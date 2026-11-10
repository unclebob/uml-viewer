(ns uml-viewer.go-language.graph-go-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.go-language.graph-go :as go]
            [uml-viewer.graph :as graph]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-module []
  (io/file (System/getProperty "java.io.tmpdir") (str "uml-go-" (System/nanoTime))))

(defn- scan [dir opts]
  (let [g (graph/scan (graph/lookup :go) dir opts)]
    {:by-id (into {} (map (juxt :id identity) (:classes g)))
     :edges (set (map (juxt :from :to :kind) (:edges g)))}))

(describe "go file surface"
  (it "reads the package, imports, functions, methods, and types"
    (let [surface (go/read-file
                    (str "// Package shop sells things.\n"
                         "package shop\n\n"
                         "import (\n"
                         "\t\"fmt\"\n"
                         "\tst \"example.com/shop/store\"\n"
                         "\t_ \"embed\"\n"
                         ")\n\n"
                         "import \"os\"\n\n"
                         "type Cart struct {\n"
                         "\tItems []st.Item `json:\"items\"`\n"
                         "\tLedger\n"
                         "}\n\n"
                         "type (\n"
                         "\tTotaler interface {\n"
                         "\t\tTotal(tax float64) (int, error)\n"
                         "\t\tfmt.Stringer\n"
                         "\t}\n"
                         "\tcount int\n"
                         ")\n\n"
                         "func (c *Cart) Total(rate float64) (n int, err error) { return 0, nil }\n"
                         "func (c Cart) label() string { return \"type Fake struct {}\" }\n"
                         "func NewCart() *Cart { return &Cart{} }\n"
                         "func helper() {}\n"))]
      (should= "shop" (:package surface))
      (should= [{:alias nil :path "fmt"}
                {:alias "st" :path "example.com/shop/store"}
                {:alias "_" :path "embed"}
                {:alias nil :path "os"}]
               (:imports surface))
      (should= ["NewCart" "helper"] (map :name (:funcs surface)))
      (should= [["Cart" "Total" 1 2] ["Cart" "label" 0 1]]
               (map (juxt :receiver :name :params :results) (:methods surface)))
      (should= [["Cart" :struct] ["Totaler" :interface] ["count" :other]]
               (map (juxt :name :kind) (:types surface)))
      (let [cart (first (:types surface))
            totaler (second (:types surface))]
        (should= ["Ledger"] (:embeds cart))
        (should= ["fmt.Stringer"] (:embeds totaler))
        (should= [["Total" 1 2]] (map (juxt :name :params :results) (:specs totaler))))))

  (it "keeps generic type parameters apart from array and slice types"
    (let [surface (go/read-file
                    (str "package box\n"
                         "type Stack[T any] struct { items []T }\n"
                         "type Bytes []byte\n"
                         "type Grid [9]int\n"
                         "func (s *Stack[T]) Push(v T) { s.items = append(s.items, v) }\n"
                         "func Map[T, U any](xs []T, f func(T) U) []U { return nil }\n"))]
      (should= ["Stack" "Bytes" "Grid"] (map :name (:types surface)))
      (should= [["Stack" "Push" 1 0]] (map (juxt :receiver :name :params :results) (:methods surface)))
      (should= [["Map" 2 1]] (map (juxt :name :params :results) (:funcs surface)))))

  (it "ignores declarations inside comments, strings, and function bodies"
    (let [surface (go/read-file
                    (str "package p\n"
                         "/*\ntype Hidden struct{}\n*/\n"
                         "var s = `\ntype Raw struct{}\n`\n"
                         "func F() {\n\ttype local struct{}\n}\n"))]
      (should= [] (:types surface))
      (should= ["F"] (map :name (:funcs surface))))))

(describe "go graph"
  (it "makes one class per package and one per type, joined by Go names"
    (let [dir (temp-module)]
      (spit-file dir "go.mod" "module example.com/shop\n\ngo 1.22\n")
      (spit-file dir "store/item.go"
                 (str "package store\n\n"
                      "type Item struct { Price int }\n\n"
                      "type Pricer interface {\n\tPrice() int\n}\n\n"
                      "func (i Item) Cost() int { return i.Price }\n"))
      (spit-file dir "cart/cart.go"
                 (str "package cart\n\n"
                      "import (\n\t\"fmt\"\n\t\"example.com/shop/store\"\n"
                      "\t\"github.com/acme/money\"\n)\n\n"
                      "type Cart struct { items []store.Item }\n\n"
                      "func New() *Cart { fmt.Println(money.Zero); return &Cart{} }\n"))
      (spit-file dir "cart/total.go"
                 (str "package cart\n\n"
                      "func (c *Cart) Total() int { return len(c.items) }\n"
                      "func (c *Cart) reset() {}\n"))
      (spit-file dir "cart/cart_test.go" "package cart\n\ntype TestOnly struct{}\n")
      (spit-file dir "vendor/x/x.go" "package x\n\ntype Vendored struct{}\n")
      (let [{:keys [by-id edges]} (scan dir {:prefix "shop"})]
        (should= #{:store :store.Item :store.Pricer :cart :cart.Cart
                   :fmt :github.com.acme.money}
                 (set (keys by-id)))
        (should= "example.com/shop/cart" (:ns (by-id :cart)))
        (should= "example.com/shop/cart.Cart" (:ns (by-id :cart.Cart)))
        (should= "cart" (:name (by-id :cart)))
        (should= "Cart" (:name (by-id :cart.Cart)))
        (should= :go (:lang (by-id :cart.Cart)))
        (should (str/ends-with? (:file (by-id :cart.Cart)) "cart/cart.go"))
        (should (str/ends-with? (:file (by-id :cart)) "cart/cart.go"))
        (should= ["New"] (map :name (:ops (by-id :cart))))
        (should= ["Total"] (map :name (:ops (by-id :cart.Cart))))
        (should= :interface (:stereotype (by-id :store.Pricer)))
        (should= ["Price"] (map :name (:ops (by-id :store.Pricer))))
        (should (:foreign (by-id :fmt)))
        (should (:foreign (by-id :github.com.acme.money)))
        (should (contains? edges [:cart :store :dependency]))
        (should (contains? edges [:cart :fmt :dependency]))
        (should (contains? edges [:cart :github.com.acme.money :dependency]))
        (should (contains? edges [:cart.Cart :store.Item :dependency]))
        (should-not (some #(= :cart.Cart (first %)) (filter #(= :fmt (second %)) edges))))))

  (it "records a type that has every method of a project interface as implements"
    (let [dir (temp-module)]
      (spit-file dir "go.mod" "module example.com/zoo\n")
      (spit-file dir "animal/animal.go"
                 (str "package animal\n\n"
                      "type Namer interface {\n\tName() string\n}\n\n"
                      "type Animal interface {\n\tNamer\n\tSpeak(loud bool) string\n}\n\n"
                      "type Walker interface {\n\tWalk(steps int)\n}\n\n"
                      "type Number interface {\n\t~int | ~float64\n}\n"))
      (spit-file dir "dog/dog.go"
                 (str "package dog\n\n"
                      "type Base struct{}\n\n"
                      "func (Base) Name() string { return \"dog\" }\n\n"
                      "type Dog struct {\n\tBase\n}\n\n"
                      "func (d *Dog) Speak(loud bool) string { return \"woof\" }\n"
                      "func (d *Dog) Walk(steps int, fast bool) {}\n"))
      (let [{:keys [edges]} (scan dir {:prefix "zoo"})]
        (should (contains? edges [:dog.Dog :animal.Animal :implements]))
        (should (contains? edges [:dog.Dog :animal.Namer :implements]))
        (should (contains? edges [:dog.Base :animal.Namer :implements]))
        (should-not (contains? edges [:dog.Base :animal.Animal :implements]))
        (should-not (contains? edges [:dog.Dog :animal.Walker :implements]))
        (should (contains? edges [:animal.Animal :animal.Namer :inheritance]))
        (should (contains? edges [:dog.Dog :dog.Base :dependency]))
        (should-not (some #(= :animal.Number (second %)) edges)))))

  (it "skips an interface that embeds an interface it cannot see"
    (let [dir (temp-module)]
      (spit-file dir "go.mod" "module example.com/io2\n")
      (spit-file dir "rw.go"
                 (str "package io2\n\nimport \"io\"\n\n"
                      "type ReadNamer interface {\n\tio.Reader\n\tName() string\n}\n\n"
                      "type File struct{}\n\nfunc (File) Name() string { return \"\" }\n"))
      (let [{:keys [by-id edges]} (scan dir {:prefix "io2"})]
        (should= "example.com/io2" (:ns (by-id :io2)))
        (should= "example.com/io2.File" (:ns (by-id :io2.File)))
        (should-not (contains? edges [:io2.File :io2.ReadNamer :implements])))))

  (it "names packages by their package clause when there is no go.mod"
    (let [dir (temp-module)]
      (spit-file dir "Movie.go" "package GoVideoStore\n\ntype Movie struct{ title string }\n")
      (spit-file dir "Rental.go"
                 (str "package GoVideoStore\n\n"
                      "type Rental struct {\n\tmovie Movie\n}\n\n"
                      "func (r Rental) Title() string { movie := r.movie; return movie.title }\n"))
      (let [{:keys [by-id edges]} (scan dir {:prefix "videostore"})]
        (should= #{:videostore :videostore.Movie :videostore.Rental} (set (keys by-id)))
        (should= "GoVideoStore" (:ns (by-id :videostore)))
        (should= "GoVideoStore.Rental" (:ns (by-id :videostore.Rental)))
        (should (contains? edges [:videostore.Rental :videostore.Movie :dependency])))))

  (it "skips build-ignored files, testdata, and hidden directories"
    (let [dir (temp-module)]
      (spit-file dir "go.mod" "module example.com/gen\n")
      (spit-file dir "gen.go" "//go:build ignore\n\npackage main\n\ntype Gen struct{}\n")
      (spit-file dir "lib.go" "package gen\n\ntype Lib struct{}\n")
      (spit-file dir "testdata/t.go" "package t\n\ntype T struct{}\n")
      (spit-file dir ".hidden/h.go" "package h\n\ntype H struct{}\n")
      (spit-file dir "_old/o.go" "package o\n\ntype O struct{}\n")
      (let [{:keys [by-id]} (scan dir {:prefix "gen"})]
        (should= #{:gen :gen.Lib} (set (keys by-id)))
        (should= "gen" (:name (by-id :gen)))))))
