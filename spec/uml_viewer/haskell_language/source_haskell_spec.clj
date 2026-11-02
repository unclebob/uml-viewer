(ns uml-viewer.haskell-language.source-haskell-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.haskell-language.source-haskell]
            [uml-viewer.source :as source]))

(describe "haskell extractor"
  (it "opens the module file at a top-level signature"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-haskell-source-" (System/nanoTime)))
          file (io/file dir "Worker.hs")]
      (io/make-parents file)
      (spit file "module Worker where\nrefresh :: Int -> Int\nrefresh value = value\n")
      (let [found (source/member-source {:lang :haskell
                                         :file (.getPath file)
                                         :name "refresh"})]
        (should= :haskell (:lang found))
        (should= 2 (:line found))
        (should (re-find #"refresh :: Int" (:body found))))))

  (it "opens every operator in an indented grouped signature"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-haskell-source-" (System/nanoTime)))
          file (io/file dir "Ops.hs")
          text (str "module Ops where\n"
                    "  (<+>), (<!>) :: Int -> Int -> Int\n"
                    "  (<+>) x y = x + y\n"
                    "  (<!>) x y = x - y\n")]
      (io/make-parents file)
      (spit file text)
      (let [impl (source/lookup :haskell)
            first-op (source/member-source {:lang :haskell
                                            :file (.getPath file)
                                            :name "(<+>)"})
            second-op (source/member-source {:lang :haskell
                                             :file (.getPath file)
                                             :name "(<!>)"})]
        (should= 2 (:line first-op))
        (should= 2 (:line second-op))
        (should= "  (<+>), (<!>) :: Int -> Int -> Int"
                 (source/extract impl text {:name "(<!>)"})))))

  (it "uses the module layout for definition-only lookup"
    (let [impl (source/lookup :haskell)
          text (str "module Worker where\n"
                    "  outer = local\n"
                    "    where\n"
                    "      refresh value = value - 1\n"
                    "  refresh value = value\n")]
      (should= "  refresh value = value"
               (source/extract impl text {:name "refresh"})))))
