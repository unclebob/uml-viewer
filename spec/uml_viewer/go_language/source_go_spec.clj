(ns uml-viewer.go-language.source-go-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.go-language.source-go :as go]
            [uml-viewer.source :as source]))

(def ^:private temps (atom []))

(defn- temp-dir [tag]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "uml-go-" tag "-" (System/nanoTime)))]
    (swap! temps conj dir)
    dir))

(defn- delete-temps! []
  (doseq [dir @temps
          f (reverse (file-seq dir))]
    (io/delete-file f true))
  (reset! temps []))

(describe "go extractor"
  (after (delete-temps!))

  (it "finds funcs, methods, generic funcs, and types"
    (let [src (str "package shop\n"
                   "func Open() {}\n"
                   "func (r *Repo) Get() {}\n"
                   "func Map[T any](xs []T) {}\n"
                   "type Repo struct {\n"
                   "\tReader int\n"
                   "}\n"
                   "type (\n"
                   "\tReader interface{}\n"
                   ")\n")]
      (should= 2 (go/member-line src "Open"))
      (should= 3 (go/member-line src "Get"))
      (should= 4 (go/member-line src "Map"))
      (should= 5 (go/member-line src "Repo"))
      (should= 9 (go/member-line src "Reader"))
      (should= "func Open() {}" (go/extract-member src "Open"))
      (should-be-nil (go/extract-member src "Missing"))))

  (it "finds a method named Type.Method, by its receiver type"
    (let [src (str "package shop\n"
                   "func (c *RepoCache) Get() {}\n"
                   "func (r *Repo) Get() {}\n"
                   "func (l list[T]) Len() int { return 0 }\n"
                   "func init() {}\n")]
      (should= 3 (go/member-line src "Repo.Get"))
      (should= 2 (go/member-line src "RepoCache.Get"))
      (should= 4 (go/member-line src "list.Len"))
      (should= 5 (go/member-line src "init (shop.go)"))
      (should-be-nil (go/member-line src "Repo.Len"))))

  (it "opens a type rather than another type's method of the same name"
    (let [src (str "package shop\n"
                   "func (s *Server) Config() Config { return Config{} }\n"
                   "type Config struct{}\n"
                   "func (s *Server) Run() {}\n")]
      (should= 3 (go/member-line src "Config"))
      (should= 4 (go/member-line src "Run"))))

  (it "uses the file and line go-metrics adds to a repeated name"
    (should= {:name "init" :file "x.go" :line 5} (go/member-ref "init (x.go:5)"))
    (should= {:name "init" :file "x.go" :line nil} (go/member-ref "init (x.go)"))
    (should= {:name "Open"} (go/member-ref "Open"))
    (should= 5 (go/member-line "package p\nfunc init() {}\n\n\nfunc init() {}\n" "init (x.go:5)"))
    (should= 2 (go/member-line "package p\nfunc init() {}\n" "init (x.go:99)")))

  (it "titles a member by its file and name"
    (should= "internal/shop/shop.go/Open"
             (source/title go/impl {:file "internal/shop/shop.go" :name "Open"})))

  (it "does not take a name prefix for the member"
    (should= 3 (go/member-line "package p\nfunc OpenAll() {}\nfunc Open() {}\n" "Open")))

  (it "finds a member in another file of the package"
    (let [dir (temp-dir "src")
          main (io/file dir "shop.go")
          other (io/file dir "repo.go")]
      (io/make-parents main)
      (spit main "package shop\n\nfunc Open() {}\n")
      (spit other "package shop\n\ntype Repo struct{}\n")
      (spit (io/file dir "repo_test.go") "package shop\n\ntype Repo struct{}\n")
      (let [found (source/member-source {:lang :go
                                         :ns "shop"
                                         :file (.getPath main)
                                         :name "Repo"})]
        (should= :go (:lang found))
        (should= (.getPath other) (:file found))
        (should= 3 (:line found))
        (should (re-find #"repo\.go:3$" (:title found))))
      (should= (.getPath main)
               (:file (source/member-source {:lang :go
                                             :file (.getPath main)
                                             :name "Open"})))))

  (it "opens the file a repeated name points at"
    (let [dir (temp-dir "init")
          main (io/file dir "shop.go")
          other (io/file dir "repo.go")]
      (io/make-parents main)
      (spit main "package shop\n\nfunc init() {}\n")
      (spit other "package shop\n\n\nfunc init() {}\n")
      (let [found (source/member-source {:lang :go :file (.getPath main) :name "init (repo.go:4)"})]
        (should= (.getPath other) (:file found))
        (should= 4 (:line found)))))

  (it "opens the package file at the top when no member is named"
    (let [dir (temp-dir "mod")
          file (io/file dir "shop.go")]
      (io/make-parents file)
      (spit file "package shop\n")
      (let [found (source/member-source {:lang :go
                                         :file (.getPath file)
                                         :ns "shop"})]
        (should= (.getPath file) (:file found))
        (should-be-nil (:line found))))))
