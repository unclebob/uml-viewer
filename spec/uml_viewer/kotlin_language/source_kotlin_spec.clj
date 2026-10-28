(ns uml-viewer.kotlin-language.source-kotlin-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.kotlin-language.source-kotlin]
            [uml-viewer.source :as source]))

(def ^:private shapes
  (str "package demo\n"
       "\n"
       "class Square(val s: Double) {\n"
       "    fun area() = s * s\n"
       "    companion object {\n"
       "        fun area() = 1.0\n"
       "    }\n"
       "}\n"
       "\n"
       "class Circle(val r: Double) {\n"
       "    override fun area() = r * r\n"
       "}\n"
       "\n"
       "fun area(x: Double) = x\n"))

(defn- shapes-file []
  (let [file (io/file (System/getProperty "java.io.tmpdir")
                      (str "uml-kt-src-" (System/nanoTime))
                      "Shapes.kt")]
    (io/make-parents file)
    (spit file shapes)
    file))

(defn- found [file ns-name member]
  (source/member-source (cond-> {:lang :kotlin :file (.getPath file) :ns ns-name}
                          member (assoc :name member))))

(describe "kotlin extractor"
  (it "finds a fun inside the class body that :ns names"
    (let [file (shapes-file)]
      (should= 4 (:line (found file "demo.Square" "area")))
      (should= 6 (:line (found file "demo.Square.Companion" "area")))
      (should= 11 (:line (found file "demo.Circle" "area")))
      (should= :kotlin (:lang (found file "demo.Circle" "area")))
      (should (re-find #"Shapes\.kt:11$" (:title (found file "demo.Circle" "area"))))))

  (it "finds a top-level fun for the FileKt class"
    (should= 14 (:line (found (shapes-file) "demo.ShapesKt" "area"))))

  (it "returns nil when the class does not declare the member"
    (should-be-nil (found (shapes-file) "demo.Circle" "perimeter"))
    (should-be-nil (found (shapes-file) "demo.Square.Companion" "missing")))

  (it "falls back to the first fun of that name for a class the file does not declare"
    (should= 4 (:line (found (shapes-file) "demo.Other" "area"))))

  (it "opens a class at the top when no member is named"
    (let [file (shapes-file)
          hit (found file "demo.Circle" nil)]
      (should= (.getPath file) (:file hit))
      (should-be-nil (:line hit)))))
