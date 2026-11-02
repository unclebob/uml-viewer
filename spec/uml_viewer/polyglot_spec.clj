(ns uml-viewer.polyglot-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.graph :as graph]
            [uml-viewer.rust-language.graph-rust]
            [uml-viewer.typescript-language.graph-typescript]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(describe "merge-scans"
  (it "links an invoke to the rust command that owns the name"
    (let [merged (graph/merge-scans
                   [{:classes [{:id :tauriFs :name "TauriFs" :lang :typescript}
                               {:id :at.tauri-apps :name "at.tauri-apps" :foreign true}]
                     :edges [{:from :tauriFs :to :at.tauri-apps :kind :dependency}]
                     :invokes {:tauriFs ["read_text" "allow_book"]}}
                    {:classes [{:id :rust :name "Rust" :lang :rust}]
                     :edges []
                     :commands {:rust ["read_text" "allow_book"]}}])
          edges (set (map (juxt :from :to :kind) (:edges merged)))]
      (should (contains? edges [:tauriFs :rust :dependency]))
      (should= 2 (count (:edges merged)))
      (should-not (some :invokes (:classes merged)))
      (should-not (some :commands (:classes merged)))))

  (it "rejects two project classes with the same id"
    (should-throw
      (graph/merge-scans
        [{:classes [{:id :main :name "Main"}]}
         {:classes [{:id :main :name "Other"}]}])))

  (it "keeps foreign classes that module resolution did not replace"
    (let [foreign {:id :external :name "external" :foreign true}
          merged (graph/merge-scans [{:classes [foreign] :edges []}])]
      (should= [foreign] (:classes merged)))))

(describe "policy sources"
  (it "scans each language and writes one document"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-poly-" (System/nanoTime)))
          policy {:title "Bookwriter"
                  :prefix "bookwriter"
                  :hierarchical true
                  :sources [{:lang :typescript :root (str (io/file dir "src"))}
                            {:lang :rust
                             :root (str (io/file dir "src-tauri/src"))
                             :prefix "bookwriter.rust"}]
                  :foreign [:at.tauri-apps :tauri]}
          out (io/file dir "examples/bookwriter.edn")]
      (spit-file dir "src/tauriFs.ts"
                 (str "import { invoke } from \"@tauri-apps/api/core\";\n"
                      "export function allowBook(): void { invoke(\"allow_book\"); }\n"))
      (spit-file dir "src-tauri/Cargo.toml" "[lib]\nname = \"bookwriter_lib\"\n")
      (spit-file dir "src-tauri/src/lib.rs"
                 (str "use tauri::Manager;\n"
                      "#[tauri::command]\n"
                      "fn allow_book() {}\n"))
      (spit-file dir "src-tauri/src/main.rs" "fn main() { bookwriter_lib::run(); }\n")
      (let [doc (ir-generator/document nil policy)
            ids (set (map :id (:classes doc)))
            edges (set (map (juxt :from :to :kind) (:edges doc)))]
        (should (contains? ids :tauriFs))
        (should (contains? ids :rust))
        (should (contains? ids :rust.main))
        (should (contains? ids :tauri))
        (should (contains? ids :at.tauri-apps))
        (should (contains? edges [:tauriFs :rust :dependency]))
        (should (contains? edges [:rust.main :rust :dependency]))
        (should (contains? edges [:tauriFs :at.tauri-apps :dependency]))
        (io/make-parents out)
        (let [policy-file (spit-file dir "bookwriter.policy.edn" (pr-str policy))
              written (ir-generator/generate nil (.getPath policy-file) (.getPath out))
              round (read-string (slurp written))]
          (should= "Bookwriter" (:title round))
          (should (some #(= :tauriFs (:id %)) (:classes round))))))))

(def ^:private bookwriter
  (let [candidates [(io/file "/Users/unclebob/projects/bookwriter")
                    (io/file ".." "bookwriter")]]
    (some (fn [dir]
            (when (and (.isDirectory dir)
                       (.isDirectory (io/file dir "src"))
                       (.isFile (io/file dir "src-tauri/src/lib.rs")))
              (.getCanonicalFile dir)))
          candidates)))

(describe "bookwriter"
  (it "diagrams the typescript modules and the rust crate together"
    (if-not bookwriter
      (println "bookwriter checkout not found; scan skipped")
      (let [doc (ir-generator/document
                  nil
                  {:title "Bookwriter"
                   :prefix "bookwriter"
                   :hierarchical true
                   :sources [{:lang :typescript :root (str (io/file bookwriter "src"))}
                             {:lang :rust
                              :root (str (io/file bookwriter "src-tauri/src"))
                              :prefix "bookwriter.rust"}]})
            by-id (into {} (map (juxt :id identity) (:classes doc)))
            edges (set (map (juxt :from :to :kind) (:edges doc)))
            ids (set (keys by-id))
            named (fn [suffix]
                    (let [s (name suffix)]
                      (or (ids (keyword s))
                          (some #(when (str/ends-with? (name %) (str "." s)) %) ids))))
            model (named :model)
            book (named :book)
            node-fs (named :nodeFs)
            tauri-fs (named :tauriFs)]
        (should model)
        (should book)
        (should node-fs)
        (should tauri-fs)
        (should (contains? ids :rust))
        (should (contains? ids :rust.main))
        (should-not (some #(str/ends-with? (name %) ".test") ids))
        (should= [] (filter #(and (= model (:from %))
                                  (not (:foreign (by-id (:to %)))))
                            (:edges doc)))
        (should (contains? edges [book model :dependency]))
        (should (contains? edges [node-fs book :implements]))
        (should (contains? edges [tauri-fs book :implements]))
        (should (contains? edges [tauri-fs :rust :dependency]))
        (should (contains? edges [:rust.main :rust :dependency]))
        (should (some #{"read_text" "allow_book" "startup_book_path"}
                      (map :name (:ops (by-id :rust)))))))))
