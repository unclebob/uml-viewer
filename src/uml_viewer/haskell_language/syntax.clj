(ns uml-viewer.haskell-language.syntax
  "Small Haskell lexer for module headers, imports, and top-level signatures."
  (:require [clojure.string :as str]
            [uml-viewer.graph :as graph]))

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
  (re-pattern (str "(?m)^([\\t ]*)(" name-pattern
                   "(?:\\s*,\\s*" name-pattern ")*)\\s*::")))

(def ^:private definition-pattern
  (re-pattern (str "(?m)^([\\t ]*)(" name-pattern
                   ")(?=[\\t ]|=)[^\\n]*=")))

(def ^:private module-header-pattern
  #"(?ms)^\s*module\s+[A-Z][\w.']*\b.*?\bwhere\b")

(defn- module-body-start [text]
  (let [matcher (re-matcher module-header-pattern text)]
    (if (.find matcher) (.end matcher) 0)))

(defn- next-tab-stop [column]
  (+ column (- 8 (mod column 8))))

(defn- column-at [text index]
  (let [line-start (inc (.lastIndexOf ^String text "\n" (dec index)))]
    (reduce (fn [column c]
              (if (= \tab c) (next-tab-stop column) (inc column)))
            0
            (subs text line-start index))))

(defn- first-layout-token [text start]
  (loop [line-start start in-pragma? false]
    (when (< line-start (count text))
      (let [line-end (or (str/index-of text \newline line-start) (count text))
            line (subs text line-start line-end)
            leading (count (re-find #"^[\t ]*" line))
            token (+ line-start leading)
            trimmed (subs line leading)
            pragma-start? (str/starts-with? trimmed "{-#")
            pragma-end? (str/includes? trimmed "#-}")]
        (cond
          in-pragma? (recur (min (count text) (inc line-end)) (not pragma-end?))
          (str/blank? trimmed) (recur (min (count text) (inc line-end)) false)
          pragma-start? (recur (min (count text) (inc line-end)) (not pragma-end?))
          (str/starts-with? trimmed "#")
          (recur (min (count text) (inc line-end)) false)
          :else token)))))

(defn- layout-column [text]
  (some->> (first-layout-token text (module-body-start text))
           (column-at text)))

(defn- signature-groups [text]
  (let [matcher (re-matcher signature-pattern text)]
    (loop [groups []]
      (if (.find matcher)
        (recur (conj groups {:column (column-at text (.start matcher 2))
                             :names (.group matcher 2)
                             :start (.start matcher)}))
        groups))))

(defn- signature-entries-in [text]
  (let [column (layout-column text)]
    (->> (signature-groups text)
         (filter #(= column (:column %)))
         (mapcat (fn [{:keys [names start]}]
                   (map (fn [name] {:name (str/trim name) :start start})
                        (str/split names #","))))
         distinct
         vec)))

(defn signature-start
  "Index of a top-level signature that declares `member-name`, or nil."
  [source member-name]
  (some (fn [entry]
          (when (= member-name (:name entry)) (:start entry)))
        (signature-entries-in (visible-source source))))

(defn definition-start
  "Index of a top-level definition for `member-name`, or nil."
  [source member-name]
  (let [text (visible-source source)
        column (layout-column text)
        matcher (re-matcher definition-pattern text)]
    (loop []
      (when (.find matcher)
        (if (and (= column (column-at text (.start matcher 2)))
                 (= member-name (.group matcher 2)))
          (.start matcher)
          (recur))))))

(defn read-module
  "Read the module name, import identities, and top-level signatures."
  [source]
  (let [text (visible-source source)
        module (second (re-find #"(?m)^\s*module\s+([A-Z][\w.']*)\b" text))
        imports (->> (re-seq
                       #"(?m)^\s*import\s+(?:\{-#\s*SOURCE\s*#-\}\s*)?(?:safe\s+)?(?:qualified\s+)?(?:\"([^\"]+)\"\s+)?([A-Z][\w.']*)\b"
                       text)
                     (map (fn [[_ package module]]
                            (cond-> {:module module}
                              package (assoc :package package))))
                     distinct
                     vec)
        ops (->> (signature-entries-in text)
                 (map :name)
                 distinct
                 vec)]
    {:module module :imports imports :ops ops}))
