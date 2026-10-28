(ns uml-viewer.kotlin-language.source-kotlin
  "Kotlin LanguageSource: open :file and find a fun inside the class body
  that `:ns` names, or at top level for a FileKt class."
  (:require [clojure.string :as str]
            [uml-viewer.graph :as graph]
            [uml-viewer.kotlin-language.parse-kotlin :as parse]
            [uml-viewer.source :as source]))

(defn- type-path
  "Path of the class inside its file: `demo.Outer.Inner` in package `demo`
  is `Outer.Inner`."
  [package ns-name]
  (let [ns-name (str ns-name)]
    (if (and (seq package) (str/starts-with? ns-name (str package ".")))
      (subs ns-name (inc (count package)))
      ns-name)))

(defn- member-start
  "Index of `fun name` in the class that `:ns` names: inside its body, or
  at top level for its FileKt class. A class the file does not declare
  falls back to the first fun of that name."
  [text {:keys [ns file name]}]
  (let [surface (parse/read-source (or (graph/file-base file) "") text)
        path (type-path (:package surface) ns)
        named (filter #(= name (:name %)) (:funs surface))
        owned (cond
                (some #(= path (:path %)) (:types surface)) (filter #(= path (:owner %)) named)
                (= path (:facade surface)) (filter #(nil? (:owner %)) named)
                :else named)]
    (:start (first owned))))

(defn- line-start [text i]
  (inc (or (str/last-index-of text \newline (max 0 (dec i))) -1)))

(defn extract-member
  "Declaration line of the member `ident` names, or nil."
  [text ident]
  (when-let [i (member-start text ident)]
    (str/trim (source/declaration-line text (line-start text i)))))

(defn member-line
  "1-based line of the member `ident` names, or nil."
  [text ident]
  (when-let [i (member-start text ident)]
    (source/line-number text i)))

(def impl
  (reify source/LanguageSource
    (locate [_ ident] (source/existing-file ident))
    (extract [_ text ident] (extract-member text ident))
    (start-line [_ text ident] (member-line text ident))
    (title [_ ident] (source/file-title ident))))

(source/register! :kotlin impl)
