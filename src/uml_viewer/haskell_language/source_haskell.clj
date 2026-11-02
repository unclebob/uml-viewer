(ns uml-viewer.haskell-language.source-haskell
  "Haskell LanguageSource: open :file and find a top-level declaration."
  (:require [uml-viewer.haskell-language.syntax :as syntax]
            [uml-viewer.source :as source]))

(defn- member-start [text member-name]
  (or (syntax/signature-start text member-name)
      (syntax/definition-start text member-name)))

(defn- extract-member [text member-name]
  (when-let [start (member-start text member-name)]
    (source/declaration-line text start)))

(defrecord HaskellSource []
  source/LanguageSource
  (locate [_ ident]
    (source/existing-file ident))
  (extract [_ text ident]
    (extract-member text (:name ident)))
  (start-line [_ text ident]
    (some->> (member-start text (:name ident))
             (source/line-number text)))
  (title [_ ident]
    (source/file-title ident)))

(def impl (->HaskellSource))

(source/register! :haskell impl)
