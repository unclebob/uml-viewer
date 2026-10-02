(ns uml-viewer.go-language.graph-go
  "Go LanguageGraph: one class per package directory, import edges."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]))

(def ^:private skip-dir-names
  #{"testdata" "vendor" "node_modules"})

(defn- skip-dir? [name]
  (or (str/starts-with? name ".")
      (str/starts-with? name "_")
      (contains? skip-dir-names name)))

(defn- go-source? [^java.io.File f]
  (let [name (.getName f)]
    (and (.isFile f)
         (str/ends-with? name ".go")
         (not (str/ends-with? name "_test.go")))))

(defn- go-files [^java.io.File dir]
  (->> (.listFiles dir)
       (filter go-source?)
       (sort-by #(.getName ^java.io.File %))
       vec))

(defn- raw-end
  "Index just after the raw string that opens at `i`. It has no escapes."
  [source i n]
  (if-let [j (str/index-of source \` (inc i))] (inc j) n))

(defn- mask-go-step [source sb holes i n]
  (let [c (.charAt ^String source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt)
      {:i (graph/mask-line-comment source sb i n) :holes holes}

      (graph/block-comment? c nxt)
      {:i (graph/mask-block-comment source sb i n) :holes holes}

      (= c \`)
      (graph/copy-span sb source holes i (raw-end source i n))

      (#{\" \'} c)
      (graph/copy-span sb source holes i (graph/scan-quoted source i c n))

      :else
      (do (.append ^StringBuilder sb c)
          {:i (inc i) :holes holes}))))

(defn- mask-comments
  "Comments become spaces. String, raw string, and rune literals are copied
  and recorded as half-open holes so a match inside them can be ignored."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 holes []]
      (if (>= i n)
        {:text (str sb) :holes holes}
        (let [step (mask-go-step source sb holes i n)]
          (recur (:i step) (:holes step)))))))

(defn- in-hole? [holes i]
  (boolean (some (fn [[a b]] (and (<= a i) (< i b))) holes)))

(defn- matches
  "Matches of `pattern` in `text` that do not start inside a literal.
  Each is `{:start :groups}`."
  [text holes pattern]
  (let [m (re-matcher pattern text)]
    (loop [acc []]
      (if (.find m)
        (recur (if (in-hole? holes (.start m))
                 acc
                 (conj acc {:start (.start m)
                            :end (.end m)
                            :groups (mapv #(.group m (int %))
                                          (range 1 (inc (.groupCount m))))})))
        acc))))

(defn- import-specs [chunk]
  (mapv (fn [[_ alias path]]
          (cond-> {:path path} alias (assoc :alias alias)))
        (re-seq #"(?:([A-Za-z_]\w*|\.)\s+)?[\"`]([^\"`]+)[\"`]" chunk)))

(defn- imports-of [text holes]
  (let [blocks (matches text holes #"(?m)^import\s*\(([^)]*)\)")
        singles (matches text holes #"(?m)^import[ \t]+([^(\s][^\n]*)")]
    (->> (concat blocks singles)
         (sort-by :start)
         (mapcat #(import-specs (first (:groups %))))
         vec)))

(defn- exported? [name]
  (Character/isUpperCase (.charAt ^String name 0)))

(defn- bracket-end
  "Index just after the `]` that closes the `[` at the start of `s`."
  [^String s]
  (loop [i 1 depth 1]
    (cond
      (zero? depth) i
      (>= i (count s)) (count s)
      :else (recur (inc i) (case (.charAt s i)
                             \[ (inc depth)
                             \] (dec depth)
                             depth)))))

(defn- type-kind
  "`rest-of-line` starts right after the type's name. gofmt writes type
  parameters against the name (`List[T any]`) and an array or slice type
  after a space (`Args []interface{}`), so only the first is skipped."
  [^String rest-of-line]
  (let [body (if (str/starts-with? rest-of-line "[")
               (subs rest-of-line (bracket-end rest-of-line))
               rest-of-line)]
    (if (re-find #"^\s*interface\b" body) :interface :type)))

(defn- block-matches
  "Matches of `spec` inside each block that `opener` starts, up to the `)`
  in column 0 that closes it. Each is `{:start :groups}`."
  [text holes opener spec]
  (for [{:keys [end]} (matches text holes opener)
        :let [close (re-matcher #"(?m)^\)" text)
              stop (if (.find close (int end)) (.start close) (count text))
              m (doto (re-matcher spec text)
                  (.useAnchoringBounds false)
                  (.region (int end) (int stop)))]
        found (loop [acc []]
                (if (.find m)
                  (recur (conj acc {:start (.start m)
                                    :groups (mapv #(.group m (int %))
                                                  (range 1 (inc (.groupCount m))))}))
                  acc))]
    found))

(defn- type-decl [{:keys [start groups]}]
  {:start start :name (first groups) :kind (type-kind (second groups))})

(defn- block-types
  "Type specs inside each `type ( ... )` block, one tab in."
  [text holes]
  (map type-decl (block-matches text holes #"(?m)^type\s*\(" #"(?m)^\t([A-Za-z_]\w*)([^\n]*)")))

(defn- decls-of [text holes]
  (let [funcs (map (fn [{:keys [start groups]}]
                     {:start start :name (first groups) :kind :func})
                   (matches text holes #"(?m)^func\s+([A-Za-z_]\w*)\s*[\[(]"))
        types (map type-decl (matches text holes #"(?m)^type\s+([A-Za-z_]\w*)([^\n]*)"))]
    (->> (concat funcs types (block-types text holes))
         (filter #(exported? (:name %)))
         (sort-by :start)
         (mapv #(dissoc % :start)))))

(defn- asserts-of
  "`var _ pkg.T = ...`, alone or in a `var ( ... )` block. A `const` block's
  `_ pkg.T = iota` has the same shape and is not an assertion."
  [text holes]
  (mapv (fn [{:keys [groups]}]
          {:qualifier (first groups) :name (second groups)})
        (concat
          (matches text holes
                   #"(?m)^var[ \t]+_[ \t]+\*?([A-Za-z_]\w*)\.([A-Za-z_]\w*)[ \t]*=")
          (block-matches text holes #"(?m)^var\s*\("
                         #"(?m)^\t_[ \t]+\*?([A-Za-z_]\w*)\.([A-Za-z_]\w*)[ \t]*="))))

(defn read-file
  "Surface of one Go source file: package name, imports, exported top-level
  funcs and types in source order, and `var _ pkg.T = ...` assertions."
  [source]
  (let [{:keys [text holes]} (mask-comments source)]
    {:package (some-> (matches text holes #"(?m)^package\s+([A-Za-z_]\w*)")
                      first :groups first)
     :imports (imports-of text holes)
     :decls (decls-of text holes)
     :asserts (asserts-of text holes)}))

(defn module-path
  "Module path declared in go.mod `text`, or nil."
  [text]
  (second (re-find #"(?m)^module\s+\"?([^\s\"]+)\"?" text)))

(defn- go-mod
  "go.mod at `dir` or the nearest ancestor, or nil."
  [^java.io.File dir]
  (loop [d dir]
    (when d
      (let [f (io/file d "go.mod")]
        (if (.isFile f) f (recur (.getParentFile d)))))))

(defn- rel-path [^java.io.File base ^java.io.File file]
  (str/replace (str (.relativize (.toPath base) (.toPath file))) #"\\" "/"))

(defn- symlink? [^java.io.File f]
  (java.nio.file.Files/isSymbolicLink (.toPath f)))

(defn- package-dirs
  "Directories under `root` that hold non-test Go files. A directory with
  its own go.mod is another module and is not entered, and a symlinked one
  is not followed, as the go tool does not follow it."
  [^java.io.File root]
  (let [enter? (fn [^java.io.File d]
                 (or (= d root)
                     (and (not (skip-dir? (.getName d)))
                          (not (symlink? d))
                          (not (.isFile (io/file d "go.mod"))))))]
    (->> (tree-seq (fn [^java.io.File d] (and (.isDirectory d) (enter? d)))
                   (fn [^java.io.File d] (vec (.listFiles d)))
                   root)
         (filter #(and (.isDirectory ^java.io.File %) (enter? %)))
         (filter #(seq (go-files %)))
         (sort-by #(.getPath ^java.io.File %)))))

(defn- import-path [module ^java.io.File mod-root ^java.io.File dir]
  (when module
    (let [rel (rel-path mod-root dir)]
      (if (str/blank? rel) module (str module "/" rel)))))

(defn- representative
  "File the class card opens: the one named after the directory, then
  doc.go, then the first file."
  [^java.io.File dir files]
  (let [by-name (into {} (map (juxt #(.getName ^java.io.File %) identity) files))]
    (or (by-name (str (.getName dir) ".go"))
        (by-name "doc.go")
        (first files))))

(defn- package-meta [^java.io.File dir root prefix ns-prefix module mod-root]
  (let [files (go-files dir)
        surfaces (mapv #(read-file (slurp %)) files)
        relative (str/replace (rel-path root dir) "/" ".")
        ns-str (graph/ns-join ns-prefix relative)]
    {:id (graph/id-of ns-str prefix)
     :ns ns-str
     :import-path (import-path module mod-root dir)
     :package (some :package surfaces)
     :file (representative dir files)
     :surfaces surfaces}))

(defn- foreign-id [path]
  (keyword (str/replace path "/" ".")))

(defn- binding-name [{:keys [path alias]} by-path]
  (or alias
      (:package (by-path path))
      (last (str/split path #"/"))))

(defn- assertion-targets
  "Project packages named by `var _ pkg.T = ...` in one file."
  [surface by-path]
  (let [bound (into {} (map (juxt #(binding-name % by-path) :path) (:imports surface)))]
    (keep (fn [{:keys [qualifier]}]
            (some-> (bound qualifier) by-path :id))
          (:asserts surface))))

(defn- interface-package? [decls]
  (let [types (remove #(= :func (:kind %)) decls)]
    (and (seq types)
         (= (count types) (count decls))
         (every? #(= :interface (:kind %)) types))))

(defn- analyze [pkg by-path]
  (let [imports (mapcat :imports (:surfaces pkg))
        decls (mapcat :decls (:surfaces pkg))]
    {:requires (->> imports (keep #(:id (by-path (:path %)))) distinct vec)
     :foreigns (->> imports
                    (remove #(by-path (:path %)))
                    (map #(foreign-id (:path %)))
                    distinct vec)
     :impls (->> (:surfaces pkg)
                 (mapcat #(assertion-targets % by-path))
                 distinct vec)
     :ops (->> decls
               (map :name)
               distinct
               (mapv (fn [n] {:name n :text n})))
     :stereotype (when (interface-package? decls) :interface)}))

(defn- as-edges [c]
  (graph/member-edges c [[:impls :implements]
                         [:requires :dependency]
                         [:foreigns :dependency]]))

(defn- public-class [pkg facts]
  (cond-> {:id (:id pkg)
           :ns (:ns pkg)
           :name (graph/module-name (:id pkg))
           :lang :go
           :file (graph/relative-path (:file pkg))}
    (seq (:ops facts)) (assoc :ops (:ops facts))
    (:stereotype facts) (assoc :stereotype (:stereotype facts))))

(defn- unique-ids!
  "Two directories can reach one id: under prefix `shop`, the root package
  `shop` and a child directory `shop` both become `:shop`."
  [pkgs]
  (when-let [dup (some (fn [[id n]] (when (> n 1) id)) (frequencies (map :id pkgs)))]
    (throw (ex-info (str "duplicate class id: " dup) {:id dup})))
  pkgs)

(defn- drop-shadowed-foreigns
  "A foreign import whose dotted path equals a project id, such as the
  standard library's `log` beside a project package `log`, cannot be told
  apart from that package, so it is left out rather than drawn as it."
  [parsed project-ids]
  (mapv (fn [p] (update p :foreigns #(vec (remove project-ids %)))) parsed))

(defrecord GoGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "app")
          ns-prefix (or (:ns-prefix opts) prefix)
          rootf (.getCanonicalFile (io/file root))
          mod-file (go-mod rootf)
          module (some-> mod-file slurp module-path)
          mod-root (some-> mod-file .getParentFile)
          pkgs (unique-ids! (mapv #(package-meta % rootf prefix ns-prefix module mod-root)
                                  (package-dirs rootf)))
          by-path (into {} (keep (fn [p]
                                   (when (:import-path p) [(:import-path p) p]))
                                 pkgs))
          project-ids (set (map :id pkgs))
          parsed (drop-shadowed-foreigns
                   (mapv (fn [p] (merge {:id (:id p)} (analyze p by-path))) pkgs)
                   project-ids)
          foreigns (->> parsed
                        (mapcat :foreigns)
                        distinct
                        (mapv graph/foreign-class))]
      {:classes (into (mapv public-class pkgs parsed) foreigns)
       :edges (->> (mapcat as-edges parsed)
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)})))

(def impl (->GoGraph))

(graph/register! :go impl)
