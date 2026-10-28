(ns uml-viewer.kotlin-language.graph-kotlin-spec
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.graph :as graph]
            [uml-viewer.kotlin-language.graph-kotlin :as kt]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-root []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "uml-kt-" (System/nanoTime))))

(defn- by-id [scan]
  (into {} (map (juxt :id identity) (:classes scan))))

(defn- edge-set [scan]
  (set (map (juxt :from :to :kind) (:edges scan))))

(defn- demo-project []
  (let [dir (temp-root)]
    (spit-file dir "src/main/kotlin/demo/Shape.kt"
               (str "package demo\n\n"
                    "import kotlin.math.PI\n"
                    "import java.util.UUID\n\n"
                    "/** class Phantom */\n"
                    "sealed class Shape : Comparable<Shape> {\n"
                    "    abstract fun area(): Double\n"
                    "    private fun secret() = \"class Hidden { }\"\n"
                    "    internal fun shared() = 1\n"
                    "    override fun compareTo(other: Shape): Int = area().compareTo(other.area())\n"
                    "    companion object {\n"
                    "        fun unit(): Shape = Circle(1.0)\n"
                    "    }\n"
                    "    class Inner(val id: UUID)\n"
                    "}\n\n"
                    "class Circle(\n"
                    "    val r: Double\n"
                    ") : Shape(),\n"
                    "    Named {\n"
                    "    override fun area() = PI * r * r\n"
                    "    override fun label() = \"circle ${r}\"\n"
                    "    fun scale(k: Double): Circle {\n"
                    "        fun local() = k\n"
                    "        class Local\n"
                    "        return Circle(r * local())\n"
                    "    }\n"
                    "}\n"))
    (spit-file dir "src/main/kotlin/demo/Named.kt"
               (str "package demo\n\n"
                    "interface Named {\n"
                    "    fun label(): String\n"
                    "}\n\n"
                    "enum class Color { RED, GREEN }\n"))
    (spit-file dir "src/main/kotlin/demo/Geometry.kt"
               (str "package demo\n\n"
                    "fun totalArea(shapes: List<Shape>): Double = shapes.sumOf { it.area() }\n"
                    "private fun helper() = 0\n"
                    "fun <T> List<T>.second(): T = this[1]\n"))
    (spit-file dir "src/main/kotlin/demo/report/Report.kt"
               (str "package demo.report\n\n"
                    "import demo.Circle\n"
                    "import demo.totalArea\n"
                    "import demo.Color.RED\n"
                    "import demo.*\n"
                    "import kotlinx.coroutines.*\n"
                    "import java.time.Clock\n\n"
                    "object Report {\n"
                    "    fun print(c: Circle) = println(totalArea(listOf(c)))\n"
                    "    fun red() = RED\n"
                    "}\n"))
    (spit-file dir "src/test/kotlin/demo/ShapeTest.kt" "package demo\nclass ShapeTest\n")
    (spit-file dir "src/main/kotlin/demo/CircleSpec.kt" "package demo\nclass CircleSpec\n")
    (spit-file dir "build/generated/Gen.kt" "package demo\nclass Gen\n")
    dir))

(describe "kotlin graph"
  (it "draws one class per type and per file with top-level functions"
    (let [scan (graph/scan kt/impl (str (io/file (demo-project) "src/main/kotlin")) {:prefix "demo"})
          classes (by-id scan)]
      (should= #{:Shape :Shape.Companion :Shape.Inner :Circle :CircleSpec :Named :Color :GeometryKt
                 :report.Report :kotlin.math.PI :java.util.UUID :java.time.Clock :kotlinx.coroutines}
               (set (keys classes)))
      (should= "demo.Shape" (:ns (classes :Shape)))
      (should= "demo.Shape.Companion" (:ns (classes :Shape.Companion)))
      (should= "demo.Shape.Inner" (:ns (classes :Shape.Inner)))
      (should= "demo.GeometryKt" (:ns (classes :GeometryKt)))
      (should= "demo.report.Report" (:ns (classes :report.Report)))
      (should= "Companion" (:name (classes :Shape.Companion)))
      (should= :kotlin (:lang (classes :Circle)))
      (should (re-find #"demo/Shape\.kt$" (:file (classes :Circle))))
      (should= true (:foreign (classes :java.util.UUID)))))

  (it "reads stereotypes"
    (let [classes (by-id (graph/scan kt/impl (str (demo-project)) {:prefix "demo"}))]
      (should= :abstract (:stereotype (classes :Shape)))
      (should= :interface (:stereotype (classes :Named)))
      (should= :enumeration (:stereotype (classes :Color)))
      (should-be-nil (:stereotype (classes :Circle)))))

  (it "skips test directories and build output, and keeps a file named like a test"
    (let [ids (set (keys (by-id (graph/scan kt/impl (str (demo-project)) {:prefix "demo"}))))]
      (should-not (ids :ShapeTest))
      (should (ids :CircleSpec))
      (should-not (ids :Gen))))

  (it "skips exactly the test directories crapper skips"
    (let [dir (temp-root)
          put (fn [rel cls] (spit-file dir rel (str "package demo\nclass " cls "\n")))]
      (put "src/jvmTest/kotlin/demo/A.kt" "JvmTestA")
      (put "core/commonTest/src/demo/B.kt" "CommonTestB")
      (put "src/testFixtures/kotlin/demo/C.kt" "FixtureC")
      (put "lib/specs/demo/D.kt" "SpecsD")
      (put "kotlinx-coroutines-test/common/src/TestBuilders.kt" "TestBuilders")
      (put "src/latestApi/A.kt" "LatestApi")
      (put "src/main/kotlin/demo/testing/E.kt" "Testing")
      (put "okio-testing-support/src/commonMain/kotlin/okio/AbstractFileSystemTest.kt"
           "AbstractFileSystemTest")
      (let [ids (set (keys (by-id (graph/scan kt/impl (str dir) {:prefix "demo"}))))]
        (should= #{:TestBuilders :LatestApi :Testing :AbstractFileSystemTest} ids))))

  (it "lists public functions declared directly in each class as ops"
    (let [classes (by-id (graph/scan kt/impl (str (demo-project)) {:prefix "demo"}))
          ops #(mapv :name (:ops (classes %)))]
      (should= ["area" "compareTo"] (ops :Shape))
      (should= ["unit"] (ops :Shape.Companion))
      (should= ["area" "label" "scale"] (ops :Circle))
      (should= ["label"] (ops :Named))
      (should= ["totalArea" "second"] (ops :GeometryKt))
      (should-not (:ops (classes :Shape.Inner)))))

  (it "reads inheritance, implements and dependencies"
    (let [edges (edge-set (graph/scan kt/impl (str (demo-project)) {:prefix "demo"}))]
      (should (edges [:Circle :Shape :inheritance]))
      (should (edges [:Circle :Named :implements]))
      (should-not (edges [:Circle :Shape :dependency]))
      (should (edges [:Shape.Companion :Circle :dependency]))
      (should (edges [:Shape.Inner :java.util.UUID :dependency]))
      (should (edges [:Circle :kotlin.math.PI :dependency]))
      (should (edges [:GeometryKt :Shape :dependency]))
      (should (edges [:report.Report :Circle :dependency]))
      (should (edges [:report.Report :GeometryKt :dependency]))
      (should (edges [:report.Report :Color :dependency]))
      (should-not (some (fn [[from to]] (= from to)) edges))))

  (it "gives an unused import, and a foreign star import, to every top-level class"
    (let [edges (edge-set (graph/scan kt/impl (str (demo-project)) {:prefix "demo"}))]
      (should (edges [:report.Report :java.time.Clock :dependency]))
      (should (edges [:report.Report :kotlinx.coroutines :dependency]))
      (should-not (some (fn [[_ to]] (= :demo to)) edges))))

  (it "names a backticked type as crapper does and keeps its id readable"
    (let [dir (temp-root)]
      (spit-file dir "okio/-Deprecated.kt"
                 (str "package okio\n"
                      "object `-Deprecated` { fun buffer() = 1 }\n"
                      "class Plain {\n"
                      "    class `with space` { fun inside() = 2 }\n"
                      "}\n"
                      "internal class `Outer$WithDate$DefaultImpls` {\n"
                      "    companion object { fun date() = 3 }\n"
                      "}\n"))
      (let [scan (graph/scan kt/impl (str dir) {:prefix "okio"})
            rows (set (map (juxt :id :ns :name #(mapv :name (:ops %))) (:classes scan)))]
        (should (rows [:-Deprecated "okio.-Deprecated" "-Deprecated" ["buffer"]]))
        (should (rows [:Plain.with_space "okio.Plain.with space" "with space" ["inside"]]))
        (should (rows [(keyword "Outer$WithDate$DefaultImpls.Companion")
                       "okio.Outer$WithDate$DefaultImpls.Companion" "Companion" ["date"]]))
        (should= (:classes scan)
                 (:classes (edn/read-string (ir-generator/emit scan)))))))

  (it "drops local classes and functions"
    (let [scan (graph/scan kt/impl (str (demo-project)) {:prefix "demo"})]
      (should-not (some #(re-find #"Local" (name (:id %))) (:classes scan)))
      (should-not (some #{"local"} (mapcat #(map :name (:ops %)) (:classes scan))))))

  (it "registers under :kotlin and collapses foreign names by policy"
    (let [dir (demo-project)
          doc (ir-generator/document (graph/lookup :kotlin)
                                     {:src (str (io/file dir "src/main/kotlin"))
                                      :prefix "demo"
                                      :lang :kotlin
                                      :hierarchical true
                                      :foreign [:java]})
          ids (set (map :id (:classes doc)))]
      (should (ids :java))
      (should-not (ids :kotlin.math.PI))
      (should (ids :Shape.Companion)))))
