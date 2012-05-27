(ns cljsbuild.crossover
  (:use
    [clojure.java.io :only [as-url resource]])
  (:require
    [cljsbuild.util :as util]
    [clojure.string :as string]
    [fs.core :as fs]))

(defn- fail [& args]
  (throw (Exception. (apply str args))))

(defn- is-macro-file? [file]
  (not (neg? (.indexOf (slurp file) ";*CLJSBUILD-MACRO-FILE*;"))))

; There is a little bit of madness here to share macros between Clojure
; and ClojureScript.  The latter needs a  (:require-macros ...) whereas the
; former just wants  (:require ...).  Thus, we have a ;*CLJSBUILD-REMOVE*;
; conditional comment to allow different code to be used for ClojureScript files.
(defn- filtered-crossover-file [file]
  (str
    "; DO NOT EDIT THIS FILE! IT WAS AUTOMATICALLY GENERATED BY\n"
    "; lein-cljsbuild FROM THE FOLLOWING SOURCE FILE:\n"
    "; " file "\n\n"
    (string/replace (slurp file) ";*CLJSBUILD-REMOVE*;" "")))

(defn- crossover-to [crossover-path [from-parent from-resource]]
  (let [subpath (string/replace-first
                  (fs/absolute-path (.getPath from-resource))
                  (fs/absolute-path from-parent) "")
        to-file (fs/normalized-path
                  (util/join-paths (fs/absolute-path crossover-path) subpath))]
    (string/replace to-file #"\.clj$" ".cljs")))

(defn- recurse-resource-dir [dir]
  (when dir
    ; We can't determine the contents of a jar dir.  Thus, crossover files
    ; in jars cannot be specified recursively; they have to be named file
    ; by file.
    (if (= (.getProtocol dir) "file")
      (let [files (util/find-files (.getPath dir) #{"clj"})]
        (map #(as-url (str "file:" %)) files))
      [dir])))

(defn- truncate-uri-path [uri n]
  (if uri
    (let [uri-path (.getPath uri)]
      (subs uri-path 0 (- (count uri-path) n)))
    nil))

(defn- ns-to-path [ns]
  (let [underscored (string/replace (str ns) #"-" "_")]
    (apply util/join-paths
      (string/split underscored #"\."))))

(defn- find-crossover [crossover macros?]
  (let [ns-path (ns-to-path crossover)
        as-dir (resource ns-path)
        dir-parent (truncate-uri-path as-dir (count ns-path))
        recurse-dirs (recurse-resource-dir as-dir)
        ns-file-path (str ns-path ".clj")
        as-file (resource ns-file-path)
        file-parent (truncate-uri-path as-file (count ns-file-path))
        all-resources (conj
                        (map vector (repeat dir-parent) recurse-dirs)
                        [file-parent as-file])
        all-resources (remove
                        (comp nil? second)
                        all-resources)
        keep-wanted (if macros? filter remove)
        resources (keep-wanted
                    (comp is-macro-file? second)
                    all-resources)]
    (when (empty? all-resources)
      (println "WARNING: Unable to find crossover: " crossover))
    resources))

(defn find-crossovers [crossovers macros?]
  (distinct
    (mapcat #(find-crossover % macros?) crossovers)))

(defn crossover-macro-paths [crossovers]
  (let [macro-paths (find-crossovers crossovers true)
        macro-files (remove #(not= (.getProtocol (second %)) "file") macro-paths)]
    (map (fn [[parent file]]
           (let [file-path (.getPath file)
                 classpath-path (string/replace-first file-path parent "")]
             {:absolute (fs/absolute-path file-path)
              :classpath classpath-path}))
         macro-files)))

(defn crossover-needs-update? [from-resource to-file]
  (or
    (not (fs/exists? to-file))
    (and
      ; We can't determine the mtime for jar resources; they'll just
      ; be copied once and that's it.
      (= "file" (.getProtocol from-resource))
      (> (fs/mod-time (.getPath from-resource)) (fs/mod-time to-file)))))

(defn write-crossover
  "Write a temp file and atomically rename to the real file
to prevent the compiler from reading a half-written file."
  [from-resource to-file]
  (let [temp-file (str to-file ".tmp")]
    (spit temp-file (filtered-crossover-file from-resource))
    (fs/rename temp-file to-file)
    ; Mark the file as read-only, to hopefully warn the user not to modify it.
    (fs/chmod "-w" to-file)))

(defn copy-crossovers [crossover-path crossovers]
  (let [from-resources (find-crossovers crossovers false)
        to-files (map (partial crossover-to crossover-path) from-resources)]
    (doseq [dir (distinct (map fs/parent to-files))]
      (fs/mkdirs dir))
    (doseq [[[_ from-resource] to-file] (zipmap from-resources to-files)]
      (when (crossover-needs-update? from-resource to-file)
        (write-crossover from-resource to-file)))))
