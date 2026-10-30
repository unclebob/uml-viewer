(ns uml-viewer.haskell-language.source-haskell
  "Haskell LanguageSource: open :file and find a top-level declaration."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["(?m)^%s\\s*::"
   "(?m)^%s\\b[^\\n]*="])

(def impl (source/file-source pattern-fmts))

(source/register! :haskell impl)
