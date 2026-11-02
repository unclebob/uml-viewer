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
  (it "reads qualified imports and signatures"
    (let [surface (haskell/read-module
                    (str "module HBS2.Lease.Worker where\n"
                         "import qualified Data.Map as Map\n"
                         "import HBS2.Lease.Policy qualified as Policy\n"
                         "-- import Hidden.Module\n"
                         "refresh, repair :: Int -> Int\n"
                         "refresh value = value\n"))]
      (should= "HBS2.Lease.Worker" (:module surface))
      (should= ["Data.Map" "HBS2.Lease.Policy"] (:imports surface))
      (should= ["refresh" "repair"] (:ops surface))))

  (it "reads prepositive qualified package imports"
    (let [surface (haskell/read-module
                    (str "{-# LANGUAGE PackageImports #-}\n"
                         "module Package.Client where\n"
                         "import qualified \"containers\" Data.Map as Map\n"
                         "import \"network\" Network.Socket\n"))]
      (should= ["Data.Map" "Network.Socket"] (:imports surface))))

  (it "ignores nested comments and keeps operator signatures"
    (let [surface (haskell/read-module
                    (str "module Real where\n"
                         "{- outer\n"
                         "   {- nested -}\n"
                         "   import Fake.Module\n"
                         "-}\n"
                         "import Real.Dependency\n"
                         "(-->) :: Int -> Int -> Bool\n"
                         "real :: Int\n"))]
      (should= ["Real.Dependency"] (:imports surface))
      (should= ["(-->)" "real"] (:ops surface))))

  (it "does not treat comment markers in literals or apostrophes as comments"
    (let [surface (haskell/read-module
                    (str "module Literals where\n"
                         "url :: String\n"
                         "url = \"-- import Fake.String {-\"\n"
                         "dash :: Char\n"
                         "dash = '-'\n"
                         "escaped :: Char\n"
                         "escaped = '\\''\n"
                         "prime' :: Int\n"
                         "promoted :: Proxy 'True\n"
                         "-- import Fake.Comment\n"
                         "kept :: Int\n"))]
      (should= [] (:imports surface))
      (should= ["url" "dash" "escaped" "prime'" "promoted" "kept"]
               (:ops surface)))))

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
      (should (contains? edges [:Lease.Worker :Lease.Policy :dependency]))))

  (it "uses source prefixes to distinguish build units and resolve imports"
    (let [dir (temp-root)
          app-root (io/file dir "app")
          tool-root (io/file dir "tool")]
      (spit-file app-root "Main.hs"
                 "module Main where\nimport Shared.Api\nmain :: IO ()\nmain = pure ()\n")
      (spit-file tool-root "Main.hs"
                 "module Main where\nmain :: IO ()\nmain = pure ()\n")
      (spit-file tool-root "Shared/Api.hs"
                 "module Shared.Api where\nserve :: IO ()\nserve = pure ()\n")
      (let [result (graph/merge-scans
                     [(graph/scan (graph/lookup :haskell) app-root
                                  {:prefix "demo" :ns-prefix "demo.app"})
                      (graph/scan (graph/lookup :haskell) tool-root
                                  {:prefix "demo" :ns-prefix "demo.tool"})])
            classes (into {} (map (juxt :id identity) (:classes result)))
            edges (set (map (juxt :from :to :kind) (:edges result)))]
        (should= #{:app.Main :tool.Main :tool.Shared.Api} (set (keys classes)))
        (should= "Main" (:ns (classes :app.Main)))
        (should= "Main" (:ns (classes :tool.Main)))
        (should (contains? edges [:app.Main :tool.Shared.Api :dependency])))))

  (it "rejects duplicate modules inside one source root"
    (let [dir (temp-root)]
      (spit-file dir "one/Main.hs" "module Main where\n")
      (spit-file dir "two/Main.hs" "module Main where\n")
      (try
        (graph/scan (graph/lookup :haskell) dir {:prefix "demo"})
        (should false)
        (catch clojure.lang.ExceptionInfo e
          (should= "Main" (:module (ex-data e)))))))

  (it "uses the implicit Main module when the header is omitted"
    (let [dir (temp-root)]
      (spit-file dir "Executable.hs" "main :: IO ()\nmain = pure ()\n")
      (let [result (graph/scan (graph/lookup :haskell) dir
                               {:prefix "demo" :ns-prefix "demo.app"})
            c (first (remove :foreign (:classes result)))]
        (should= :app.Main (:id c))
        (should= "Main" (:ns c))))))
