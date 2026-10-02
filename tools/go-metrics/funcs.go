package main

import (
	"errors"
	"fmt"
	"go/ast"
	"go/build"
	"go/parser"
	"go/token"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// Func is one function or method with a body, as the viewer names it.
type Func struct {
	Name       string
	Private    bool
	File       string // slash path relative to -root
	ImportFile string // the file's name in a coverprofile: module path + path in module
	Start, End token.Position
	Complexity int
}

// Package is one Go package the viewer drew.
type Package struct {
	Dir       string // slash path relative to -root
	Namespace string
	Files     []string // slash paths relative to -root
	Funcs     []Func
}

// PackagesFromIR lists the Go packages in a hierarchical IR written by
// `clj -M:ir`. Each Go class carries the :ns the overlay joins snapshots on,
// and a :file in its package's directory, relative to the directory the IR
// was generated from, which is root. Reading them here keeps one definition
// of which directories are packages: the scanner's.
func PackagesFromIR(root, irPath string, warn io.Writer) ([]Package, error) {
	data, err := os.ReadFile(irPath)
	if err != nil {
		return nil, err
	}
	doc, err := ReadEDN(string(data))
	if err != nil {
		return nil, fmt.Errorf("%s: %w", irPath, err)
	}
	m, _ := doc.(Map)
	classes, ok := m.Get("classes").(Vector)
	if !ok {
		return nil, fmt.Errorf("%s has no :classes; generate a hierarchical IR with :lang :go", irPath)
	}
	var pkgs []Package
	for _, c := range classes {
		class, _ := c.(Map)
		ns, file, ok := goClass(class)
		if !ok {
			continue
		}
		if filepath.IsAbs(file) || strings.HasPrefix(file, "../") {
			return nil, fmt.Errorf("class %s has :file %q outside -root; run clj -M:ir from the project root and pass that directory as -root", ns, file)
		}
		pkg, err := readPackage(root, path.Dir(file), ns, warn)
		if err != nil {
			return nil, err
		}
		pkgs = append(pkgs, pkg)
	}
	if len(pkgs) == 0 {
		return nil, fmt.Errorf("%s has no Go classes", irPath)
	}
	return pkgs, nil
}

func goClass(c Map) (ns, file string, ok bool) {
	if c.Get("lang") != Keyword("go") || c.Get("foreign") == true {
		return "", "", false
	}
	ns, nsOK := c.Get("ns").(string)
	file, fileOK := c.Get("file").(string)
	return ns, file, nsOK && fileOK
}

func goSource(name string) bool {
	return strings.HasSuffix(name, ".go") && !strings.HasSuffix(name, "_test.go")
}

// readPackage parses the package's files that build on this platform. A file
// that its build constraints exclude (foo_windows.go, //go:build ignore) never
// reaches a coverprofile, so scoring it would paint it 0% covered.
func readPackage(root, dir, ns string, warn io.Writer) (Package, error) {
	abs := filepath.Join(root, filepath.FromSlash(dir))
	module, modRoot, err := findModule(abs)
	if err != nil {
		return Package{}, err
	}
	entries, err := os.ReadDir(abs)
	if err != nil {
		return Package{}, err
	}
	pkg := Package{Dir: dir, Namespace: ns}
	fset := token.NewFileSet()
	for _, e := range entries {
		if e.IsDir() || !goSource(e.Name()) {
			continue
		}
		if match, err := build.Default.MatchFile(abs, e.Name()); err != nil || !match {
			continue
		}
		rel := path.Join(dir, e.Name())
		pkg.Files = append(pkg.Files, rel)
		parsed, err := parser.ParseFile(fset, filepath.Join(abs, e.Name()), nil, parser.SkipObjectResolution)
		if err != nil {
			fmt.Fprintf(warn, "go-metrics: skipping %s: %v\n", rel, err)
			continue
		}
		inModule, err := filepath.Rel(modRoot, filepath.Join(abs, e.Name()))
		if err != nil {
			return Package{}, err
		}
		importFile := module + "/" + filepath.ToSlash(inModule)
		pkg.Funcs = append(pkg.Funcs, fileFuncs(fset, parsed, rel, importFile)...)
	}
	sort.Strings(pkg.Files)
	pkg.Funcs = disambiguate(pkg.Funcs)
	return pkg, nil
}

var moduleLine = regexp.MustCompile(`(?m)^module\s+"?([^\s"]+)"?`)

// findModule returns the module path and directory of the go.mod at dir or
// its nearest ancestor.
func findModule(dir string) (string, string, error) {
	for d := dir; ; {
		data, err := os.ReadFile(filepath.Join(d, "go.mod"))
		if err == nil {
			m := moduleLine.FindSubmatch(data)
			if m == nil {
				return "", "", fmt.Errorf("%s declares no module", filepath.Join(d, "go.mod"))
			}
			return string(m[1]), d, nil
		}
		if !errors.Is(err, os.ErrNotExist) {
			return "", "", err
		}
		parent := filepath.Dir(d)
		if parent == d {
			return "", "", fmt.Errorf("no go.mod at or above %s", dir)
		}
		d = parent
	}
}

func fileFuncs(fset *token.FileSet, file *ast.File, rel, importFile string) []Func {
	var funcs []Func
	for _, decl := range file.Decls {
		fd, ok := decl.(*ast.FuncDecl)
		if !ok || fd.Body == nil {
			continue
		}
		name, private := funcName(fd)
		funcs = append(funcs, Func{
			Name:       name,
			Private:    private,
			File:       rel,
			ImportFile: importFile,
			Start:      fset.Position(fd.Pos()),
			End:        fset.Position(fd.End()),
			Complexity: Complexity(fd),
		})
	}
	return funcs
}

// funcName is `Name` for a function and `Type.Name` for a method. A method is
// private when either half is unexported.
func funcName(fd *ast.FuncDecl) (string, bool) {
	private := !ast.IsExported(fd.Name.Name)
	if fd.Recv == nil || len(fd.Recv.List) == 0 {
		return fd.Name.Name, private
	}
	recv := receiverType(fd.Recv.List[0].Type)
	return recv + "." + fd.Name.Name, private || !ast.IsExported(recv)
}

func receiverType(expr ast.Expr) string {
	switch t := expr.(type) {
	case *ast.StarExpr:
		return receiverType(t.X)
	case *ast.IndexExpr:
		return receiverType(t.X)
	case *ast.IndexListExpr:
		return receiverType(t.X)
	case *ast.ParenExpr:
		return receiverType(t.X)
	case *ast.Ident:
		return t.Name
	default:
		return "?"
	}
}

// disambiguate renames repeats, which Go allows for init (several per file
// even), because the viewer keys a class's members by name. The suffix is
// what the Go source extractor reads to open the right one.
func disambiguate(funcs []Func) []Func {
	count := map[string]int{}
	for _, f := range funcs {
		count[f.Name]++
	}
	for i, f := range funcs {
		if count[f.Name] > 1 {
			funcs[i].Name = fmt.Sprintf("%s (%s:%d)", f.Name, path.Base(f.File), f.Start.Line)
		}
	}
	return funcs
}

// Complexity is gocyclo's count: 1, plus one per if, for, range, non-default
// case or select clause, and && or ||. Closures count toward their enclosing
// function.
func Complexity(fd *ast.FuncDecl) int {
	n := 1
	ast.Inspect(fd.Body, func(node ast.Node) bool {
		switch x := node.(type) {
		case *ast.IfStmt, *ast.ForStmt, *ast.RangeStmt:
			n++
		case *ast.CaseClause:
			if x.List != nil {
				n++
			}
		case *ast.CommClause:
			if x.Comm != nil {
				n++
			}
		case *ast.BinaryExpr:
			if x.Op == token.LAND || x.Op == token.LOR {
				n++
			}
		}
		return true
	})
	return n
}

// Contains reports whether line:col falls inside the function.
func (f Func) Contains(line, col int) bool {
	return !before(line, col, f.Start.Line, f.Start.Column) &&
		!before(f.End.Line, f.End.Column, line, col)
}

func before(l1, c1, l2, c2 int) bool {
	return l1 < l2 || (l1 == l2 && c1 < c2)
}
