package main

import (
	"encoding/json"
	"fmt"
	"io"
	"path"
	"strings"
)

// gremlinsReport is the part of `gremlins unleash -o` output used here.
// File names are relative to the directory gremlins ran in.
type gremlinsReport struct {
	Files []struct {
		FileName  string `json:"file_name"`
		Mutations []struct {
			Status string `json:"status"`
			Line   int    `json:"line"`
			Column int    `json:"column"`
		} `json:"mutations"`
	} `json:"files"`
}

// Tally is one function's mutation outcome, in clj-mutate's terms.
type Tally struct {
	Killed, Survived, Uncovered int
}

// Sites counts the mutants that compiled and were judged.
func (t Tally) Sites() int { return t.Killed + t.Survived + t.Uncovered }

// MutationRun is one gremlins report and the directory it ran in.
type MutationRun struct {
	Dir    string // slash path relative to the module root
	Report gremlinsReport
}

// ParseMutationRun reads a gremlins JSON report that was produced in dir.
func ParseMutationRun(dir string, r io.Reader) (MutationRun, error) {
	var rep gremlinsReport
	if err := json.NewDecoder(r).Decode(&rep); err != nil {
		return MutationRun{}, fmt.Errorf("gremlins report for %s: %w", dir, err)
	}
	return MutationRun{Dir: cleanDir(dir), Report: rep}, nil
}

func cleanDir(dir string) string {
	d := path.Clean(strings.ReplaceAll(dir, "\\", "/"))
	return strings.TrimPrefix(d, "./")
}

// Covers reports whether a package directory was inside this run: gremlins
// mutates everything under the directory it starts in.
func (m MutationRun) Covers(pkgDir string) bool {
	return m.Dir == "." || pkgDir == m.Dir || strings.HasPrefix(pkgDir, m.Dir+"/")
}

// Check refuses a run that would write misleading snapshots. A DIR that is not
// where gremlins ran joins its file names to paths that hold no Go file, and
// every function would read as having no mutation sites.
func (m MutationRun) Check(pkgs []Package) error {
	known := map[string]bool{}
	covered := false
	for _, pkg := range pkgs {
		if !m.Covers(pkg.Dir) {
			continue
		}
		covered = true
		for _, f := range pkg.Files {
			known[f] = true
		}
	}
	if !covered {
		return fmt.Errorf("-mutation %s: no Go package of the IR is in that directory", m.Dir)
	}
	var unknown []string
	for _, file := range m.Report.Files {
		if rel := path.Join(m.Dir, file.FileName); !known[rel] {
			unknown = append(unknown, rel)
		}
	}
	if len(unknown) > 0 {
		return fmt.Errorf("-mutation %s: report files %s are not in any Go package under it; pass the directory gremlins ran in",
			m.Dir, strings.Join(unknown, ", "))
	}
	return nil
}

// Tallies attributes each judged mutant to the function containing it.
// Timeouts count as killed, as clj-mutate counts them. Mutants that did not
// compile, or were skipped, are not sites. A mutant outside every function
// (a package-level initializer) belongs to no member and is dropped.
func (m MutationRun) Tallies(funcs []Func) map[string]Tally {
	byFile := map[string][]Func{}
	for _, f := range funcs {
		byFile[f.File] = append(byFile[f.File], f)
	}
	out := map[string]Tally{}
	for _, file := range m.Report.Files {
		rel := path.Join(m.Dir, file.FileName)
		for _, mut := range file.Mutations {
			owner, ok := enclosing(byFile[rel], mut.Line, mut.Column)
			if !ok {
				continue
			}
			t := out[owner]
			switch mut.Status {
			case "KILLED", "TIMED OUT":
				t.Killed++
			case "LIVED":
				t.Survived++
			case "NOT COVERED":
				t.Uncovered++
			default:
				continue
			}
			out[owner] = t
		}
	}
	return out
}

func enclosing(funcs []Func, line, col int) (string, bool) {
	for _, f := range funcs {
		if f.Contains(line, col) {
			return f.Name, true
		}
	}
	return "", false
}
