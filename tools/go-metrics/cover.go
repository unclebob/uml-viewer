package main

import (
	"bufio"
	"fmt"
	"io"
	"math"
	"strconv"
	"strings"
)

type span struct {
	startLine, startCol, endLine, endCol int
}

type blockStat struct {
	span
	stmts   int
	covered bool
}

// Profile is a coverprofile with duplicate blocks merged. `go test
// -coverpkg` writes one copy of a block per test binary, and the block counts
// as covered when any of them ran it.
type Profile struct {
	byFile map[string][]blockStat
}

// ParseProfile reads `go test -coverprofile` output. File names are import
// paths, as the go tool writes them.
func ParseProfile(r io.Reader) (*Profile, error) {
	merged := map[string]map[span]*blockStat{}
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for n := 1; sc.Scan(); n++ {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "mode:") {
			continue
		}
		file, s, stmts, count, err := parseBlock(line)
		if err != nil {
			return nil, fmt.Errorf("coverprofile line %d: %w", n, err)
		}
		if merged[file] == nil {
			merged[file] = map[span]*blockStat{}
		}
		b := merged[file][s]
		if b == nil {
			b = &blockStat{span: s, stmts: stmts}
			merged[file][s] = b
		}
		b.covered = b.covered || count > 0
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	p := &Profile{byFile: map[string][]blockStat{}}
	for file, blocks := range merged {
		for _, b := range blocks {
			p.byFile[file] = append(p.byFile[file], *b)
		}
	}
	return p, nil
}

// parseBlock reads `file:startLine.startCol,endLine.endCol numStmt count`.
func parseBlock(line string) (string, span, int, int, error) {
	var s span
	colon := strings.LastIndex(line, ":")
	if colon < 0 {
		return "", s, 0, 0, fmt.Errorf("no file in %q", line)
	}
	fields := strings.Fields(line[colon+1:])
	if len(fields) != 3 {
		return "", s, 0, 0, fmt.Errorf("want range, statements, count in %q", line)
	}
	if _, err := fmt.Sscanf(fields[0], "%d.%d,%d.%d", &s.startLine, &s.startCol, &s.endLine, &s.endCol); err != nil {
		return "", s, 0, 0, fmt.Errorf("bad range in %q: %w", line, err)
	}
	stmts, err := strconv.Atoi(fields[1])
	if err != nil {
		return "", s, 0, 0, err
	}
	count, err := strconv.Atoi(fields[2])
	if err != nil {
		return "", s, 0, 0, err
	}
	return line[:colon], s, stmts, count, nil
}

// Coverage is the percent of f's statements that ran, matching
// `go tool cover -func`, except that a function with no statements in a
// profiled file is fully covered rather than 0%: nothing in it is untested.
// A file missing from the profile was never instrumented, so it is 0%.
func (p *Profile) Coverage(importFile string, f Func) float64 {
	blocks, profiled := p.byFile[importFile]
	total, covered := 0, 0
	for _, b := range blocks {
		if !f.Contains(b.startLine, b.startCol) {
			continue
		}
		total += b.stmts
		if b.covered {
			covered += b.stmts
		}
	}
	switch {
	case total > 0:
		return 100 * float64(covered) / float64(total)
	case profiled:
		return 100
	default:
		return 0
	}
}

// CRAP is cc² × (1 − coverage)³ + cc, coverage as a percent.
func CRAP(complexity int, coveragePct float64) float64 {
	cc := float64(complexity)
	uncovered := 1 - coveragePct/100
	return cc*cc*math.Pow(uncovered, 3) + cc
}
