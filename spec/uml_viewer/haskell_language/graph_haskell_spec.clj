(ns uml-viewer.haskell-language.graph-haskell-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.haskell-language.graph-haskell :as haskell]))

(defn- temp-root []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "uml-haskell-" (System/nanoTime))))

(defn- spit-file [dir relative content]
  (let [file (io/file dir relative)]
    (io/make-parents file)
    (spit file content)
    file))

(describe "haskell module surface"
  (it "reads multiline imports, qualified imports, and signatures"
    (let [surface (haskell/read-module
                    (str "module HBS2.Lease.Worker where\n"
                         "import qualified Data.Map as Map\n"
                         "import HBS2.Lease.Policy qualified as Policy\n"
                         "-- import Hidden.Module\n"
                         "refresh, repair :: Int -> Int\n"
                         "refresh value = value\n"))]
      (should= "HBS2.Lease.Worker" (:module surface))
      (should= ["Data.Map" "HBS2.Lease.Policy"] (:imports surface))
      (should= ["refresh" "repair"] (:ops surface)))))

(describe "haskell graph"
  (it "links project modules, records foreign imports, and skips specs"
    (let [dir (temp-root)]
      (spit-file dir "HBS2/Lease/Policy.hs"
                 "module HBS2.Lease.Policy where\nactive :: Bool\nactive = True\n")
      (spit-file dir "HBS2/Lease/Worker.hs"
                 (str "module HBS2.Lease.Worker where\n"
                      "import HBS2.Lease.Policy\n"
                      "import Data.Word (Word64)\n"
                      "refresh :: Word64 -> Bool\n"
                      "refresh _ = active\n"))
      (spit-file dir "test/HBS2/Lease/WorkerSpec.hs"
                 "module HBS2.Lease.WorkerSpec where\n")
      (let [result (graph/scan (graph/lookup :haskell) dir {:prefix "HBS2"})
            classes (into {} (map (juxt :id identity) (:classes result)))
            edges (set (map (juxt :from :to :kind) (:edges result)))]
        (should= :haskell (:lang (classes :Lease.Worker)))
        (should= "HBS2.Lease.Worker" (:ns (classes :Lease.Worker)))
        (should= ["refresh"] (map :name (:ops (classes :Lease.Worker))))
        (should (:foreign (classes :Data.Word)))
        (should (contains? edges [:Lease.Worker :Lease.Policy :dependency]))
        (should (contains? edges [:Lease.Worker :Data.Word :dependency]))
        (should-not (contains? classes :Lease.WorkerSpec))))))

(describe "haskell split scans"
  (it "keeps project ids stable when source files are scanned separately"
    (let [dir (temp-root)
          policy (spit-file dir "Policy.hs"
                   "module HBS2.Lease.Policy where\nactive :: Bool\nactive = True\n")
          worker (spit-file dir "Worker.hs"
                   "module HBS2.Lease.Worker where\nimport HBS2.Lease.Policy\n")
          opts {:prefix "HBS2"}
          result (graph/merge-scans
                   [(graph/scan (graph/lookup :haskell) policy opts)
                    (graph/scan (graph/lookup :haskell) worker opts)])
          classes (group-by :id (:classes result))
          edges (set (map (juxt :from :to :kind) (:edges result)))]
      (should= 1 (count (classes :Lease.Policy)))
      (should-not (:foreign (first (classes :Lease.Policy))))
      (should (contains? edges [:Lease.Worker :Lease.Policy :dependency])))))
