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

(def ^:private symbol-chars
  (set "!#$%&*+./<=>?@\\^|~-:"))

(defn- symbol-char? [c]
  (boolean (and c (symbol-chars c))))

(defn- dash-run-end [source i]
  (loop [j i]
    (if (and (< j (count source)) (= \- (.charAt ^String source j)))
      (recur (inc j))
      j)))

(defn- line-comment? [source i]
  (when (and (.startsWith ^String source "--" (int i))
             (not (symbol-char? (when (pos? i) (.charAt ^String source (dec i))))))
    (let [end (dash-run-end source i)]
      (not (symbol-char? (when (< end (count source))
                           (.charAt ^String source end)))))))

(defn- nested-comment-end [source i]
  (let [n (count source)]
    (loop [j (+ i 2) depth 1]
      (cond
        (>= j n) n
        (.startsWith ^String source "{-" (int j)) (recur (+ j 2) (inc depth))
        (.startsWith ^String source "-}" (int j))
        (if (= depth 1) (+ j 2) (recur (+ j 2) (dec depth)))
        :else (recur (inc j) depth)))))

(defn- line-comment-end [source i]
  (or (str/index-of source \newline i) (count source)))

(defn- mask-span [sb source start end]
  (doseq [i (range start end)]
    (.append ^StringBuilder sb
             (if (= \newline (.charAt ^String source i)) \newline \space))))

(defn- identifier-char? [c]
  (boolean (and c (or (Character/isLetterOrDigit ^Character c) (= \_ c)))))

(defn- char-literal-start? [source i]
  (and (= \' (.charAt ^String source i))
       (not (identifier-char? (when (pos? i)
                                (.charAt ^String source (dec i)))))))

(defn- char-literal-end [source i n]
  (let [end (graph/scan-quoted source i \' n)
        newline (str/index-of source \newline i)]
    (when (or (nil? newline) (<= end newline)) end)))

(defn- block-comment-start? [source i]
  (and (.startsWith ^String source "{-" (int i))
       (not (.startsWith ^String source "{-#" (int i)))))

(defn- token-end [source i n]
  (cond
    (= \" (.charAt ^String source i)) (graph/scan-quoted source i \" n)
    (char-literal-start? source i) (or (char-literal-end source i n) (inc i))
    :else (inc i)))

(defn- copy-span [sb source start end]
  (.append ^StringBuilder sb (subs source start end)))

(defn- visible-source
  "Mask comments while preserving strings, characters, pragmas, and newlines."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (cond
          (block-comment-start? source i)
          (let [end (nested-comment-end source i)]
            (mask-span sb source i end)
            (recur end))

          (line-comment? source i)
          (let [end (line-comment-end source i)]
            (mask-span sb source i end)
            (recur end))

          :else
          (let [end (token-end source i n)]
            (copy-span sb source i end)
            (recur end)))))))

(def ^:private name-pattern
  "(?:[a-z_][\\w']*|\\([-!#$%&*+./<=>?@\\\\^|~:]+\\))")

(def ^:private signature-pattern
  (re-pattern (str "(?m)^(" name-pattern
                   "(?:\\s*,\\s*" name-pattern ")*)\\s*::")))

(defn read-module
  "Read the module name, imported modules, and top-level signatures."
  [source]
  (let [text (visible-source source)
        module (second (re-find #"(?m)^\s*module\s+([A-Z][\w.']*)\b" text))
        imports (->> (re-seq
                       #"(?m)^\s*import\s+(?:\{-#\s*SOURCE\s*#-\}\s*)?(?:safe\s+)?(?:qualified\s+)?(?:\"[^\"]+\"\s+)?([A-Z][\w.']*)\b"
                       text)
                     (map second)
                     distinct
                     vec)
        ops (->> (re-seq signature-pattern text)
                 (mapcat (fn [[_ names]] (map str/trim (str/split names #","))))
                 distinct
                 vec)]
    {:module module :imports imports :ops ops}))

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
          modules (->> (source-files root)
                       (mapv #(module-meta % prefix ns-prefix))
                       reject-duplicate-modules)
          project (into {} (map (juxt :module :id) modules))
          imports (for [module modules
                        imported (:imports module)]
                    {:from (:id module)
                     :to (import-id imported project prefix)
                     :module imported
                     :kind :dependency})
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
