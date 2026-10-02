package main

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

// toolName marks the crap.edn rows this tool owns, so a rerun replaces them
// and leaves other languages' rows alone.
const toolName = "go-metrics"

// CrapEntry is one row of crap.edn, in crap4clj's shape plus :private.
type CrapEntry struct {
	Name       string
	Namespace  string
	Complexity int
	Coverage   float64
	CRAP       float64
	Private    bool
}

// CrapEntries scores every function, worst first, as crap4clj orders them.
func CrapEntries(pkgs []Package, profile *Profile) []CrapEntry {
	var entries []CrapEntry
	for _, pkg := range pkgs {
		for _, f := range pkg.Funcs {
			cov := profile.Coverage(f.ImportFile, f)
			entries = append(entries, CrapEntry{
				Name:       f.Name,
				Namespace:  pkg.Namespace,
				Complexity: f.Complexity,
				Coverage:   cov,
				CRAP:       CRAP(f.Complexity, cov),
				Private:    f.Private,
			})
		}
	}
	sort.SliceStable(entries, func(i, j int) bool { return entries[i].CRAP > entries[j].CRAP })
	return entries
}

// CrapEDN renders the rows other tools left, then this tool's rows, the way
// the viewer's overlay reads them.
func CrapEDN(kept []Map, entries []CrapEntry) string {
	var rows []string
	for _, m := range kept {
		rows = append(rows, WriteEDN(m))
	}
	for _, e := range entries {
		row := fmt.Sprintf("{:name %s, :namespace %s, :complexity %d, :coverage %s, :crap %s",
			ednString(e.Name), ednString(e.Namespace), e.Complexity, ednFloat(e.Coverage), ednFloat(e.CRAP))
		if e.Private {
			row += ", :private true"
		}
		rows = append(rows, row+", :tool "+ednString(toolName)+"}")
	}
	return "{:entries\n [" + strings.Join(rows, "\n  ") + "]}\n"
}

// MutateEDN renders one package's mutation snapshot. Every function is a
// form, so one gremlins never touched reads as "no mutation sites" rather than
// as missing data.
func MutateEDN(pkg Package, tallies map[string]Tally, now time.Time) string {
	var b strings.Builder
	fmt.Fprintf(&b, "{:tool \"gremlins\"\n :namespace %s\n :source %s\n :tested-at %s\n :forms\n [",
		ednString(pkg.Namespace), ednString(pkg.Dir), ednString(now.Format(time.RFC3339)))
	for i, f := range pkg.Funcs {
		if i > 0 {
			b.WriteString("\n  ")
		}
		t := tallies[f.Name]
		fmt.Fprintf(&b, "{:id %s, :killed %d, :survived %d, :uncovered %d, :sites %d}",
			ednString(formID(f)), t.Killed, t.Survived, t.Uncovered, t.Sites())
	}
	b.WriteString("]}\n")
	return b.String()
}

// formID uses clj-mutate's defn/ and defn-/ prefixes, which the overlay reads
// as public and private.
func formID(f Func) string {
	if f.Private {
		return "defn-/" + f.Name
	}
	return "defn/" + f.Name
}

func ednString(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range s {
		switch {
		case r == '"' || r == '\\':
			b.WriteByte('\\')
			b.WriteRune(r)
		case r == '\n':
			b.WriteString(`\n`)
		case r == '\t':
			b.WriteString(`\t`)
		case r < 0x20:
			fmt.Fprintf(&b, `\u%04x`, r)
		default:
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
	return b.String()
}

func ednFloat(x float64) string {
	s := strconv.FormatFloat(x, 'f', -1, 64)
	if !strings.ContainsAny(s, ".eE") {
		s += ".0"
	}
	return s
}
