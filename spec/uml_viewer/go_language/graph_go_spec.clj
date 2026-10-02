(ns uml-viewer.go-language.graph-go-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.go-language.graph-go :as go]
            [uml-viewer.graph :as graph])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(def ^:private temps (atom []))

(defn- temp-root []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "uml-go-" (System/nanoTime)))]
    (swap! temps conj dir)
    dir))

(defn- delete-tree
  "Removes `f` without following symlinks, so a link cycle cannot loop."
  [^java.io.File f]
  (when (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f))))
    (run! delete-tree (.listFiles f)))
  (io/delete-file f true))

(defn- delete-temps! []
  (run! delete-tree @temps)
  (reset! temps []))

(defn- link! [dir rel target]
  (Files/createSymbolicLink (.toPath (io/file dir rel)) (.toPath (io/file target))
                            (make-array FileAttribute 0)))

(describe "go file surface"
  (after (delete-temps!))

  (it "reads the package, imports, exported decls, and assertions"
    (let [surface (go/read-file
                    (str "// Package shop sells things.\n"
                         "package shop\n"
                         "\n"
                         "import (\n"
                         "\t\"context\"\n"
                         "\tm \"example.com/shop/model\"\n"
                         "\t_ \"embed\"\n"
                         "\t// \"example.com/nope\"\n"
                         ")\n"
                         "\n"
                         "import . \"strings\"\n"
                         "\n"
                         "/*\n"
                         "func Commented() {}\n"
                         "*/\n"
                         "const q = `\n"
                         "func InRaw() {}\n"
                         "type InRaw struct{}\n"
                         "`\n"
                         "\n"
                         "var _ m.Store = (*Repo)(nil)\n"
                         "\n"
                         "func Open(ctx context.Context) {}\n"
                         "func hidden() {}\n"
                         "func (r *Repo) Get() {}\n"
                         "func Map[T any](xs []T) {}\n"
                         "\n"
                         "type Repo struct {\n"
                         "\tName string\n"
                         "}\n"
                         "\n"
                         "type (\n"
                         "\tReader interface {\n"
                         "\t\tRead() error\n"
                         "\t}\n"
                         "\tsecret int\n"
                         "\tID = string\n"
                         ")\n"))]
      (should= "shop" (:package surface))
      (should= [{:path "context"}
                {:path "example.com/shop/model" :alias "m"}
                {:path "embed" :alias "_"}
                {:path "strings" :alias "."}]
               (:imports surface))
      (should= [{:name "Open" :kind :func}
                {:name "Map" :kind :func}
                {:name "Repo" :kind :type}
                {:name "Reader" :kind :interface}
                {:name "ID" :kind :type}]
               (:decls surface))
      (should= [{:qualifier "m" :name "Store"}] (:asserts surface))))

  (it "keeps a quote inside a rune or raw string from hiding later decls"
    (let [surface (go/read-file
                    (str "package p\n"
                         "var r = '\"'\n"
                         "var s = `don't`\n"
                         "func After() {}\n"))]
      (should= ["After"] (map :name (:decls surface)))))

  (it "tells interfaces from types that only mention interface"
    (let [kinds (into {} (map (juxt :name :kind))
                      (:decls (go/read-file
                                (str "package p\n"
                                     "type Args []interface{}\n"
                                     "type Fixed [4]interface{}\n"
                                     "type M[K comparable, V any] map[K]interface{}\n"
                                     "type C[T any] interface{ Get() T }\n"
                                     "type N[T interface{ ~int }] interface{}\n"
                                     "type L[S ~[]E, E any] []E\n"
                                     "type I[S ~[]E, E any] interface{ Len() int }\n"
                                     "type (\n"
                                     "\tList []interface{}\n"
                                     "\tPort interface{}\n"
                                     ")\n"))))]
      (should= {"Args" :type "Fixed" :type "M" :type "C" :interface "N" :interface
                "L" :type "I" :interface
                "List" :type "Port" :interface}
               kinds)))

  (it "takes assertions from var declarations, not const blocks"
    (should= #{{:qualifier "port" :name "Store"} {:qualifier "api" :name "Thing"}}
             (set (:asserts (go/read-file
                              (str "package p\n"
                                   "const (\n"
                                   "\t_ model.Status = iota\n"
                                   "\tActive\n"
                                   ")\n"
                                   "var (\n"
                                   "\t_ port.Store = (*Repo)(nil)\n"
                                   ")\n"
                                   "var _ api.Thing = (*Repo)(nil)\n"))))))

  (it "reads the module path from go.mod"
    (should= "example.com/shop" (go/module-path "module example.com/shop\n\ngo 1.22\n"))
    (should= "example.com/q" (go/module-path "module \"example.com/q\"\n"))
    (should-be-nil (go/module-path "go 1.22\n"))))

(describe "go graph"
  (after (delete-temps!))

  (it "scans packages, skips tests and other modules, and marks assertions"
    (let [dir (temp-root)]
      (spit-file dir "go.mod" "module example.com/shop\n\ngo 1.22\n")
      (spit-file dir "model/model.go"
                 (str "package model\n"
                      "type Item struct{}\n"
                      "func NewItem() Item { return Item{} }\n"))
      (spit-file dir "model/doc.go" "// Package model holds items.\npackage model\n")
      (spit-file dir "port/port.go"
                 (str "package port\n"
                      "type Store interface { Get() }\n"))
      (spit-file dir "contract/types.go"
                 (str "package api\n"
                      "type Thing interface { Do() }\n"
                      "func Helper() {}\n"))
      (spit-file dir "db/db.go"
                 (str "package db\n"
                      "import (\n"
                      "\t\"context\"\n"
                      "\t\"example.com/shop/contract\"\n"
                      "\t\"example.com/shop/model\"\n"
                      "\t\"example.com/shop/port\"\n"
                      "\t\"github.com/jackc/pgx/v5\"\n"
                      ")\n"
                      "var (\n"
                      "\t_ port.Store = (*Repo)(nil)\n"
                      "\t_ api.Thing = (*Repo)(nil)\n"
                      ")\n"
                      "type Repo struct{}\n"
                      "func (r *Repo) Get() {}\n"
                      "func (r *Repo) Do() {}\n"
                      "func Open(ctx context.Context, c *pgx.Conn) model.Item { return model.Item{} }\n"))
      (spit-file dir "db/db_test.go"
                 "package db\nimport \"example.com/shop/cmd/shop\"\n")
      (spit-file dir "cmd/shop/main.go"
                 (str "package main\n"
                      "import (\n"
                      "\t\"example.com/shop/db\"\n"
                      "\tm \"example.com/shop/model\"\n"
                      ")\n"
                      "func main() { db.Open(nil, nil); _ = m.Item{} }\n"))
      (spit-file dir "docs/README.md" "not go\n")
      (spit-file dir "testdata/fixture.go" "package fixture\n")
      (spit-file dir "vendor/x/x.go" "package x\n")
      (spit-file dir ".hidden/h.go" "package h\n")
      (spit-file dir "_scratch/s.go" "package s\n")
      (spit-file dir "tools/go.mod" "module example.com/shop/tools\n")
      (spit-file dir "tools/tool.go" "package tools\n")
      (let [g (graph/scan (graph/lookup :go) dir {:prefix "shop"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            project (set (remove #(get-in by-id [% :foreign]) (keys by-id)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should= #{:model :port :contract :db :cmd.shop} project)
        (should= "shop.cmd.shop" (:ns (by-id :cmd.shop)))
        (should= "Shop" (:name (by-id :cmd.shop)))
        (should= :go (:lang (by-id :db)))
        (should (str/ends-with? (:file (by-id :db)) "db/db.go"))
        (should (str/ends-with? (:file (by-id :model)) "model/model.go"))
        (should (str/ends-with? (:file (by-id :contract)) "contract/types.go"))
        (should= ["Item" "NewItem"] (map :name (:ops (by-id :model))))
        (should= ["Repo" "Open"] (map :name (:ops (by-id :db))))
        (should-not (contains? (by-id :cmd.shop) :ops))
        (should= :interface (:stereotype (by-id :port)))
        (should-be-nil (:stereotype (by-id :contract)))
        (should-be-nil (:stereotype (by-id :db)))
        (should (:foreign (by-id :github.com.jackc.pgx.v5)))
        (should (:foreign (by-id :context)))
        (should (contains? edges [:db :model :dependency]))
        (should (contains? edges [:db :port :dependency]))
        (should (contains? edges [:db :port :implements]))
        (should (contains? edges [:db :contract :implements]))
        (should (contains? edges [:db :github.com.jackc.pgx.v5 :dependency]))
        (should (contains? edges [:db :context :dependency]))
        (should (contains? edges [:cmd.shop :db :dependency]))
        (should (contains? edges [:cmd.shop :model :dependency]))
        (should-not (contains? edges [:db :cmd.shop :dependency]))
        (should-not (contains? edges [:db :model :implements]))
        (let [doc (ir-generator/document
                    (graph/lookup :go)
                    {:title "Shop"
                     :prefix "shop"
                     :lang :go
                     :src (str dir)
                     :hierarchical true
                     :foreign [:github.com.jackc.pgx]})
              ids (set (map :id (:classes doc)))
              doc-edges (set (map (juxt :from :to :kind) (:edges doc)))]
          (should= :go (:lang (some #(when (= :db (:id %)) %) (:classes doc))))
          (should (contains? ids :github.com.jackc.pgx))
          (should-not (contains? ids :context))
          (should (contains? doc-edges [:db :port :implements]))
          (should (contains? doc-edges [:db :github.com.jackc.pgx :dependency]))
          (should-not (contains? doc-edges [:db :port :dependency]))))
      (let [nested (graph/scan (graph/lookup :go) (io/file dir "cmd")
                               {:prefix "shop"})
            by-id (into {} (map (juxt :id identity) (:classes nested)))
            edges (set (map (juxt :from :to :kind) (:edges nested)))]
        (should= #{:shop} (set (remove #(get-in by-id [% :foreign]) (keys by-id))))
        (should= "shop.shop" (:ns (by-id :shop)))
        (should (:foreign (by-id :example.com.shop.db)))
        (should (contains? edges [:shop :example.com.shop.db :dependency])))))

  (it "does not draw a standard library import as the project package of that name"
    (let [dir (temp-root)]
      (spit-file dir "go.mod" "module example.com/shop\n")
      (spit-file dir "log/log.go" "package log\nfunc Info() {}\n")
      (spit-file dir "app/app.go" "package app\nimport \"log\"\nfunc A() { log.Println() }\n")
      (spit-file dir "web/web.go" "package web\nimport \"example.com/shop/log\"\nfunc W() { log.Info() }\n")
      (let [g (graph/scan (graph/lookup :go) dir {:prefix "shop"})
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should-not (contains? edges [:app :log :dependency]))
        (should (contains? edges [:web :log :dependency]))
        (should= 1 (count (filter #(= :log (:id %)) (:classes g)))))))

  (it "does not follow symlinked directories"
    (let [dir (temp-root)]
      (spit-file dir "go.mod" "module example.com/shop\n")
      (spit-file dir "real/real.go" "package real\n")
      (link! dir "linked" (io/file dir "real"))
      (link! dir "real/loop" dir)
      (let [g (graph/scan (graph/lookup :go) dir {:prefix "shop"})]
        (should= #{:real} (set (map :id (:classes g)))))))

  (it "refuses two directories that reach one id"
    (let [dir (temp-root)]
      (spit-file dir "go.mod" "module example.com/shop\n")
      (spit-file dir "root.go" "package shop\n")
      (spit-file dir "shop/shop.go" "package shop\n")
      (should-throw clojure.lang.ExceptionInfo "duplicate class id: :shop"
        (graph/scan (graph/lookup :go) dir {:prefix "shop"}))))

  (it "treats every import as foreign without a go.mod"
    (let [dir (temp-root)]
      (spit-file dir "a/a.go" "package a\nimport \"example.com/b\"\n")
      (let [g (graph/scan (graph/lookup :go) dir {:prefix "x"})
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should (contains? edges [:a :example.com.b :dependency]))))))
