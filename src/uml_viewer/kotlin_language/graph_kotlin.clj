(ns uml-viewer.kotlin-language.graph-kotlin
  "Kotlin LanguageGraph: one class per type, plus a FileKt class for a file
  with top-level functions. Each `:ns` is the name JaCoCo reports."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]
            [uml-viewer.kotlin-language.parse-kotlin :as parse]))

(def ^:private skip-dir-names
  #{"build" "out" "target" "node_modules"})

(defn- skip-dir? [name]
  (or (str/starts-with? name ".")
      (contains? skip-dir-names name)))

(def ^:private test-dir-names
  #{"test" "tests" "spec" "specs" "__tests__"})

(defn- test-dir?
  "crapper's rule: `test`, `tests`, `spec`, `specs`, `__tests__`, or a source
  set such as `jvmTest`, `commonTest` or `testFixtures`."
  [name]
  (or (contains? test-dir-names name)
      (boolean (re-matches #"[a-z][A-Za-z0-9]*Tests?|test[A-Z][A-Za-z0-9]*" name))))

(defn- test-path?
  "Any directory segment of the path, never the file name, is a test directory."
  [segs]
  (boolean (some test-dir? (butlast segs))))

(defn- relative-segs [root file]
  (str/split (str/replace (str (.relativize (.toPath root) (.toPath file))) #"\\" "/")
             #"/"))

(defn- source-files [root]
  (let [root (.getCanonicalFile (io/file root))]
    (->> (tree-seq (fn [f]
                     (and (.isDirectory f)
                          (or (= f root) (not (skip-dir? (.getName f))))))
                   (fn [dir] (vec (.listFiles dir)))
                   root)
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".kt"))
         (remove #(test-path? (relative-segs root %)))
         (sort-by #(.getPath %)))))

(defn- qualify [package path]
  (if (str/blank? package) path (str package "." path)))

(def ^:private stereotypes
  {:interface :interface :enum :enumeration :abstract :abstract})

(defn- ops-for [funs owner]
  (->> funs
       (filter #(and (= owner (:owner %)) (:public? %)))
       (map :name)
       distinct
       (mapv (fn [n] {:name n :text n}))))

(defn- file-classes
  "One class per type, and the FileKt class when the file has top-level functions."
  [surface file]
  (let [{:keys [package types funs facade]} surface
        type-classes (mapv (fn [t]
                             (cond-> {:ns (qualify package (:path t))
                                      :path (:path t)
                                      :span (:span t)
                                      :top? (nil? (:parent t))
                                      :header (:header t)
                                      :ops (ops-for funs (:path t))}
                               (stereotypes (:kind t)) (assoc :stereotype (stereotypes (:kind t)))))
                           types)]
    (cond-> type-classes
      facade (conj {:ns (qualify package facade)
                    :path facade
                    :facade? true
                    :top? true
                    :ops (ops-for funs nil)}))))

(defn- with-ids [classes prefix file]
  (mapv (fn [c]
          (let [id (graph/id-of (str/replace (:ns c) #"\s" "_") prefix)]
            (assoc c :id id :name (last (str/split (:path c) #"\.")) :lang :kotlin
                     :file (graph/relative-path file))))
        classes))

(defn- package-index
  "Package name to its top-level type names and ids, and its top-level
  function names and the FileKt id that owns each."
  [parsed]
  (reduce (fn [acc {:keys [surface classes]}]
            (let [pkg (or (:package surface) "")
                  facade (first (filter :facade? classes))]
              (-> acc
                  (update-in [pkg :types] merge
                             (into {} (keep (fn [c] (when (and (:top? c) (not (:facade? c)))
                                                      [(:path c) (:id c)]))
                                            classes)))
                  (update-in [pkg :funs] merge
                             (into {} (keep (fn [f] (when (and facade (nil? (:owner f)))
                                                      [(:name f) (:id facade)]))
                                            (:funs surface)))))))
          {}
          parsed))

(defn- path-prefixes
  "`a.b.c`, `a.b`, `a`: longest first."
  [path]
  (->> (iterate #(when-let [i (some-> % (str/last-index-of "."))] (subs % 0 i)) path)
       (take-while some?)))

(defn- project-path?
  "`path` names, or lies under, a project type or package."
  [path by-ns index]
  (boolean (some #(or (contains? by-ns %) (contains? index %)) (path-prefixes path))))

(defn- resolve-import
  "Target of `import path`: a project type, a top-level function's FileKt,
  the type that owns a member (`demo.Color.RED`), or a foreign name.
  Anything else under a project package, such as a top-level property, is nil."
  [{:keys [path]} by-ns index]
  (let [dot (str/last-index-of path ".")
        pkg (if dot (subs path 0 dot) "")
        simple (if dot (subs path (inc dot)) path)]
    (if-let [id (or (get-in index [pkg :funs simple])
                    (some by-ns (path-prefixes path)))]
      {:id id}
      (when-not (project-path? path by-ns index)
        {:foreign path}))))

(defn- explicit-bindings [imports by-ns index]
  (into {}
        (keep (fn [imp]
                (when-not (:star? imp)
                  (when-let [target (resolve-import imp by-ns index)]
                    [(or (:alias imp) (last (str/split (:path imp) #"\.")))
                     target]))))
        imports))

(defn- visible-types
  "Simple type names visible without an explicit import: the same package,
  then project packages imported with `*`. Explicit imports win."
  [package imports index]
  (let [stars (->> imports (filter :star?) (map :path) (filter #(contains? index %)))]
    (into {}
          (for [pkg (concat (reverse stars) [(or package "")])
                [name id] (get-in index [pkg :types])]
            [name {:id id}]))))

(defn- owner-at
  "Class whose own text holds `pos`: the smallest type span that encloses
  it, else the FileKt class."
  [classes pos]
  (or (->> classes
           (filter (fn [c] (when-let [[a b] (:span c)] (and (<= a pos) (< pos b)))))
           (sort-by (fn [c] (let [[a b] (:span c)] (- b a))))
           first)
      (first (filter :facade? classes))))

(def ^:private ident-pattern #"(?<![\w.$`])([A-Za-z_]\w*)(\s*\()?")

(defn- uses
  "`[class-id target]` for each name the file uses. A same-package
  top-level function counts only as a call, and not where the class
  declares a function of that name."
  [text classes bindings fun-bindings]
  (for [{:keys [start groups]} (parse/matches ident-pattern text)
        :let [[name call] groups
              owner (owner-at classes start)
              own? (some #(= name (:name %)) (:ops owner))
              target (or (get bindings name)
                         (when (and call (not own?)) (get fun-bindings name)))]
        :when (and owner target)]
    [(:id owner) target]))

(defn- fallback-uses
  "An import with no use found, and a foreign `*` import, go to every
  top-level class of the file."
  [imports explicit used classes by-ns index]
  (let [tops (map :id (filter :top? classes))
        unused (remove (set used) (vals explicit))
        stars (keep (fn [imp]
                      (when (and (:star? imp) (not (project-path? (:path imp) by-ns index)))
                        {:foreign (:path imp)}))
                    imports)]
    (for [target (concat unused stars) id tops] [id target])))

(defn- resolve-super
  "Project id of supertype `name` seen from the type at `path`: a type
  nested in an enclosing type, then the file's bindings, then a full name."
  [name path package bindings by-ns]
  (let [scopes (rest (path-prefixes path))]
    (or (some #(get by-ns (qualify package (str % "." name))) scopes)
        (:id (get bindings name))
        (get by-ns name))))

(defn- super-edges [classes package bindings by-ns]
  (for [c classes
        :when (:header c)
        {:keys [name call?]} (parse/supertypes (:header c))
        :let [to (resolve-super name (:path c) package bindings by-ns)]
        :when to]
    {:from (:id c) :to to :kind (if call? :inheritance :implements)}))

(defn- use-edges [pairs]
  (for [[from target] (distinct pairs)]
    {:from from
     :to (or (:id target) (keyword (:foreign target)))
     :kind :dependency}))

(defn- file-edges [{:keys [surface classes]} by-ns index]
  (let [{:keys [text package imports]} surface
        explicit (explicit-bindings imports by-ns index)
        bindings (merge (visible-types package imports index) explicit)
        fun-bindings (get-in index [(or package "") :funs])
        used (uses (parse/header-lines-blanked text) classes bindings fun-bindings)
        fallback (fallback-uses imports explicit (map second used) classes by-ns index)
        supers (super-edges classes package bindings by-ns)
        typed (set (map (juxt :from :to) supers))]
    {:edges (concat supers
                    (remove #(typed [(:from %) (:to %)])
                            (use-edges (concat used fallback))))
     :foreigns (->> (concat used fallback) (keep (comp :foreign second)) distinct)}))

(defn- public-class [c]
  (cond-> (select-keys c [:id :name :ns :stereotype :lang :file :ops])
    (empty? (:ops c)) (dissoc :ops)))

(defrecord KotlinGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "app")
          parsed (mapv (fn [file]
                         (let [surface (parse/read-source (.getName file) (slurp file))]
                           {:surface surface
                            :classes (with-ids (file-classes surface file) prefix file)}))
                       (source-files root))
          classes (->> (mapcat :classes parsed)
                       (reduce (fn [{:keys [seen] :as acc} c]
                                 (if (seen (:id c))
                                   acc
                                   (-> acc (update :seen conj (:id c)) (update :out conj c))))
                               {:seen #{} :out []})
                       :out)
          by-ns (into {} (map (juxt :ns :id) classes))
          index (package-index parsed)
          results (map #(file-edges % by-ns index) parsed)
          project-ids (set (map :id classes))]
      {:classes (into (mapv public-class classes)
                      (->> (mapcat :foreigns results)
                           distinct
                           (map keyword)
                           (remove project-ids)
                           (mapv graph/foreign-class)))
       :edges (->> (mapcat :edges results)
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)})))

(def impl (->KotlinGraph))

(graph/register! :kotlin impl)
