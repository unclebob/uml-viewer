(ns uml-viewer.haskell-language.graph-haskell
  "Haskell LanguageGraph: one class per module and import dependencies."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]))

(defn- excluded-path? [file]
  (let [path (str/replace (.getPath ^java.io.File file) #"\\" "/")
        name (.getName ^java.io.File file)]
    (or (re-find #"/(?:dist-newstyle|\.stack-work|test|tests)/" path)
        (re-find #"(?:Spec|Test)\.hs$" name))))

(defn- source-files [root]
  (->> (file-seq (.getCanonicalFile (io/file root)))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".hs"))
       (remove excluded-path?)
       (sort-by #(.getPath ^java.io.File %))))

(defn- without-block-comments [source]
  (str/replace source #"(?s)\{-(?!#).*?-\}" ""))

(defn- without-line-comments [source]
  (str/replace source #"(?m)--.*$" ""))

(defn- visible-source [source]
  (-> source without-block-comments without-line-comments))

(defn read-module
  "Read the module name, imported modules, and top-level signatures."
  [source]
  (let [text (visible-source source)
        module (second (re-find #"(?m)^\s*module\s+([A-Z][\w.']*)\b" text))
        imports (->> (re-seq
                       #"(?m)^\s*import\s+(?:\{-#\s*SOURCE\s*#-\}\s*)?(?:safe\s+)?(?:\"[^\"]+\"\s+)?(?:qualified\s+)?([A-Z][\w.']*)\b"
                       text)
                     (map second)
                     distinct
                     vec)
        ops (->> (re-seq #"(?m)^([a-z_][\w']*(?:\s*,\s*[a-z_][\w']*)*)\s*::" text)
                 (mapcat (fn [[_ names]] (str/split names #"\s*,\s*")))
                 distinct
                 vec)]
    {:module module :imports imports :ops ops}))

(defn- relative-module [file root]
  (let [root-path (.toPath (.getCanonicalFile (io/file root)))
        file-path (.toPath (.getCanonicalFile (io/file file)))
        relative (str (.relativize root-path file-path))]
    (-> relative
        (str/replace #"\\" "/")
        (str/replace #"\.hs$" "")
        (str/replace #"/" "."))))

(defn- module-meta [file root prefix ns-prefix]
  (let [surface (read-module (slurp file))
        module (or (:module surface)
                   (graph/ns-join ns-prefix (relative-module file root)))]
    (merge surface
           {:id (graph/id-of module prefix)
            :name (graph/module-name (graph/id-of module prefix))
            :ns module
            :lang :haskell
            :file (graph/relative-path file)})))

(defn- public-class [module]
  (cond-> (dissoc module :module :imports)
    (seq (:ops module)) (update :ops #(mapv (fn [name] {:name name}) %))
    (empty? (:ops module)) (dissoc :ops)))

(defn- import-id [imported project prefix]
  (or (get project imported)
      (when (or (= imported prefix)
                (str/starts-with? imported (str prefix ".")))
        (graph/id-of imported prefix))
      (keyword imported)))

(defrecord HaskellGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "Main")
          ns-prefix (or (:ns-prefix opts) prefix)
          modules (mapv #(module-meta % root prefix ns-prefix) (source-files root))
          project (into {} (map (juxt :module :id) modules))
          imports (for [module modules
                        imported (:imports module)]
                    {:from (:id module)
                     :to (import-id imported project prefix)
                     :kind :dependency})
          project-ids (set (vals project))
          foreigns (->> imports
                        (map :to)
                        distinct
                        (remove project-ids)
                        (mapv graph/foreign-class))]
      {:classes (into (mapv public-class modules) foreigns)
       :edges (->> imports
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)})))

(def impl (->HaskellGraph))

(graph/register! :haskell impl)
