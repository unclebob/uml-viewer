package main

import (
	"fmt"
	"strconv"
	"strings"
	"unicode"
)

// The EDN subset the viewer writes: maps, vectors, lists, sets, strings,
// keywords, symbols, numbers, booleans and nil. Maps keep their entries in
// order, as pairs, because keys such as [:a :b] are not comparable in Go.

type Keyword string
type Symbol string

type Entry struct{ Key, Val any }
type Map []Entry
type Vector []any
type List []any
type Set []any

// Get returns the value under keyword k, or nil.
func (m Map) Get(k string) any {
	for _, e := range m {
		if kw, ok := e.Key.(Keyword); ok && string(kw) == k {
			return e.Val
		}
	}
	return nil
}

// ReadEDN parses one EDN value. Trailing content other than whitespace,
// commas and comments is an error.
func ReadEDN(src string) (any, error) {
	r := &ednReader{src: src}
	v, err := r.value()
	if err != nil {
		return nil, err
	}
	r.skip()
	if r.pos < len(r.src) {
		return nil, r.errorf("unexpected %q after value", r.src[r.pos])
	}
	return v, nil
}

type ednReader struct {
	src string
	pos int
}

func (r *ednReader) errorf(format string, args ...any) error {
	line := 1 + strings.Count(r.src[:r.pos], "\n")
	return fmt.Errorf("edn line %d: %s", line, fmt.Sprintf(format, args...))
}

func (r *ednReader) skip() {
	for r.pos < len(r.src) {
		c := r.src[r.pos]
		switch {
		case c == ';':
			for r.pos < len(r.src) && r.src[r.pos] != '\n' {
				r.pos++
			}
		case c == ',' || unicode.IsSpace(rune(c)):
			r.pos++
		default:
			return
		}
	}
}

func (r *ednReader) value() (any, error) {
	r.skip()
	if r.pos >= len(r.src) {
		return nil, r.errorf("unexpected end of input")
	}
	switch c := r.src[r.pos]; {
	case c == '{':
		r.pos++
		return r.mapBody()
	case c == '[':
		r.pos++
		items, err := r.seq(']')
		return Vector(items), err
	case c == '(':
		r.pos++
		items, err := r.seq(')')
		return List(items), err
	case c == '#' && strings.HasPrefix(r.src[r.pos:], "#{"):
		r.pos += 2
		items, err := r.seq('}')
		return Set(items), err
	case c == '"':
		return r.str()
	case c == ':':
		r.pos++
		return Keyword(r.token()), nil
	default:
		return r.atom()
	}
}

func (r *ednReader) seq(end byte) ([]any, error) {
	var items []any
	for {
		r.skip()
		if r.pos >= len(r.src) {
			return nil, r.errorf("missing %q", end)
		}
		if r.src[r.pos] == end {
			r.pos++
			return items, nil
		}
		v, err := r.value()
		if err != nil {
			return nil, err
		}
		items = append(items, v)
	}
}

func (r *ednReader) mapBody() (Map, error) {
	items, err := r.seq('}')
	if err != nil {
		return nil, err
	}
	if len(items)%2 != 0 {
		return nil, r.errorf("map with an odd number of forms")
	}
	m := make(Map, 0, len(items)/2)
	for i := 0; i < len(items); i += 2 {
		m = append(m, Entry{items[i], items[i+1]})
	}
	return m, nil
}

func (r *ednReader) str() (string, error) {
	var b strings.Builder
	for r.pos++; r.pos < len(r.src); r.pos++ {
		c := r.src[r.pos]
		switch c {
		case '"':
			r.pos++
			return b.String(), nil
		case '\\':
			r.pos++
			if r.pos >= len(r.src) {
				return "", r.errorf("unterminated string")
			}
			switch e := r.src[r.pos]; e {
			case 'n':
				b.WriteByte('\n')
			case 't':
				b.WriteByte('\t')
			case 'r':
				b.WriteByte('\r')
			case 'u':
				if r.pos+4 >= len(r.src) {
					return "", r.errorf("short \\u escape")
				}
				code, err := strconv.ParseUint(r.src[r.pos+1:r.pos+5], 16, 32)
				if err != nil {
					return "", r.errorf("bad \\u escape")
				}
				b.WriteRune(rune(code))
				r.pos += 4
			default:
				b.WriteByte(e)
			}
		default:
			b.WriteByte(c)
		}
	}
	return "", r.errorf("unterminated string")
}

func (r *ednReader) token() string {
	start := r.pos
	for r.pos < len(r.src) && !delimiter(r.src[r.pos]) {
		r.pos++
	}
	return r.src[start:r.pos]
}

func delimiter(c byte) bool {
	return unicode.IsSpace(rune(c)) || strings.IndexByte(",;()[]{}\"", c) >= 0
}

func (r *ednReader) atom() (any, error) {
	tok := r.token()
	if tok == "" {
		return nil, r.errorf("unexpected %q", r.src[r.pos])
	}
	switch tok {
	case "nil":
		return nil, nil
	case "true":
		return true, nil
	case "false":
		return false, nil
	}
	if n, err := strconv.ParseInt(strings.TrimSuffix(tok, "N"), 10, 64); err == nil {
		return n, nil
	}
	if f, err := strconv.ParseFloat(strings.TrimSuffix(tok, "M"), 64); err == nil {
		return f, nil
	}
	return Symbol(tok), nil
}

// WriteEDN renders the values ReadEDN produces.
func WriteEDN(v any) string {
	var b strings.Builder
	writeEDN(&b, v)
	return b.String()
}

func writeEDN(b *strings.Builder, v any) {
	switch x := v.(type) {
	case nil:
		b.WriteString("nil")
	case bool:
		b.WriteString(strconv.FormatBool(x))
	case int64:
		b.WriteString(strconv.FormatInt(x, 10))
	case int:
		b.WriteString(strconv.Itoa(x))
	case float64:
		b.WriteString(ednFloat(x))
	case string:
		b.WriteString(ednString(x))
	case Keyword:
		b.WriteString(":" + string(x))
	case Symbol:
		b.WriteString(string(x))
	case Map:
		b.WriteByte('{')
		for i, e := range x {
			if i > 0 {
				b.WriteString(", ")
			}
			writeEDN(b, e.Key)
			b.WriteByte(' ')
			writeEDN(b, e.Val)
		}
		b.WriteByte('}')
	case Vector:
		writeSeq(b, "[", "]", x)
	case List:
		writeSeq(b, "(", ")", x)
	case Set:
		writeSeq(b, "#{", "}", x)
	default:
		panic(fmt.Sprintf("no EDN form for %T", v))
	}
}

func writeSeq(b *strings.Builder, open, close string, items []any) {
	b.WriteString(open)
	for i, it := range items {
		if i > 0 {
			b.WriteByte(' ')
		}
		writeEDN(b, it)
	}
	b.WriteString(close)
}
