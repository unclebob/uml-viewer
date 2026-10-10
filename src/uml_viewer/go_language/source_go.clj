(ns uml-viewer.go-language.source-go
  "Go LanguageSource: find a func, method, or type in the package of :file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.source :as source])
  (:import [java.util.regex Pattern]))

(def ^:private decl-fmts
  ["(?m)^func\\s+%s\\s*[\\[(]"
   "(?m)^type\\s+%s\\b"])

(def ^:private any-method-fmts
  ["(?m)^func\\s*\\([^)]*\\)\\s*%s\\s*[\\[(]"])

(defn- block-type-start
  "Offset of `member-name` declared inside a `type ( ... )` block, or nil."
  [source member-name]
  (let [blocks (re-matcher #"(?m)^type\s*\(" source)
        spec (Pattern/compile (format "(?m)^\\t%s\\b" (Pattern/quote member-name)))]
    (loop []
      (when (.find blocks)
        (let [close (re-matcher #"(?m)^\)" source)
              stop (if (.find close (.end blocks)) (.start close) (count source))
              m (doto (.matcher spec source)
                  (.region (.end blocks) stop))]
          (if (.find m) (.start m) (recur)))))))

(defn- method-start
  "Offset of `Type.Method`: a method whose receiver is `Type` or `*Type`,
  generic or not."
  [source member-name]
  (when-let [[_ type-name method] (re-matches #"([A-Za-z_]\w*)\.([A-Za-z_]\w*)" member-name)]
    (let [m (re-matcher (Pattern/compile
                          (format "(?m)^func\\s*\\([^)]*?\\b%s\\b[^)]*\\)\\s*%s\\s*[\\[(]"
                                  (Pattern/quote type-name) (Pattern/quote method)))
                        source)]
      (when (.find m) (.start m)))))

(defn member-ref
  "Name, plus the file and line go-metrics adds to tell repeated names
  apart: `init (shop.go:12)`."
  [member-name]
  (if-let [[_ base file line] (re-matches #"(.+?) \(([^():]+?)(?::(\d+))?\)" (str member-name))]
    {:name base :file file :line (some-> line parse-long)}
    {:name (str member-name)}))

(defn- line-start
  "Offset where 1-based `line` begins, or nil past the end."
  [source line]
  (let [m (re-matcher #"(?m)^" source)]
    (loop [n 1]
      (when (.find m)
        (if (= n line) (.start m) (recur (inc n)))))))

(defn- member-start
  "A type wins over a method of the same name on some other type, which a
  plain name only reaches when nothing else declares it."
  [source member-name]
  (when (and source (seq (str member-name)))
    (let [{:keys [name line]} (member-ref member-name)]
      (or (some->> line (line-start source))
          (method-start source name)
          (source/pattern-start source name decl-fmts)
          (block-type-start source name)
          (source/pattern-start source name any-method-fmts)))))

(defn member-line
  "1-based line of `member-name` in `source`, or nil."
  [source member-name]
  (some->> (member-start source member-name) (source/line-number source)))

(defn extract-member
  "Declaration line of `member-name`, or nil."
  [source member-name]
  (some->> (member-start source member-name) (source/declaration-line source)))

(defn- package-files
  "`path` first, then the other non-test Go files in its directory."
  [path]
  (let [f (io/file path)
        dir (.getParentFile (.getAbsoluteFile f))
        others (->> (.listFiles dir)
                    (filter #(.isFile ^java.io.File %))
                    (map #(.getName ^java.io.File %))
                    (filter #(and (str/ends-with? % ".go")
                                  (not (str/ends-with? % "_test.go"))))
                    (remove #{(.getName f)})
                    sort)]
    (cons path
          (map #(str/replace (str (io/file (.getParent f) %)) #"\\" "/") others))))

(defrecord GoSource []
  source/LanguageSource
  (locate [_ ident]
    (when-let [path (source/existing-file ident)]
      (if (str/blank? (str (:name ident)))
        path
        (let [{:keys [file]} (member-ref (:name ident))
              files (package-files path)]
          (or (when file (some #(when (= file (.getName (io/file %))) %) files))
              (some #(when (member-start (slurp %) (:name ident)) %) files)
              path)))))
  (extract [_ source ident]
    (extract-member source (:name ident)))
  (start-line [_ source ident]
    (member-line source (:name ident)))
  (title [_ ident]
    (source/file-title ident)))

(def impl (->GoSource))

(source/register! :go impl)
