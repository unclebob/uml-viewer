package main

import (
	"bytes"
	"go/parser"
	"go/token"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func write(t *testing.T, root, rel, content string) {
	t.Helper()
	p := filepath.Join(root, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func parseFuncs(t *testing.T, src string) []Func {
	t.Helper()
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, "x.go", src, 0)
	if err != nil {
		t.Fatal(err)
	}
	return disambiguate(fileFuncs(fset, f, "p/x.go", "m/p/x.go"))
}

func byName(funcs []Func) map[string]Func {
	m := map[string]Func{}
	for _, f := range funcs {
		m[f.Name] = f
	}
	return m
}

func TestComplexityCountsBranchesCasesAndBooleans(t *testing.T) {
	funcs := byName(parseFuncs(t, `package p
func Flat() {}
func Branchy(a, b bool, xs []int, ch chan int) {
	if a && b || !a {
	}
	for i := 0; i < 3; i++ {
	}
	for range xs {
	}
	switch {
	case a:
	case b:
	default:
	}
	select {
	case <-ch:
	default:
	}
	_ = func() {
		if a {
		}
	}
}
`))
	if got := funcs["Flat"].Complexity; got != 1 {
		t.Errorf("Flat = %d, want 1", got)
	}
	// 1 + if + && + || + for + range + 2 cases + 1 comm + closure's if
	if got := funcs["Branchy"].Complexity; got != 10 {
		t.Errorf("Branchy = %d, want 10", got)
	}
}

func TestNamesMethodsAndMarksPrivacy(t *testing.T) {
	funcs := byName(parseFuncs(t, `package p
type Repo struct{}
type list[T any] struct{}
func Open() {}
func helper() {}
func (r *Repo) Get() {}
func (r Repo) find() {}
func (l *list[T]) Len() int { return 0 }
func init() {}
func init() {}
`))
	want := map[string]bool{
		"Open":           false,
		"helper":         true,
		"Repo.Get":       false,
		"Repo.find":      true,
		"list.Len":       true,
		"init (x.go:9)":  true,
		"init (x.go:10)": true,
	}
	for name, private := range want {
		f, ok := funcs[name]
		if !ok {
			t.Errorf("missing %q in %v", name, funcs)
			continue
		}
		if f.Private != private {
			t.Errorf("%s private = %v, want %v", name, f.Private, private)
		}
	}
	if len(funcs) != len(want) {
		t.Errorf("got %d names, want %d: %v", len(funcs), len(want), funcs)
	}
}

func TestCoverageMergesBlocksAcrossTestBinaries(t *testing.T) {
	funcs := parseFuncs(t, `package p

func A(x int) int {
	if x > 0 {
		return 1
	}
	return 0
}

func B() {}
`)
	a, b := byName(funcs)["A"], byName(funcs)["B"]
	profile, err := ParseProfile(strings.NewReader(`mode: atomic
m/p/x.go:3.19,4.11 1 0
m/p/x.go:4.11,6.3 1 0
m/p/x.go:7.2,7.10 1 0
m/p/x.go:3.19,4.11 1 4
m/p/x.go:7.2,7.10 1 2
`))
	if err != nil {
		t.Fatal(err)
	}
	if got := profile.Coverage("m/p/x.go", a); math.Abs(got-200.0/3) > 1e-9 {
		t.Errorf("A coverage = %v, want 66.67", got)
	}
	if got := profile.Coverage("m/p/x.go", b); got != 100 {
		t.Errorf("statement-free B in a profiled file = %v, want 100", got)
	}
	if got := profile.Coverage("m/p/other.go", a); got != 0 {
		t.Errorf("unprofiled file = %v, want 0", got)
	}
}

func TestParseProfileRejectsGarbage(t *testing.T) {
	if _, err := ParseProfile(strings.NewReader("mode: set\nnot a block\n")); err == nil {
		t.Fatal("want an error for a malformed line")
	}
}

func TestCRAP(t *testing.T) {
	cases := []struct {
		cc   int
		cov  float64
		want float64
	}{
		{1, 100, 1},
		{2, 0, 6},
		{4, 50, 6},
	}
	for _, c := range cases {
		if got := CRAP(c.cc, c.cov); math.Abs(got-c.want) > 1e-9 {
			t.Errorf("CRAP(%d, %v) = %v, want %v", c.cc, c.cov, got, c.want)
		}
	}
}

func TestTalliesAttributeMutantsToTheirFunction(t *testing.T) {
	funcs := parseFuncs(t, `package p

var limit = 3 + 4

func A(x int) bool {
	return x > limit
}

func B(x int) bool {
	return x < 0
}
`)
	run, err := ParseMutationRun("./p", strings.NewReader(`{"files":[{"file_name":"x.go","mutations":[
		{"status":"KILLED","line":6,"column":11},
		{"status":"TIMED OUT","line":6,"column":11},
		{"status":"LIVED","line":6,"column":11},
		{"status":"NOT COVERED","line":10,"column":11},
		{"status":"NOT VIABLE","line":10,"column":11},
		{"status":"KILLED","line":3,"column":15}]}]}`))
	if err != nil {
		t.Fatal(err)
	}
	tallies := run.Tallies(funcs)
	if got, want := tallies["A"], (Tally{Killed: 2, Survived: 1}); got != want {
		t.Errorf("A = %+v, want %+v", got, want)
	}
	if got, want := tallies["B"], (Tally{Uncovered: 1}); got != want {
		t.Errorf("B = %+v, want %+v", got, want)
	}
	if len(tallies) != 2 {
		t.Errorf("package-level mutant should belong to no function: %v", tallies)
	}
}

func TestMutationRunCoversItsSubtree(t *testing.T) {
	run := MutationRun{Dir: "internal/platforms"}
	for dir, want := range map[string]bool{
		"internal/platforms":   true,
		"internal/platforms/x": true,
		"internal/platformsx":  false,
		"internal/platform":    false,
	} {
		if got := run.Covers(dir); got != want {
			t.Errorf("Covers(%q) = %v, want %v", dir, got, want)
		}
	}
	if !(MutationRun{Dir: "."}).Covers("anything/at/all") {
		t.Error("a run at the module root covers every package")
	}
}

func TestEDNStringEscapes(t *testing.T) {
	if got, want := ednString("a\"b\\c\n"), `"a\"b\\c\n"`; got != want {
		t.Errorf("got %s, want %s", got, want)
	}
	if got := ednFloat(100); got != "100.0" {
		t.Errorf("ednFloat(100) = %s", got)
	}
}

const shopIR = `;; Generated by clj -M:ir from the policy file. Do not edit.
{:hierarchical true,
 :edge-kinds {[:a :b] :association},
 :classes
 [{:id :internal.team, :ns "shop.internal.team", :name "Team", :lang :go,
   :file "internal/team/team.go", :ops [{:name "Rule", :text "Rule"}]}
  {:id :cmd.shop, :ns "shop.cmd.shop", :name "Shop", :lang :go, :file "cmd/shop/main.go"}
  {:id :github.com.x, :name "github.com.x", :foreign true, :shape :oval}
  {:id :tool, :ns "shop.tool", :lang :clojure, :file "src/tool.clj"}],
 :edges [{:from :cmd.shop, :to :internal.team, :kind :dependency}]}
`

func TestRunWritesSnapshotsKeyedByNamespace(t *testing.T) {
	root := t.TempDir()
	write(t, root, "go.mod", "module example.com/shop\n\ngo 1.22\n")
	write(t, root, ".uml-viewer/shop.edn", shopIR)
	write(t, root, "internal/team/team.go", `package team

func Rule(n int) bool {
	if n > 1 {
		return true
	}
	return false
}

func clamp(n int) int { return n }
`)
	write(t, root, "internal/team/team_test.go", "package team\n\nfunc ignored() {}\n")
	write(t, root, "cmd/shop/main.go", "package main\n\nfunc main() {}\n")
	write(t, root, "testdata/skip.go", "package skip\n\nfunc Skip() {}\n")
	write(t, root, "cover.out", `mode: set
example.com/shop/internal/team/team.go:3.23,4.11 1 1
example.com/shop/internal/team/team.go:4.11,6.3 1 0
example.com/shop/internal/team/team.go:7.2,7.14 1 1
example.com/shop/internal/team/team.go:10.25,10.37 1 1
`)
	write(t, root, "gremlins.json", `{"files":[{"file_name":"team.go","mutations":[
		{"status":"KILLED","line":4,"column":7},
		{"status":"LIVED","line":4,"column":7}]}]}`)
	out := filepath.Join(root, ".uml-viewer", ".metrics")

	var stdout bytes.Buffer
	err := run([]string{
		"-root", root,
		"-ir", filepath.Join(root, ".uml-viewer", "shop.edn"),
		"-cover", filepath.Join(root, "cover.out"),
		"-mutation", "internal/team=" + filepath.Join(root, "gremlins.json"),
		"-out", out,
	}, &stdout, &bytes.Buffer{}, time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}

	crap, err := os.ReadFile(filepath.Join(out, "crap.edn"))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		`{:name "Rule", :namespace "shop.internal.team", :complexity 2, :coverage 66.66666666666667, :crap 2.148148148148148, :tool "go-metrics"}`,
		`{:name "clamp", :namespace "shop.internal.team", :complexity 1, :coverage 100.0, :crap 1.0, :private true, :tool "go-metrics"}`,
		`{:name "main", :namespace "shop.cmd.shop", :complexity 1, :coverage 0.0, :crap 2.0, :private true, :tool "go-metrics"}`,
	} {
		if !strings.Contains(string(crap), want) {
			t.Errorf("crap.edn lacks %s\n%s", want, crap)
		}
	}
	for _, absent := range []string{"ignored", "Skip", "shop.tool"} {
		if strings.Contains(string(crap), absent) {
			t.Errorf("crap.edn should skip %s:\n%s", absent, crap)
		}
	}

	mut, err := os.ReadFile(filepath.Join(out, "mutate", "shop.internal.team.edn"))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		`:namespace "shop.internal.team"`,
		`:tested-at "2026-10-03T12:00:00Z"`,
		`{:id "defn/Rule", :killed 1, :survived 1, :uncovered 0, :sites 2}`,
		`{:id "defn-/clamp", :killed 0, :survived 0, :uncovered 0, :sites 0}`,
	} {
		if !strings.Contains(string(mut), want) {
			t.Errorf("mutate snapshot lacks %s\n%s", want, mut)
		}
	}
	if _, err := os.Stat(filepath.Join(out, "mutate", "shop.cmd.shop.edn")); !os.IsNotExist(err) {
		t.Errorf("a package outside the gremlins run must get no mutation snapshot (err=%v)", err)
	}
}

func TestRunRefusesWithoutInputs(t *testing.T) {
	root := t.TempDir()
	write(t, root, "go.mod", "module m\n")
	if err := run([]string{"-root", root, "-ir", "x.edn"}, &bytes.Buffer{}, &bytes.Buffer{}, time.Now()); err == nil {
		t.Error("want an error when there is nothing to write")
	}
	if err := run([]string{"-root", root, "-cover", "x"}, &bytes.Buffer{}, &bytes.Buffer{}, time.Now()); err == nil {
		t.Error("want an error without -ir")
	}
}

func TestSelectCountsCommCasesNotDefault(t *testing.T) {
	funcs := byName(parseFuncs(t, `package p
func Wait(a, b chan int) {
	select {
	case <-a:
	case <-b:
	default:
	}
}
`))
	if got := funcs["Wait"].Complexity; got != 3 {
		t.Errorf("Wait = %d, want 3", got)
	}
}

func TestContainsIncludesTheFunctionStart(t *testing.T) {
	f := parseFuncs(t, "package p\n\nfunc A() {}\n")[0]
	if !f.Contains(f.Start.Line, f.Start.Column) {
		t.Error("the first column of the function is inside it")
	}
	if f.Contains(f.Start.Line, f.Start.Column-1) {
		t.Error("the column before the function is outside it")
	}
}

func TestParseProfileErrorNamesTheLine(t *testing.T) {
	_, err := ParseProfile(strings.NewReader("mode: set\nm/x.go:1.1,2.2 1 1\nm/x.go:oops\n"))
	if err == nil || !strings.Contains(err.Error(), "line 3") {
		t.Fatalf("want an error naming line 3, got %v", err)
	}
}

func TestSitesCountsEveryJudgedMutant(t *testing.T) {
	if got := (Tally{Killed: 3, Survived: 2, Uncovered: 4}).Sites(); got != 9 {
		t.Errorf("Sites = %d, want 9", got)
	}
}

func TestCrapEntriesAreWorstFirstAndRenderExactly(t *testing.T) {
	funcs := parseFuncs(t, `package p

func Simple() {}

func Branchy(a, b bool) {
	if a {
	}
	if b {
	}
}
`)
	entries := CrapEntries([]Package{{Dir: "p", Namespace: "ns", Funcs: funcs}}, &Profile{byFile: map[string][]blockStat{}})
	got := CrapEDN(nil, entries)
	want := "{:entries\n" +
		" [{:name \"Branchy\", :namespace \"ns\", :complexity 3, :coverage 0.0, :crap 12.0, :tool \"go-metrics\"}\n" +
		"  {:name \"Simple\", :namespace \"ns\", :complexity 1, :coverage 0.0, :crap 2.0, :tool \"go-metrics\"}]}\n"
	if got != want {
		t.Errorf("got\n%s\nwant\n%s", got, want)
	}
}

func TestMutateEDNRendersEveryForm(t *testing.T) {
	pkg := Package{Dir: "p", Namespace: "ns", Funcs: []Func{{Name: "A"}, {Name: "b", Private: true}}}
	got := MutateEDN(pkg, map[string]Tally{"A": {Killed: 1}}, time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC))
	want := "{:tool \"gremlins\"\n :namespace \"ns\"\n :source \"p\"\n :tested-at \"2026-01-02T03:04:05Z\"\n :forms\n" +
		" [{:id \"defn/A\", :killed 1, :survived 0, :uncovered 0, :sites 1}\n" +
		"  {:id \"defn-/b\", :killed 0, :survived 0, :uncovered 0, :sites 0}]}\n"
	if got != want {
		t.Errorf("got\n%s\nwant\n%s", got, want)
	}
}

func TestRunWritesASnapshotForEveryPackageInTheRun(t *testing.T) {
	root := t.TempDir()
	write(t, root, "go.mod", "module m\n")
	write(t, root, "a/a.go", "package a\n\nfunc A() {}\n")
	write(t, root, "b/b.go", "package b\n\nfunc B() {}\n")
	write(t, root, "r.json", `{"files":[]}`)
	ir := writeIR(t, root, `{:classes [{:ns "m.a" :lang :go :file "a/a.go"} {:ns "m.b" :lang :go :file "b/b.go"}]}`)
	out := filepath.Join(root, "out")
	if err := run([]string{"-root", root, "-ir", ir, "-mutation", ".=" + filepath.Join(root, "r.json"), "-out", out},
		&bytes.Buffer{}, &bytes.Buffer{}, time.Now()); err != nil {
		t.Fatal(err)
	}
	for _, f := range []string{"m.a.edn", "m.b.edn"} {
		if _, err := os.Stat(filepath.Join(out, "mutate", f)); err != nil {
			t.Errorf("missing %s: %v", f, err)
		}
	}
}

func TestMutationFlagNeedsDirAndFile(t *testing.T) {
	var m mutationFlags
	if err := m.Set("report.json"); err == nil {
		t.Error("want an error without DIR=")
	}
	if err := m.Set("internal/x=report.json"); err != nil || len(m) != 1 {
		t.Errorf("Set(DIR=FILE) = %v, flags %v", err, m)
	}
}

func TestCrapEntriesKeepSourceOrderOnTies(t *testing.T) {
	funcs := parseFuncs(t, "package p\n\nfunc First() {}\n\nfunc Second() {}\n\nfunc Third() {}\n")
	entries := CrapEntries([]Package{{Namespace: "ns", Funcs: funcs}}, &Profile{byFile: map[string][]blockStat{}})
	var names []string
	for _, e := range entries {
		names = append(names, e.Name)
	}
	if got := strings.Join(names, ","); got != "First,Second,Third" {
		t.Errorf("tied entries reordered: %s", got)
	}
}

func writeIR(t *testing.T, root, content string) string {
	t.Helper()
	write(t, root, "ir.edn", content)
	return filepath.Join(root, "ir.edn")
}

func TestCrapRewriteKeepsOtherToolsRows(t *testing.T) {
	root := t.TempDir()
	write(t, root, "go.mod", "module m\n")
	write(t, root, "a/a.go", "package a\n\nfunc A() {}\n")
	ir := writeIR(t, root, `{:classes [{:ns "m.a" :lang :go :file "a/a.go"}]}`)
	write(t, root, "cover.out", "mode: set\nm/a/a.go:3.13,3.14 0 0\n")
	write(t, root, ".metrics/crap.edn", `{:entries [{:name "render", :namespace "app.view", :complexity 3, :coverage 50.0, :crap 4.125}
 {:name "Stale", :namespace "m.gone", :complexity 1, :coverage 0.0, :crap 2.0, :tool "go-metrics"}]}`)
	var stdout bytes.Buffer
	if err := run([]string{"-root", root, "-ir", ir, "-cover", filepath.Join(root, "cover.out")},
		&stdout, &bytes.Buffer{}, time.Now()); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(stdout.String(), "(1 Go functions, 1 other entries kept)") {
		t.Errorf("summary = %q", stdout.String())
	}
	got, err := os.ReadFile(filepath.Join(root, ".metrics", "crap.edn"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(got), `{:name "render", :namespace "app.view", :complexity 3, :coverage 50.0, :crap 4.125}`) {
		t.Errorf("another tool's row was lost:\n%s", got)
	}
	if strings.Contains(string(got), "Stale") {
		t.Errorf("an earlier go-metrics row survived:\n%s", got)
	}
	if !strings.Contains(string(got), `:name "A", :namespace "m.a"`) {
		t.Errorf("new row missing:\n%s", got)
	}
}

func TestMutationRunMustNameWhereGremlinsRan(t *testing.T) {
	root := t.TempDir()
	write(t, root, "go.mod", "module m\n")
	write(t, root, "internal/team/team.go", "package team\n\nfunc A() {}\n")
	ir := writeIR(t, root, `{:classes [{:ns "m.internal.team" :lang :go :file "internal/team/team.go"}]}`)
	write(t, root, "from-root.json", `{"files":[{"file_name":"internal/team/team.go","mutations":[]}]}`)
	report := filepath.Join(root, "from-root.json")

	err := run([]string{"-root", root, "-ir", ir, "-mutation", "internal/team=" + report}, &bytes.Buffer{}, &bytes.Buffer{}, time.Now())
	if err == nil || !strings.Contains(err.Error(), "pass the directory gremlins ran in") {
		t.Errorf("a report run elsewhere must be refused, got %v", err)
	}
	err = run([]string{"-root", root, "-ir", ir, "-mutation", "nowhere=" + report}, &bytes.Buffer{}, &bytes.Buffer{}, time.Now())
	if err == nil || !strings.Contains(err.Error(), "no Go package") {
		t.Errorf("a directory holding no package must be refused, got %v", err)
	}
	if err := run([]string{"-root", root, "-ir", ir, "-mutation", ".=" + report}, &bytes.Buffer{}, &bytes.Buffer{}, time.Now()); err != nil {
		t.Errorf("the directory gremlins ran in is accepted: %v", err)
	}
}

func TestReadPackageSkipsFilesThatDoNotBuildHereAndWarnsOnBadOnes(t *testing.T) {
	root := t.TempDir()
	other := "plan9"
	if runtime.GOOS == other {
		other = "windows"
	}
	write(t, root, "go.mod", "module m\n")
	write(t, root, "p/p.go", "package p\n\nfunc Here() {}\n")
	write(t, root, "p/p_"+other+".go", "package p\n\nfunc Elsewhere() {}\n")
	write(t, root, "p/gen.go", "//go:build ignore\n\npackage main\n\nfunc Gen() {}\n")
	write(t, root, "p/bad.go", "package p\n\nfunc Broken( {\n")
	var warn bytes.Buffer
	pkg, err := readPackage(root, "p", "m.p", &warn)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, f := range pkg.Funcs {
		names = append(names, f.Name)
	}
	if got := strings.Join(names, ","); got != "Here" {
		t.Errorf("funcs = %s, want Here", got)
	}
	if !strings.Contains(warn.String(), "skipping p/bad.go") {
		t.Errorf("want a warning for the unparsable file, got %q", warn.String())
	}
}

func TestRepeatedInitsInOneFileGetTheirLines(t *testing.T) {
	funcs := byName(parseFuncs(t, "package p\n\nfunc init() {}\n\nfunc init() {}\n"))
	for _, name := range []string{"init (x.go:3)", "init (x.go:5)"} {
		if _, ok := funcs[name]; !ok {
			t.Errorf("missing %q in %v", name, funcs)
		}
	}
}

func TestModuleBelowTheRootMapsToItsCoverprofilePaths(t *testing.T) {
	root := t.TempDir()
	write(t, root, "backend/go.mod", "module example.com/b\n")
	write(t, root, "backend/internal/x/x.go", "package x\n\nfunc X() int {\n\treturn 1\n}\n")
	pkgs, err := PackagesFromIR(root, writeIR(t, root, `{:classes [{:ns "b.x" :lang :go :file "backend/internal/x/x.go"}]}`), &bytes.Buffer{})
	if err != nil {
		t.Fatal(err)
	}
	if got := pkgs[0].Funcs[0].ImportFile; got != "example.com/b/internal/x/x.go" {
		t.Errorf("ImportFile = %s", got)
	}
}

func TestIRFilesMustBeUnderTheRoot(t *testing.T) {
	root := t.TempDir()
	_, err := PackagesFromIR(root, writeIR(t, root, `{:classes [{:ns "x" :lang :go :file "../elsewhere/x.go"}]}`), &bytes.Buffer{})
	if err == nil || !strings.Contains(err.Error(), "outside -root") {
		t.Errorf("want an outside-root error, got %v", err)
	}
	_, err = PackagesFromIR(root, writeIR(t, root, `{:packages []}`), &bytes.Buffer{})
	if err == nil || !strings.Contains(err.Error(), "no :classes") {
		t.Errorf("want a no-classes error, got %v", err)
	}
	_, err = PackagesFromIR(root, writeIR(t, root, `{:classes [{:ns "t" :lang :typescript :file "src/t.ts"}]}`), &bytes.Buffer{})
	if err == nil || !strings.Contains(err.Error(), "no Go classes") {
		t.Errorf("want a no-Go-classes error, got %v", err)
	}
}

func TestReadEDNReadsTheViewerSubsetAndWritesItBack(t *testing.T) {
	v, err := ReadEDN(`;; comment
{:a 1, :b -2.5, :c "q\"\né", :d [nil true false sym], :e #{:x}, :f (1 2),
 [:k :v] :association, :g {:h {}}}`)
	if err != nil {
		t.Fatal(err)
	}
	m := v.(Map)
	if m.Get("a") != int64(1) || m.Get("b") != -2.5 || m.Get("c") != "q\"\né" {
		t.Errorf("scalars: %#v", m)
	}
	if d := m.Get("d").(Vector); d[0] != nil || d[1] != true || d[2] != false || d[3] != Symbol("sym") {
		t.Errorf("vector: %#v", d)
	}
	if got, want := WriteEDN(v), `{:a 1, :b -2.5, :c "q\"\né", :d [nil true false sym], :e #{:x}, :f (1 2), [:k :v] :association, :g {:h {}}}`; got != want {
		t.Errorf("round trip\n got %s\nwant %s", got, want)
	}
	if v, err := ReadEDN("[1] ; trailing comment"); err != nil || len(v.(Vector)) != 1 {
		t.Errorf("a comment that ends the input: %v %v", v, err)
	}
	if _, err := ReadEDN("{:a 1}\n{:b"); err == nil || !strings.Contains(err.Error(), "edn line 2") {
		t.Errorf("want an error naming line 2, got %v", err)
	}
	for _, bad := range []string{`{:a 1`, `"open`, `"open\`, `{:a}`, `[1] 2`, `)`, ``, `   `} {
		if _, err := ReadEDN(bad); err == nil {
			t.Errorf("ReadEDN(%q) should fail", bad)
		}
	}
}
