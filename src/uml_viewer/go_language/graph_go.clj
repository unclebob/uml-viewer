(ns uml-viewer.go-language.graph-go
  "Go LanguageGraph: one class per package, one per package-level type.
  Imports are dependencies, method sets are implements."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]))

;; ---------------------------------------------------------------- masking

(defn- blank-char [c]
  (if (= c \newline) \newline \space))

(defn- literal-end
  "Index just after the string, rune, or raw literal that opens at `i`."
  [source i c n]
  (if (= c \`)
    (inc (or (str/index-of source "`" (inc i)) (dec n)))
    (graph/scan-quoted source i c n)))

(defn- mask-literal
  "Copy the delimiters of the literal at `i`, blank its contents."
  [source sb i end]
  (.append ^StringBuilder sb (.charAt ^String source i))
  (doseq [k (range (inc i) (dec end))]
    (.append ^StringBuilder sb (blank-char (.charAt ^String source k))))
  (when (> (dec end) i)
    (.append ^StringBuilder sb (.charAt ^String source (dec end))))
  end)

(defn- mask-step [source sb i n]
  (let [c (.charAt ^String source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt) [(graph/mask-line-comment source sb i n) nil]
      (graph/block-comment? c nxt) [(graph/mask-block-comment source sb i n) nil]
      (#{\" \' \`} c) (let [end (literal-end source i c n)]
                        [(mask-literal source sb i end) [i end]])
      :else (do (.append ^StringBuilder sb c) [(inc i) nil]))))

(defn mask
  "Comments and literal contents become blanks, at the same offsets.
  `:strings` lists each literal as `[start end]`."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 strings []]
      (if (>= i n)
        {:code (str sb) :strings strings}
        (let [[j span] (mask-step source sb i n)]
          (recur j (cond-> strings span (conj span))))))))

;; ---------------------------------------------------------------- brackets

(def ^:private openers {\( \) \[ \] \{ \}})
(def ^:private closers #{\) \] \}})

(defn matching
  "Index of the bracket that closes the one at `i` in `code`, or nil."
  [^String code i]
  (let [n (count code)]
    (loop [j (inc i) depth 1]
      (when (< j n)
        (let [c (.charAt code j)
              depth (cond (openers c) (inc depth)
                          (closers c) (dec depth)
                          :else depth)]
          (if (zero? depth) j (recur (inc j) depth)))))))

(defn- inside
  "Text between the bracket at `i` and its partner."
  [code i]
  (subs code (inc i) (or (matching code i) (count code))))

(defn- depth-step [depth c]
  (cond (openers c) (inc depth)
        (closers c) (dec depth)
        :else depth))

(defn split-top
  "Pieces of `text` cut at each top-level character in `seps`."
  [text seps]
  (loop [i 0 depth 0 start 0 acc []]
    (if (>= i (count text))
      (conj acc (subs text start))
      (let [c (.charAt ^String text i)]
        (if (and (zero? depth) (seps c))
          (recur (inc i) depth (inc i) (conj acc (subs text start i)))
          (recur (inc i) (depth-step depth c) start acc))))))

(defn- members
  "Non-blank lines or `;` statements of a block body."
  [body]
  (->> (split-top body #{\newline \;})
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- item-count
  "Number of comma-separated items in a parameter or result list."
  [text]
  (count (remove str/blank? (split-top text #{\,}))))

;; ---------------------------------------------------------------- declarations

(def ^:private ident "[A-Za-z_][A-Za-z0-9_]*")

(defn- skip-type-params
  "Index past `[...]` when it opens at `i`, else `i`."
  [code i]
  (if (and (< i (count code)) (= \[ (.charAt ^String code i)))
    (inc (or (matching code i) (dec (count code))))
    i))

(defn- body-open
  "Index of the `{` that opens a function body in `code` from `i`, or nil.
  A brace right after `struct` or `interface` is part of a type."
  [code i]
  (let [m (re-matcher #"\{" code)]
    (loop [from i]
      (when (.find m from)
        (let [at (.start m)
              before (str/trimr (subs code i at))]
          (if (re-find #"(?:struct|interface)$" before)
            (recur (inc (or (matching code at) at)))
            at))))))

(defn- result-count [results]
  (let [r (str/trim results)]
    (cond (str/blank? r) 0
          (str/starts-with? r "(") (item-count (inside r 0))
          :else 1)))

(defn- signature
  "Name, parameter count, and result count of `name(params) results` at `at`."
  [code at name]
  (let [open (str/index-of code "(" at)
        close (or (matching code open) (count code))
        stop (or (body-open code (inc close)) (count code))]
    {:name name
     :params (item-count (subs code (inc open) close))
     :results (result-count (subs code (inc close) stop))}))

(defn- receiver-type [receiver]
  (second (re-find (re-pattern (str "(" ident ")\\s*(?:\\[[^\\]]*\\])?\\s*$")) receiver)))

(defn- read-method [decl]
  (let [open (str/index-of decl "(")
        close (or (matching decl open) (count decl))
        m (re-matcher (re-pattern (str "\\G\\s*(" ident ")\\s*\\(")) decl)]
    (when (.find m (inc close))
      (assoc (signature decl (dec (.end m)) (.group m 1))
        :receiver (receiver-type (subs decl (inc open) close))
        :text decl))))

(defn- read-func [decl]
  (let [m (re-matcher (re-pattern (str "^func\\s+(" ident ")")) decl)]
    (when (.find m)
      (let [at (skip-type-params decl (.end m))]
        (when (re-find #"^\s*\(" (subs decl at))
          (assoc (signature decl at (.group m 1)) :text decl))))))

(defn- method? [decl]
  (boolean (re-find #"^func\s*\(" decl)))

(def ^:private embed-re
  (re-pattern (str "^\\*?(" ident "(?:\\." ident ")?)(?:\\[.*\\])?$")))

(defn- struct-embeds [body]
  (->> (members body)
       (map #(str/trim (str/replace % #"[`\"]" "")))
       (keep #(second (re-find embed-re %)))
       vec))

(defn- interface-member [line]
  (if-let [[_ name] (re-find (re-pattern (str "^(" ident ")\\s*\\(")) line)]
    {:spec (signature line 0 name)}
    (if-let [[_ embed] (re-find embed-re line)]
      {:embed embed}
      {:constraint true})))

(defn- read-interface [body]
  (let [parts (map interface-member (members body))]
    {:specs (vec (keep :spec parts))
     :embeds (vec (keep :embed parts))
     :constraint (boolean (some :constraint parts))}))

(defn- type-kind [rest]
  (cond (re-find #"^struct\b" rest) :struct
        (re-find #"^interface\b" rest) :interface
        :else :other))

(defn- type-body [rest]
  (if-let [open (str/index-of rest "{")]
    (inside rest open)
    ""))

(defn- read-type-spec [spec]
  (when-let [[whole name] (re-find (re-pattern (str "^\\s*(" ident ")")) spec)]
    (let [rest (-> (subs spec (skip-type-params spec (count whole)))
                   str/trim
                   (str/replace #"^=\s*" ""))
          kind (type-kind rest)
          body (type-body rest)]
      (merge {:name name :kind kind :text spec :embeds []}
             (case kind
               :struct {:embeds (struct-embeds body)}
               :interface (read-interface body)
               {})))))

(defn- type-specs [decl]
  (let [rest (str/trim (subs decl (count "type")))]
    (if (str/starts-with? rest "(")
      (members (inside rest 0))
      [rest])))

(defn- import-alias [code start hole]
  (let [line-start (inc (max (or (str/last-index-of code "\n" hole) -1)
                             (or (str/last-index-of code "(" hole) -1)
                             (or (str/last-index-of code ";" hole) -1)))
        prefix (-> (subs code (max start line-start) hole)
                   (str/replace #"^\s*import\b" "")
                   str/trim)]
    (when-not (str/blank? prefix) prefix)))

(defn- read-imports [source code strings start end]
  (->> strings
       (filter (fn [[s _]] (and (<= start s) (< s end))))
       (mapv (fn [[s e]]
               {:alias (import-alias code start s)
                :path (subs source (inc s) (dec e))}))))

(defn- decl-starts [code]
  (let [m (re-matcher #"(?m)^(?:func|type|import|var|const)\b" code)]
    (loop [acc []]
      (if (.find m) (recur (conj acc (.start m))) acc))))

(defn- declarations [code]
  (let [starts (decl-starts code)]
    (map (fn [s e] {:start s :end e :text (subs code s e)})
         starts
         (concat (rest starts) [(count code)]))))

(defn- add-decl [surface source code strings {:keys [start end text]}]
  (cond
    (str/starts-with? text "import")
    (update surface :imports into (read-imports source code strings start end))

    (str/starts-with? text "type")
    (update surface :types into (keep read-type-spec (type-specs text)))

    (method? text)
    (cond-> surface (read-method text) (update :methods conj (read-method text)))

    (str/starts-with? text "func")
    (cond-> surface (read-func text) (update :funcs conj (read-func text)))

    :else surface))

(defn read-file
  "Declarations of one Go source file."
  [source]
  (let [{:keys [code strings]} (mask source)]
    (reduce #(add-decl %1 source code strings %2)
            {:package (second (re-find (re-pattern (str "(?m)^package\\s+(" ident ")")) code))
             :imports [] :funcs [] :methods [] :types []}
            (declarations code))))

;; ---------------------------------------------------------------- files and packages

(defn- skipped-dir? [seg]
  (or (#{"vendor" "testdata" "node_modules"} seg)
      (str/starts-with? seg ".")
      (str/starts-with? seg "_")))

(defn- canonical [file]
  (.getCanonicalFile (io/file file)))

(defn- relative [root file]
  (str/replace (str (.relativize (.toPath root) (.toPath file))) #"\\" "/"))

(defn- go-source? [root file]
  (let [name (.getName file)
        dirs (butlast (str/split (relative root file) #"/"))]
    (and (.isFile file)
         (str/ends-with? name ".go")
         (not (str/ends-with? name "_test.go"))
         (not-any? skipped-dir? dirs))))

(defn- build-ignored? [source]
  (boolean (re-find #"(?m)^//\s*(?:go:build|\+build)\s+ignore\b" source)))

(defn- go-files [root]
  (->> (file-seq root)
       (filter #(go-source? root %))
       (sort-by #(.getPath %))))

(defn- module-of
  "`[module-path module-dir]` of the nearest go.mod at or above `dir`."
  [dir]
  (loop [d dir]
    (when d
      (let [mod (io/file d "go.mod")]
        (if-let [path (and (.isFile mod)
                           (second (re-find #"(?m)^module\s+(\S+)" (slurp mod))))]
          [path d]
          (recur (.getParentFile d)))))))

(defn import-path
  "Import path of the package in `dir`, or `clause` when no go.mod is found."
  [dir clause]
  (if-let [[module module-dir] (module-of dir)]
    (let [rel (relative module-dir dir)]
      (if (str/blank? rel) module (str module "/" rel)))
    clause))

(defn- majority [xs]
  (key (apply max-key val (frequencies xs))))

(defn- home-file
  "The file that names the package: `<dir>.go`, `<package>.go`, `doc.go`, or the first."
  [dir clause files]
  (let [names (set (map #(.getName %) files))
        wanted (some names [(str (.getName dir) ".go") (str clause ".go") "doc.go"])]
    (or (some #(when (= wanted (.getName %)) %) files)
        (first files))))

(defn- read-package [root ns-prefix prefix [dir parsed]]
  (let [clause (majority (keep :package parsed))
        parsed (filterv #(= clause (:package %)) parsed)
        rel (str/replace (relative root dir) "/" ".")]
    {:id (graph/id-of (graph/ns-join ns-prefix rel) prefix)
     :name clause
     :path (import-path dir clause)
     :file (home-file dir clause (map :file parsed))
     :files parsed}))

(defn- parse-file [file]
  (let [source (slurp file)]
    (when-not (build-ignored? source)
      (assoc (read-file source) :file file))))

(defn packages
  "Go packages under `root`, one per directory."
  [root ns-prefix prefix]
  (->> (go-files root)
       (keep parse-file)
       (filter :package)
       (group-by #(.getParentFile (:file %)))
       (sort-by #(.getPath (key %)))
       (mapv #(read-package root ns-prefix prefix %))))

;; ---------------------------------------------------------------- project model

(defn- type-id [pkg type-name]
  (keyword (str (name (:id pkg)) "." type-name)))

(defn- package-types
  "Types of `pkg` with their methods and the file each is declared in."
  [pkg]
  (let [methods (group-by :receiver (mapcat #(map (fn [m] (assoc m :file (:file %))) (:methods %))
                                            (:files pkg)))]
    (vec (for [f (:files pkg)
               t (:types f)]
           (assoc t
             :id (type-id pkg (:name t))
             :ns (str (:path pkg) "." (:name t))
             :pkg (:id pkg)
             :file (:file f)
             :methods (vec (get methods (:name t))))))))

(defn- default-alias [path]
  (last (str/split path #"/")))

(defn- alias-map
  "Local name to project package for the imports of one file."
  [file by-path]
  (into {}
        (keep (fn [{:keys [alias path]}]
                (when-let [pkg (by-path path)]
                  (when-not (#{"_" "."} alias)
                    [(or alias (:name pkg) (default-alias path)) pkg]))))
        (:imports file)))

(defn- index-project [pkgs]
  (let [pkgs (mapv #(assoc % :types (package-types %)) pkgs)]
    {:pkgs pkgs
     :by-path (into {} (map (juxt :path identity) pkgs))
     :by-id (into {} (map (juxt :id identity) pkgs))
     :type-of (into {} (for [p pkgs t (:types p)] [[(:id p) (:name t)] t]))}))

(defn- resolve-name
  "Project type or package for `name` (bare or `alias.Name`) seen in `file` of `pkg`."
  [project pkg aliases name]
  (let [[a b] (str/split name #"\." 2)]
    (if b
      (when-let [target (get aliases a)]
        (or (get-in project [:type-of [(:id target) b]]) target))
      (get-in project [:type-of [(:id pkg) a]]))))

;; ---------------------------------------------------------------- edges

(def ^:private qualified-re
  (re-pattern (str "(?<![\\w.])(" ident "\\." ident ")")))

(def ^:private bare-re
  (re-pattern (str "(?<![\\w.])(" ident ")\\b(?!\\s*(?::=|=[^=]|\\.))")))

(defn- refs-in [text]
  (concat (map second (re-seq qualified-re text))
          (map second (re-seq bare-re text))))

(defn- file-of [pkg file]
  (some #(when (= file (:file %)) %) (:files pkg)))

(defn- type-refs
  "Project ids `t` names in its declaration and its methods."
  [project pkg t]
  (->> (cons {:text (:text t) :file (:file t)} (:methods t))
       (mapcat (fn [{:keys [text file]}]
                 (let [aliases (alias-map (file-of pkg file) (:by-path project))]
                   (keep #(:id (resolve-name project pkg aliases %)) (refs-in text)))))
       (remove #{(:id t) (:id pkg)})
       distinct))

(defn- package-imports [pkg]
  (->> (:files pkg) (mapcat :imports) (map :path) (remove #{"C"}) distinct))

(defn- foreign-id [path]
  (keyword (str/replace path "/" ".")))

(defn- import-edges [project pkg]
  (for [path (package-imports pkg)
        :let [target (get-in project [:by-path path])]
        :when (not= (:id target) (:id pkg))]
    {:from (:id pkg) :to (if target (:id target) (foreign-id path)) :kind :dependency}))

(defn- resolve-embed [project pkg t embed]
  (let [aliases (alias-map (file-of pkg (:file t)) (:by-path project))
        found (resolve-name project pkg aliases embed)]
    (when (:kind found) found)))

(defn- owner [project t]
  (get-in project [:by-id (:pkg t)]))

(defn- method-shapes
  "`[name params results]` of `t` and of the types it embeds."
  ([project t] (method-shapes project t #{}))
  ([project t seen]
   (if (seen (:id t))
     #{}
     (into (set (map (juxt :name :params :results) (:methods t)))
           (mapcat #(when-let [e (resolve-embed project (owner project t) t %)]
                      (method-shapes project e (conj seen (:id t))))
                   (:embeds t))))))

(defn- interface-shapes
  "Method shapes `i` requires, or nil when part of it cannot be seen."
  ([project i] (interface-shapes project i #{}))
  ([project i seen]
   (when (and (= :interface (:kind i)) (not (:constraint i)) (not (seen (:id i))))
     (let [embeds (map #(resolve-embed project (owner project i) i %) (:embeds i))]
       (when (every? some? embeds)
         (let [inner (map #(interface-shapes project % (conj seen (:id i))) embeds)]
           (when (every? some? inner)
             (into (set (map (juxt :name :params :results) (:specs i)))
                   (apply concat inner)))))))))

(defn- implements-edges [project types]
  (let [ifaces (keep (fn [i]
                       (let [shapes (interface-shapes project i)]
                         (when (seq shapes) [i shapes])))
                     types)]
    (for [t types
          :when (not= :interface (:kind t))
          :let [have (method-shapes project t)]
          [i need] ifaces
          :when (every? have need)]
      {:from (:id t) :to (:id i) :kind :implements})))

(defn- inheritance-edges [project types]
  (for [i types
        :when (= :interface (:kind i))
        e (:embeds i)
        :let [j (resolve-embed project (owner project i) i e)]
        :when (= :interface (:kind j))]
    {:from (:id i) :to (:id j) :kind :inheritance}))

(defn- dependency-edges [project pkg]
  (for [t (:types pkg)
        to (type-refs project pkg t)]
    {:from (:id t) :to to :kind :dependency}))

;; ---------------------------------------------------------------- classes

(defn- exported? [name]
  (Character/isUpperCase (.charAt ^String name 0)))

(defn- op-maps [names]
  (->> names (filter exported?) distinct (mapv (fn [n] {:name n :text n}))))

(defn- type-ops [t]
  (op-maps (map :name (if (= :interface (:kind t)) (:specs t) (:methods t)))))

(defn- class-map [m]
  (cond-> (assoc m :lang :go :file (graph/relative-path (:file m)))
    (empty? (:ops m)) (dissoc :ops)))

(defn- package-class [pkg]
  (class-map {:id (:id pkg) :name (:name pkg) :ns (:path pkg) :file (:file pkg)
              :ops (op-maps (mapcat #(map :name (:funcs %)) (:files pkg)))}))

(defn- type-class [t]
  (class-map (cond-> {:id (:id t) :name (:name t) :ns (:ns t) :file (:file t)
                      :ops (type-ops t)}
               (= :interface (:kind t)) (assoc :stereotype :interface))))

(defn scan-go
  "Classes and edges for the Go packages under `root`."
  [root opts]
  (let [prefix (or (:prefix opts) "go")
        project (index-project (packages (canonical root) (or (:ns-prefix opts) prefix) prefix))
        pkgs (:pkgs project)
        types (vec (mapcat :types pkgs))
        edges (->> (concat (mapcat #(import-edges project %) pkgs)
                           (mapcat #(dependency-edges project %) pkgs)
                           (implements-edges project types)
                           (inheritance-edges project types))
                   distinct
                   vec)
        project-ids (set (concat (map :id pkgs) (map :id types)))]
    {:classes (-> (into (mapv package-class pkgs) (map type-class types))
                  (into (->> (map :to edges) (remove project-ids) distinct
                             (map graph/foreign-class))))
     :edges edges}))

(defrecord GoGraph []
  graph/LanguageGraph
  (scan [_ root opts] (scan-go root opts)))

(def impl (->GoGraph))

(graph/register! :go impl)
