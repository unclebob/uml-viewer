// Command go-metrics writes uml-viewer's .metrics snapshots for the Go
// packages in a generated IR: crap.edn entries from a coverprofile, and
// mutate/<namespace>.edn from gremlins reports. Namespaces are the :ns the Go
// scanner gave each package, so the viewer's overlay joins them as they are.
package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func main() {
	if err := run(os.Args[1:], os.Stdout, os.Stderr, time.Now()); err != nil {
		fmt.Fprintln(os.Stderr, "go-metrics:", err)
		os.Exit(1)
	}
}

type mutationFlags []string

func (m *mutationFlags) String() string { return strings.Join(*m, ",") }

func (m *mutationFlags) Set(v string) error {
	if !strings.Contains(v, "=") {
		return fmt.Errorf("want DIR=FILE, got %q", v)
	}
	*m = append(*m, v)
	return nil
}

func run(args []string, stdout, stderr io.Writer, now time.Time) error {
	fs := flag.NewFlagSet("go-metrics", flag.ContinueOnError)
	fs.SetOutput(stderr)
	root := fs.String("root", ".", "directory the IR was generated from; IR :file paths are relative to it")
	ir := fs.String("ir", "", "hierarchical IR written by clj -M:ir (required)")
	coverFile := fs.String("cover", "", "profile from `go test -coverprofile`")
	out := fs.String("out", "", "metrics directory to write (default <root>/.metrics)")
	var muts mutationFlags
	fs.Var(&muts, "mutation", "DIR=FILE: a `gremlins unleash -o FILE` report run in DIR, relative to -root (repeatable)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *ir == "" {
		return errors.New("-ir is required")
	}
	if *coverFile == "" && len(muts) == 0 {
		return errors.New("nothing to write: pass -cover, -mutation, or both")
	}
	if *out == "" {
		*out = filepath.Join(*root, ".metrics")
	}

	pkgs, err := PackagesFromIR(*root, *ir, stderr)
	if err != nil {
		return err
	}
	if *coverFile != "" {
		if err := writeCrap(*coverFile, *out, pkgs, stdout); err != nil {
			return err
		}
	}
	for _, spec := range muts {
		if err := writeMutation(spec, *out, pkgs, stdout, now); err != nil {
			return err
		}
	}
	return nil
}

func writeCrap(coverFile, out string, pkgs []Package, stdout io.Writer) error {
	f, err := os.Open(coverFile)
	if err != nil {
		return err
	}
	profile, err := ParseProfile(f)
	_ = f.Close()
	if err != nil {
		return err
	}
	target := filepath.Join(out, "crap.edn")
	kept, err := otherCrapEntries(target)
	if err != nil {
		return err
	}
	entries := CrapEntries(pkgs, profile)
	if err := writeAtomic(target, CrapEDN(kept, entries)); err != nil {
		return err
	}
	fmt.Fprintf(stdout, "wrote %s (%d Go functions, %d other entries kept)\n", target, len(entries), len(kept))
	return nil
}

// otherCrapEntries reads the entries another language's CRAP tool left in
// crap.edn, which is the one file the overlay reads, so a project scanned
// with :sources keeps both. Earlier go-metrics entries are dropped.
func otherCrapEntries(target string) ([]Map, error) {
	data, err := os.ReadFile(target)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	doc, err := ReadEDN(string(data))
	if err != nil {
		return nil, fmt.Errorf("%s: %w", target, err)
	}
	m, _ := doc.(Map)
	var kept []Map
	for _, e := range asSeq(m.Get("entries")) {
		entry, ok := e.(Map)
		if ok && entry.Get("tool") != toolName {
			kept = append(kept, entry)
		}
	}
	return kept, nil
}

func asSeq(v any) []any {
	switch s := v.(type) {
	case Vector:
		return s
	case List:
		return s
	default:
		return nil
	}
}

func writeMutation(spec, out string, pkgs []Package, stdout io.Writer, now time.Time) error {
	dir, file, _ := strings.Cut(spec, "=")
	f, err := os.Open(file)
	if err != nil {
		return err
	}
	mrun, err := ParseMutationRun(dir, f)
	_ = f.Close()
	if err != nil {
		return err
	}
	if err := mrun.Check(pkgs); err != nil {
		return err
	}
	for _, pkg := range pkgs {
		if !mrun.Covers(pkg.Dir) {
			continue
		}
		target := filepath.Join(out, "mutate", pkg.Namespace+".edn")
		if err := writeAtomic(target, MutateEDN(pkg, mrun.Tallies(pkg.Funcs), now)); err != nil {
			return err
		}
		fmt.Fprintf(stdout, "wrote %s\n", target)
	}
	return nil
}

// writeAtomic replaces target in one rename, because the viewer reloads when
// a snapshot's mtime changes and must never read half a file.
func writeAtomic(target, content string) error {
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(target), ".go-metrics-*")
	if err != nil {
		return err
	}
	if _, err := tmp.WriteString(content); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmp.Name())
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmp.Name())
		return err
	}
	return os.Rename(tmp.Name(), target)
}
