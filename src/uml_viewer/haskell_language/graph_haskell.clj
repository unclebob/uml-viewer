(ns uml-viewer.haskell-language.graph-haskell
  "Haskell LanguageGraph: one class per module and import dependencies."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]
            [uml-viewer.haskell-language.syntax :as syntax]))

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

(defn read-module
  "Read the module name, imported modules, and top-level signatures."
  [source]
  (syntax/read-module source))

(defn- relative-name [module prefix]
  (cond
    (= module prefix) ""
    (str/starts-with? module (str prefix ".")) (subs module (inc (count prefix)))
    :else module))

(defn- module-id [module prefix ns-prefix]
  (let [identity-ns (if (= prefix ns-prefix)
                      module
                      (graph/ns-join ns-prefix (relative-name module prefix)))]
    (graph/id-of identity-ns prefix)))

(defn- module-meta [file prefix ns-prefix]
  (let [surface (read-module (slurp file))
        module (or (:module surface) "Main")]
    (merge surface
           {:module module
            :id (module-id module prefix ns-prefix)
            :name (graph/module-name (module-id module prefix ns-prefix))
            :ns module
            :lang :haskell
            :file (graph/relative-path file)})))

(defn- reject-duplicate-modules [modules]
  (when-let [[id duplicates]
             (first (filter #(> (count (val %)) 1) (group-by :id modules)))]
    (throw (ex-info (str "duplicate Haskell module " (:module (first duplicates))
                         "; split build units into :sources with distinct :prefix values")
                    {:id id
                     :module (:module (first duplicates))
                     :files (mapv :file duplicates)})))
  modules)

(defn- public-class [module]
  (cond-> (dissoc module :module :imports)
    (seq (:ops module)) (update :ops #(mapv (fn [name] {:name name}) %))
    (empty? (:ops module)) (dissoc :ops)))

(defn- project-import? [{:keys [package]}]
  (or (nil? package) (= "this" package)))

(defn- import-id [{:keys [module package] :as imported} project prefix]
  (if (project-import? imported)
    (or (get project module)
        (when (or (= module prefix)
                  (str/starts-with? module (str prefix ".")))
          (graph/id-of module prefix))
        (keyword module))
    (keyword (str package "." module))))

(defrecord HaskellGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "Main")
          ns-prefix (or (:ns-prefix opts) prefix)
          modules (->> (source-files root)
                       (mapv #(module-meta % prefix ns-prefix))
                       reject-duplicate-modules)
          project (into {} (map (juxt :module :id) modules))
          imports (for [module modules
                        imported (:imports module)]
                    (cond-> {:from (:id module)
                             :to (import-id imported project prefix)
                             :kind :dependency}
                      (project-import? imported) (assoc :module (:module imported))))
          project-ids (set (vals project))
          foreigns (->> imports
                        (map :to)
                        distinct
                        (remove project-ids)
                        (mapv graph/foreign-class))]
      {:classes (into (mapv public-class modules) foreigns)
       :edges (->> imports
                   (map #(dissoc % :module))
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)
       :module-owners project
       :module-imports (vec imports)})))

(def impl (->HaskellGraph))

(graph/register! :haskell impl)
