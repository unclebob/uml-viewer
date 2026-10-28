(ns uml-viewer.kotlin-language.parse-kotlin
  "Kotlin source structure: the package, imports, types and functions,
  read with regular expressions over masked text."
  (:require [clojure.string :as str]
            [uml-viewer.graph :as graph]))

(declare string-end)

(defn- blank [^StringBuilder sb ^String source i end]
  (doseq [k (range i end)]
    (.append sb (if (= \newline (.charAt source k)) \newline \space))))

(defn- block-comment-end
  "Index just after the block comment at `i`. Kotlin block comments nest."
  [^String source i n]
  (loop [j (+ i 2) depth 1]
    (cond
      (zero? depth) j
      (>= (inc j) n) n
      (.startsWith source "/*" (int j)) (recur (+ j 2) (inc depth))
      (.startsWith source "*/" (int j)) (recur (+ j 2) (dec depth))
      :else (recur (inc j) depth))))

(defn- template-end
  "Index just after the `}` that closes the template opened at `i`."
  [^String source i n]
  (loop [j (+ i 2) depth 1]
    (if (>= j n)
      n
      (let [c (.charAt source j)]
        (cond
          (= c \") (recur (string-end source j n) depth)
          (= c \{) (recur (inc j) (inc depth))
          (and (= c \}) (= 1 depth)) (inc j)
          (= c \}) (recur (inc j) (dec depth))
          :else (recur (inc j) depth))))))

(defn- raw-close?
  "A raw string closes on the last `\"\"\"` of a run of quotes."
  [^String source j n]
  (and (.startsWith source "\"\"\"" (int j))
       (not (and (< (+ j 3) n) (= \" (.charAt source (+ j 3)))))))

(defn- raw-string-end [^String source i n]
  (loop [j (+ i 3)]
    (cond
      (>= j n) n
      (.startsWith source "${" (int j)) (recur (template-end source j n))
      (raw-close? source j n) (+ j 3)
      :else (recur (inc j)))))

(defn- plain-string-end [^String source i n]
  (loop [j (inc i)]
    (if (>= j n)
      n
      (let [c (.charAt source j)]
        (cond
          (= c \\) (recur (+ j 2))
          (.startsWith source "${" (int j)) (recur (template-end source j n))
          (= c \") (inc j)
          (= c \newline) j
          :else (recur (inc j)))))))

(defn- string-end
  "Index just after the string literal that opens at `i`."
  [^String source i n]
  (if (.startsWith source "\"\"\"" (int i))
    (raw-string-end source i n)
    (plain-string-end source i n)))

(defn- char-end
  "Index just after the char literal that opens at `i`."
  [^String source i n]
  (let [j (if (and (< (inc i) n) (= \\ (.charAt source (inc i)))) (+ i 3) (+ i 2))]
    (min n (inc (or (str/index-of source \' (min j n)) (dec n))))))

(defn- mask-step [^String source sb i n]
  (let [c (.charAt source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt) (graph/mask-line-comment source sb i n)
      (graph/block-comment? c nxt) (let [end (block-comment-end source i n)]
                                     (blank sb source i end)
                                     end)
      (or (= c \") (= c \')) (let [end (if (= c \") (string-end source i n) (char-end source i n))]
                               (.append ^StringBuilder sb c)
                               (blank sb source (inc i) end)
                               end)
      :else (do (.append ^StringBuilder sb c) (inc i)))))

(defn mask
  "`source` with comments and string bodies blanked. Same length and lines."
  [^String source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (recur (mask-step source sb i n))))))

(defn- brace-pairs
  "Every `[open close]` brace pair. An unclosed brace closes at the end."
  [^String text]
  (let [n (count text)]
    (loop [i 0 stack () acc []]
      (if (>= i n)
        (into acc (map (fn [o] [o n]) stack))
        (case (.charAt text i)
          \{ (recur (inc i) (conj stack i) acc)
          \} (if (seq stack)
               (recur (inc i) (rest stack) (conj acc [(first stack) i]))
               (recur (inc i) stack acc))
          (recur (inc i) stack acc))))))

(defn- innermost-pair
  "The smallest brace pair that encloses `pos`, or nil."
  [pairs pos]
  (->> pairs
       (filter (fn [[o c]] (and (< o pos) (< pos c))))
       (sort-by first >)
       first))

(defn matches
  "Every match of `pattern` in `text`: `:start`, `:end` and `:groups`."
  [pattern text]
  (let [m (re-matcher pattern text)]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc {:start (.start m)
                          :end (.end m)
                          :groups (mapv #(.group m (int %)) (range 1 (inc (.groupCount m))))}))
        acc))))

(defn package-of [text]
  (second (re-find #"(?m)^\s*package\s+([\w.]+)" text)))

(defn- imports-of [text]
  (mapv (fn [{[path star alias] :groups}]
          {:path path :star? (boolean star) :alias alias})
        (matches #"(?m)^\s*import\s+([\w.]+?)(\.\*)?(?:\s+as\s+(\w+))?\s*$" text)))

(defn header-lines-blanked
  "`text` with its package and import lines blanked, so their names are not uses."
  [text]
  (str/replace text #"(?m)^\s*(?:package|import)\s+[^\n]*"
               (fn [s] (apply str (repeat (count s) \space)))))

(def ^:private type-pattern
  (re-pattern
    (str "(?<![\\w.:$`])"
         "((?:(?:public|private|internal|protected|open|final|abstract|sealed|data|"
         "inner|value|annotation|enum|expect|actual|external|companion|fun)\\s+)*)"
         "(class|interface|object)\\b(?:\\s+(`[^`\\n]+`|[A-Za-z_]\\w*))?")))

(defn- type-kind [modifiers keyword]
  (let [mods (set (str/split (str/trim modifiers) #"\s+"))]
    (cond
      (= keyword "interface") :interface
      (mods "enum") :enum
      (mods "companion") :companion
      (= keyword "object") :object
      (or (mods "abstract") (mods "sealed")) :abstract
      :else :class)))

(defn- continues-header?
  "A newline at depth 0 inside a header keeps the header going when the
  line ends with `:` or `,`, or the next line starts with `:`, `,`, `{`,
  `where`, `by`, or a primary `constructor` with its annotations."
  [^String text start j]
  (let [before (str/trimr (subs text start j))
        after (str/triml (subs text j (min (count text) (+ j 80))))]
    (or (str/ends-with? before ":")
        (str/ends-with? before ",")
        (boolean (re-find #"^(?:[:,{]|where\b|by\b|(?:@[\w.]+(?:\([^)\n]*\))?\s+)*(?:(?:public|private|internal|protected)\s+)?constructor\b)"
                          after)))))

(defn- arrow? [^String text j]
  (and (pos? j) (= \- (.charAt text (dec j)))))

(defn- depth-step [^String text j depth]
  (let [c (.charAt text j)]
    (cond
      (#{\( \[ \<} c) (inc depth)
      (and (#{\) \] \>} c) (not (arrow? text j))) (max 0 (dec depth))
      :else depth)))

(defn- header-stop
  "How the header ends at `j`, a depth-0 index, or nil when it goes on."
  [^String text start j]
  (let [c (.charAt text j)]
    (cond
      (= c \{) {:end j :open j}
      (#{\} \; \=} c) {:end j}
      (and (= c \newline) (not (continues-header? text start j))) {:end j})))

(defn- header-end
  "Where the header that starts at `start` ends, from `from` on.
  `:open` is the index of its body brace, when it has one."
  [^String text start from]
  (let [n (count text)]
    (loop [j from depth 0]
      (if (>= j n)
        {:end n}
        (or (when (zero? depth) (header-stop text start j))
            (recur (inc j) (depth-step text j depth)))))))

(defn- split-top
  "Split `s` on `sep` at bracket depth 0."
  [^String s sep]
  (loop [j 0 depth 0 from 0 acc []]
    (if (>= j (count s))
      (conj acc (subs s from))
      (let [c (.charAt s j)]
        (if (and (zero? depth) (= c sep))
          (recur (inc j) depth (inc j) (conj acc (subs s from j)))
          (recur (inc j) (depth-step s j depth) from acc))))))

(defn- supertype-text
  "The supertype list of a header: what follows the first `:` at depth 0,
  up to a `where` clause."
  [header]
  (let [parts (split-top header \:)]
    (when (> (count parts) 1)
      (first (str/split (str/join ":" (rest parts)) #"\bwhere\b")))))

(defn- skip-type-args
  "`s` after the balanced `<...>` it starts with, if any."
  [^String s]
  (if (str/starts-with? s "<")
    (loop [j 0 depth 0]
      (cond
        (>= j (count s)) ""
        (= \< (.charAt s j)) (recur (inc j) (inc depth))
        (and (= \> (.charAt s j)) (= 1 depth)) (subs s (inc j))
        (= \> (.charAt s j)) (recur (inc j) (dec depth))
        :else (recur (inc j) depth)))
    s))

(defn- supertype [entry]
  (when-let [[_ name tail] (re-find #"^\s*(?:@\w+\s+)*([A-Za-z_][\w.]*)\s*(.*)$"
                                    (str/replace entry #"\s+" " "))]
    {:name name
     :call? (str/starts-with? (str/triml (skip-type-args tail)) "(")}))

(defn supertypes
  "Supertypes named in `header`. `:call?` marks a superclass constructor call."
  [header]
  (when-let [s (supertype-text header)]
    (vec (keep supertype (split-top s \,)))))

(defn- type-candidate [text {:keys [start end groups]}]
  (let [[modifiers keyword name] groups
        kind (type-kind modifiers keyword)
        name (or (some-> name (str/replace "`" ""))
                 (when (= kind :companion) "Companion"))]
    (when name
      (let [{hend :end open :open} (header-end text start end)]
        {:name name
         :kind kind
         :start start
         :header (subs text end hend)
         :header-end hend
         :open open}))))

(defn- with-body [pairs t]
  (if-let [open (:open t)]
    (let [[_ close] (first (filter #(= open (first %)) pairs))]
      (assoc t :span [(:start t) (inc close)] :body [open close]))
    (assoc t :span [(:start t) (:header-end t)])))

(defn- place-types
  "Give each type its `:path`. A type inside a function, lambda or other
  block that is not a type body is local, and drops with its children."
  [types pairs]
  (reduce (fn [acc t]
            (let [pair (innermost-pair pairs (:start t))
                  parent (when pair (first (filter #(= pair (:body %)) acc)))]
              (cond
                (nil? pair) (conj acc (assoc t :path (:name t)))
                parent (conj acc (assoc t :path (str (:path parent) "." (:name t))
                                          :parent (:path parent)))
                :else acc)))
          []
          (sort-by :start types)))

(defn- types-of [text pairs]
  (let [candidates (keep #(type-candidate text %) (matches type-pattern text))]
    (place-types (map #(with-body pairs %) candidates) pairs)))

(def ^:private fun-pattern
  (re-pattern
    (str "(?<![\\w$`])fun\\s+(?:<[^(){}=]*?>\\s*)?"
         "(?:(?:\\((?:[^()\\n{}=]|\\([^()\\n{}=]*\\))*\\)\\??|[\\w.?<>,*& ]+?)\\.)?"
         "(`[^`\\n]+`|[A-Za-z_]\\w*)\\s*\\(")))

(defn- statement-prefix
  "Text between the last statement boundary and `start`."
  [^String text start]
  (let [from (loop [j (dec start)]
               (cond
                 (neg? j) 0
                 (#{\newline \; \{ \}} (.charAt text j)) (inc j)
                 :else (recur (dec j))))]
    (subs text from start)))

(defn- public? [text start]
  (not (re-find #"\b(?:private|internal|protected)\b" (statement-prefix text start))))

(defn- funs-of
  "Functions declared directly in a type body (`:owner` is the type path)
  or at top level (`:owner` nil). Functions inside other blocks drop."
  [text pairs types]
  (let [by-body (into {} (keep (fn [t] (when (:body t) [(:body t) (:path t)])) types))]
    (keep (fn [{:keys [start groups]}]
            (let [pair (innermost-pair pairs start)
                  owner (when pair (get by-body pair))]
              (when (or (nil? pair) owner)
                {:name (str/replace (first groups) "`" "")
                 :owner owner
                 :start start
                 :public? (public? text start)})))
          (matches fun-pattern text))))

(defn facade-name
  "JaCoCo class of a file's top-level functions: `Geometry.kt` is `GeometryKt`.
  `@file:JvmName(\"Geo\")` renames it."
  [file-name source]
  (or (second (re-find #"@file\s*:\s*JvmName\s*\(\s*\"([^\"]+)\"" source))
      (let [base (str/replace file-name #"\.kt$" "")
            base (str/replace base #"[^\w]" "_")]
        (str (str/upper-case (subs base 0 (min 1 (count base))))
             (subs base (min 1 (count base)))
             "Kt"))))

(defn read-source
  "Structure of Kotlin `source` in a file named `file-name`: the package,
  imports, types with their paths and spans, and the functions."
  [file-name source]
  (let [text (mask source)
        pairs (brace-pairs text)
        types (types-of text pairs)
        funs (vec (funs-of text pairs types))]
    {:text text
     :package (package-of text)
     :imports (imports-of text)
     :types types
     :funs funs
     :facade (when (some #(nil? (:owner %)) funs)
               (facade-name file-name source))}))
