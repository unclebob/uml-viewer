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
        (should (re-find #"refresh :: Int" (:body found)))))))
