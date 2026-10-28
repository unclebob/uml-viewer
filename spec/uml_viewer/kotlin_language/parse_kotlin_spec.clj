(ns uml-viewer.kotlin-language.parse-kotlin-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.kotlin-language.parse-kotlin :as parse]))

(describe "kotlin masking"
  (it "blanks comments and string bodies and keeps the length"
    (let [src "val s = \"{ class X }\" // class Y\n/* a /* nested */ class Z */ val c = '}'\n"
          text (parse/mask src)]
      (should= (count src) (count text))
      (should-not (re-find #"class" text))
      (should-not (re-find #"[{}]" text))
      (should= 2 (count (re-seq #"\n" text)))))

  (it "ends a template at its own brace, past a string inside it"
    (let [text (parse/mask "val s = \"a ${m[\"}\"]} b\"\nclass After\n")]
      (should (re-find #"class After" text))))

  (it "closes a raw string on the last quote of a run"
    (let [text (parse/mask "val s = \"\"\"say \"hi\"\"\"\"\nclass After\n")]
      (should (re-find #"class After" text)))))

(describe "kotlin supertypes"
  (it "marks a constructor call and reads past generics and constructors"
    (should= [{:name "Base" :call? true} {:name "Comparable" :call? false} {:name "I" :call? false}]
             (parse/supertypes "<T : Any>(val x: Map<String, Int>) : Base<List<T>>(x), Comparable<T>, I by d where T : Foo"))
    (should-be-nil (parse/supertypes "(val x: Int)")))

  (it "ends a bodiless header at a semicolon, a closing brace or the line end"
    (let [surface (parse/read-source "Flat.kt"
                                     (str "package demo\n"
                                          "class A(val x: Int = 0); class B\n"
                                          "class Outer { class Inner }\n"
                                          "class C(\n"
                                          "    val y: Int\n"
                                          ")\n"
                                          "    : Base(y)\n"
                                          "fun after() = 1\n"))]
      (should= ["A" "B" "Outer" "Outer.Inner" "C"] (map :path (:types surface)))
      (should= [{:name "Base" :call? true}]
               (parse/supertypes (:header (last (:types surface)))))
      (should= [["after" nil]] (map (juxt :name :owner) (:funs surface)))))

  (it "follows a header across a constructor line to its body"
    (let [surface (parse/read-source "Box.kt"
                                  (str "package demo\n"
                                       "class Box<T : Comparable<T>>\n"
                                       "    @Inject constructor(private val t: T)\n"
                                       "    : Holder<T>(t), Named\n"
                                       "{\n"
                                       "    fun get(): T = t\n"
                                       "    enum class Mode { A { override fun x() = 1 }, B; open fun x() = 0 }\n"
                                       "}\n"
                                       "@Marker\n"
                                       "class Next\n"))
          box (first (:types surface))]
      (should= ["Box" "Box.Mode" "Next"] (map :path (:types surface)))
      (should= [{:name "Holder" :call? true} {:name "Named" :call? false}]
               (parse/supertypes (:header box)))
      (should= [["get" "Box"] ["x" "Box.Mode"]]
               (map (juxt :name :owner) (:funs surface))))))

(describe "kotlin facade name"
  (it "names a file's top-level functions as JaCoCo does"
    (should= "GeometryKt" (parse/facade-name "Geometry.kt" ""))
    (should= "Geo_utilsKt" (parse/facade-name "geo-utils.kt" ""))
    (should= "Geo" (parse/facade-name "Geometry.kt" "@file:JvmName(\"Geo\")\npackage demo\n"))))

(defn- top-level [source]
  (let [surface (parse/read-source "Text.kt" (str "package demo\n" source "\n"))]
    {:funs (mapv (juxt :name :owner) (:funs surface))
     :facade (:facade surface)}))

(describe "kotlin extension receivers"
  (it "reads a plain receiver"
    (should= {:funs [["plain" nil]] :facade "TextKt"}
             (top-level "fun String.plain() = 1")))

  (it "reads a definitely non-null receiver"
    (should= {:funs [["open" nil]] :facade "TextKt"}
             (top-level "fun <T> Box<T & Any>.open() = 1")))

  (it "reads a function-type receiver"
    (should= {:funs [["twice" nil]] :facade "TextKt"}
             (top-level "fun (() -> Unit).twice() = 1")))

  (it "reads a suspend function-type receiver"
    (should= {:funs [["later" nil]] :facade "TextKt"}
             (top-level "fun (suspend () -> Unit).later() = 1")))

  (it "reads a function-type receiver with parameters inside"
    (should= {:funs [["curried" nil]] :facade "TextKt"}
             (top-level "fun <P1, R> ((P1) -> R).curried() = 1")))

  (it "reads a nullable function-type receiver inside a class"
    (should= {:funs [["call" "Box"]] :facade nil}
             (top-level "class Box {\n    fun ((Int) -> Unit)?.call() = 1\n}"))))

(describe "kotlin backticked type names"
  (it "reads a backticked object, a backticked nested class and a companion inside one"
    (let [surface (parse/read-source "-Deprecated.kt"
                                     (str "package okio\n"
                                          "object `-Deprecated` {\n"
                                          "    fun buffer() = 1\n"
                                          "}\n"
                                          "class Plain {\n"
                                          "    class `with space` { fun inside() = 2 }\n"
                                          "}\n"
                                          "internal class `Outer$WithDate$DefaultImpls` {\n"
                                          "    companion object { fun date() = 3 }\n"
                                          "}\n"))]
      (should= ["-Deprecated" "Plain" "Plain.with space" "Outer$WithDate$DefaultImpls"
                "Outer$WithDate$DefaultImpls.Companion"]
               (map :path (:types surface)))
      (should= [["buffer" "-Deprecated"] ["inside" "Plain.with space"]
                ["date" "Outer$WithDate$DefaultImpls.Companion"]]
               (map (juxt :name :owner) (:funs surface))))))
