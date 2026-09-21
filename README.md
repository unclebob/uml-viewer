# UML viewer
## Abstract

This project displays a project as UML. The UML is *dynamic*. You can click
elements to drill down — all the way to source code. You can also play
what-if games by proposing architecture changes.

The app is coupled to an agent assistant. The two communicate, so you can
display the UML, drill down, tell the agent what you don't like, and watch
it make the changes. Describe a proposal and the agent will build a new
diagram. Keep revising that proposal until you like it, then tell the
agent to make the code match.

Color coding comes from CRAP and mutation metrics. You can adjust the
thresholds in config if you like.

The viewer and agent treat namespaces as components. Modules inside those
namespaces are the components' elements. Nesting can be arbitrarily deep.
You build an architecture by moving modules into the right namespaces with
the right dependencies.

You choose architectural levels by telling the agent what coupling and
cohesion criteria to use. For example, I tell my agent to group modules
into components by the Common Closure Principle.

Dependencies that point from higher to lower level components are colored red in conformance to the Dependency Rule of Clean Architecture.

The bottom line: you can view the structure of your code, see how well
tests cover it, play what-if games, and manipulate the system from a high
level.

## Tech

A live Quil app that lays out and draws UML from an EDN IR. A **policy** plus
a language-specific parser write the topology; this tool displays it, routes
the arrows, colors CRAP, and lets you click.

The IR is **topology**: the namespace tree, classes, and edges. **Metrics**
(CC, coverage, CRAP, killed/survived/uncovered) come from `.metrics/` snapshots produced
by [crap4clj](https://github.com/unclebob/crap4clj) and
[clj-mutate](https://github.com/unclebob/clj-mutate). The viewer overlays those
files at load, keyed by namespace + function name. Agents edit the policy, not
the IR. See [Policy](#policy).

## Run

Needs Clojure CLI and Java 21+.

From **this repo**:

```bash
clj -M:ir                            # policy → examples/uml-viewer.edn
clj -M:run
clj -M:run examples/library.edn
clj -M:run examples/uml-viewer.edn
clj -M:run --help
```

From **any Clojure project** you want to view (no local uml-viewer checkout
needed):

```bash
cd /path/to/the-project
curl -fsSL https://raw.githubusercontent.com/unclebob/uml-viewer/master/scripts/get-uml-viewer -o get-uml-viewer
chmod +x get-uml-viewer
./get-uml-viewer               # fetch, write ./uml, start
./uml                          # later fresh starts
./uml --restart                # companion only: new JVM, restore last view
```

`scripts/get-uml-viewer` is in this repo. It clones uml-viewer into
**gitignored** `.uml-viewer/uml-viewer/` (a nested `.git` there is invisible
to the project's repo), writes `./uml`, and starts the viewer. `--install-only`
skips the start. `UML_VIEWER_REPO_URL` and `UML_VIEWER_REF` override the clone
source (default `master`).

A **tmux** session unique to the examined project starts interactive Grok in
that directory (`--yolo --trust --rules …` plus a launch prompt). The name is
`uml-viewer-<project>-<hash>`, stored in `.uml-viewer/companion.edn`. On start
it writes a hierarchical policy from that project's namespaces and regenerates
the IR. Type there; Esc is the real TUI interrupt. Closing the diagram kills
**only that** tmux session and its Terminal window — other Grok agents stay
up. If that Grok process dies, tmux respawns it in the same pane. That
instance also runs `clj -M:crap`, `clj -M:mutate`, and IR generate after later
changes. Project-wide rules live in `.grok/rules/uml-viewer.md`.

Prefer `./uml` in the examined project (from `get-uml-viewer`). Aliases
`:uml-viewer` / `:uml-viewer-restart` still work if present.

| Command | Who | What |
|---------|-----|------|
| `./uml` | anyone | Fresh window. Starts the companion. Waits for `:display`. |
| `./uml --restart` | **associated agent only** | New JVM, same companion. Restores the last view. |

Do **not** pass `--restart` unless you are that companion recycling the
window after source changes. A stray `--restart` skips spawning Grok and
leaves a diagram with no agent. The companion recycles the window by
writing `:quit-for-restart` to `.uml-viewer/to-viewer.edn`, waiting for
the JVM to exit, then `./uml --restart`. The new JVM restores depth, pan,
zoom, and which proposal was showing (`.uml-viewer/session.edn`). Do not
SIGKILL. Closing the window kills only that project's companion, not other
Grok agents.

On a fresh start the canvas stays blank until the companion sends `:display`,
with **Waiting for agent to create diagram.** `R` reloads the current EDN
immediately and does not wait. A missing or unreadable file prints
`UML viewer: file not found: …` in the inspector instead of throwing.

```bash
clj -M:spec
clj -M:cov
clj -M:ir                            # writes examples/uml-viewer.edn
clj -M:crap                          # writes .metrics/crap.edn
clj -M:mutate src/uml_viewer/engine/layout.clj
```

This project's `:crap` alias uses `../clojure/crap4clj`. `:mutate` pins
[clj-mutate](https://github.com/unclebob/clj-mutate) by git SHA. Commit
`.metrics/` so a clone has numbers without re-running those tools.

Rename or move of a function is a new form: overlay does not match old names.

## Navigation

**Layer** and **component** mean the same thing: a namespace grouping
(the first segment after the prefix, or a named proposal group).

- First view: **namespace components** (layers). Dependencies between them
  collapse to one arrow. Each component lists nested namespaces.
- Double-click a component to open the next level. Esc or the ← label goes
  up a level, including after drilling a proposal group. Esc does not quit.
  The window close box or a `:quit-for-restart` mail message exits the app.
- Hover an arrow for a popup of every `from -> to` it bundles, in any
  declutter mode. Violating pairs are red.
- Right-click a class or component for **Refresh CRAP**, **Refresh
  Mutation**, **Refresh All Mutation**, or **Omit**. Each writes
  `to-agent.edn` and wakes the companion. CRAP runs on that class or the
  files under that component. Mutation is differential `clj -M:mutate` on
  those src files; All Mutation passes `--mutate-all`. Omit adds the id to
  the current proposal's `:omit`, or to policy `:omit` on the real diagram.
- Component titles paint **on top of** crossing arrows.
- The inspector lists the **real diagram** (the namespace tree) above
  **Proposals**. Click the real row to return to the tree; click a proposal
  to show it (marked as not in the code). Either click writes `:context`
  so the companion treats that diagram as the discussion. **New Proposal**
  adds a timestamp-named proposal. Right-click to rename or delete.
  **Declutter** cycles Declutter arrows / Remove arrows / Declutter
  elements / Declutter classes / Declutter none. **Remove arrows** hides
  the lines and puts a triangle on the top (incoming) and bottom
  (outgoing) of each box, **including nested classes** as well as their
  packages. A triangle is red if any bundled pair is violating. Hover it
  for the `from -> to` list.
- Double-click a leaf module for its **class card**.
- The class card names the **module** (`:ns`). Click it to open that source
  file at the top. Hover a member to highlight it; click it to open the same
  file at the defn. See [Source extractors](#source-extractors).
- Methods on the card are `+` public and `-` private. `defn-` is not drawn on
  the class box.
- Abstract classes show a white **α** in the upper-right; interfaces a white
  **I**. Names of rectangles that are not classes (components/layers,
  interfaces, enumerations, package banners) are italic. Foreign libraries
  listed in policy are ovals outside the components.
- The main window is resizable.
- Scroll to pan vertically; Shift-scroll (or left/right arrows) for
  horizontal. Pan can follow arrows that bow past the origin.
- **Ctrl+** (or **Ctrl+=**) zooms in 10%; **Ctrl-** zooms out 10%;
  **Ctrl+0** restores 100%. Zoom keeps the view center still.
- **Regen** in the inspector asks the companion to rewrite policy and IR
  (see [Companion mailbox](#companion-mailbox)).
- `R` reloads the current EDN. The watcher also reloads on save, and when
  `.metrics/` snapshots change. An open class card updates with the new
  numbers.
- `Esc` on the class card closes it (it does not quit the viewer). Closing
  the main window exits the app.

## Policy

This project's diagram is **generated**. Do not edit `examples/uml-viewer.edn`.
Edit `examples/uml-viewer.policy.edn`, then run `clj -M:ir` (or press Regen).

The **parser** (`LanguageGraph`) reads source and emits facts: one class per
project namespace, `:require` / `:use` of another project ns as
`:dependency`, `requiring-resolve` of a quoted var as `:dependency` on that
var's namespace, `defprotocol` as `:stereotype :interface`, `defrecord` /
`deftype` of a protocol as `:implements`. External `:require`s and `:import`s
become **foreign** classes. Members are not authored — overlay fills them from
`.metrics/`.

### Do not invent layers (components)

The tree **is** the namespaces. After `:prefix`, every `.` is a nesting
level. `uml-viewer.engine.layout` is a child of `engine`.
`uml-viewer.clojure-language.source-clojure` is a child of `clojure-language`.
The policy does **not** assign nses to invented packages. If you want Domain /
Engine / Adapters boxes **in the source tree**, those segments must exist as
namespaces. To **view** a grouping that is not in the code, use `:proposals`
(see [Proposed components](#proposed-components)) — do not rewrite namespaces.

To write a policy for a project:

1. Set `:src` and `:prefix` to the project's source root and ns prefix
   (`src` and `foo` for `foo.bar.baz`).
2. Set `:hierarchical true` (or omit `:packages` and `:diagrams`).
3. List top-level **segments** in `:order` — the first dotted part after
   the prefix, in the order you want the boxes. Do not invent names.
4. List real libraries in `:foreign` if they should appear as ovals.
5. Optionally override a require with `:edge-kinds {[:from :to] :association}`
   using the **leaf** ids (`clojure-language.source-clojure`, not
   `clojure-language`).
6. Set `:levels` so the generator can mark dependency-rule violations
   (see [Dependency rule](#dependency-rule)).
7. Optionally set `:proposals` to name design components that are not namespaces
   (see [Proposed components](#proposed-components)).
8. Run `clj -M:ir` (or Regen).

If `foo.bar` and `foo.bar.baz` both exist, the `bar` box lists `bar` (the
module) and `baz` (the child). Module titles are the last ns segment
(`layout`, not `engine.layout`). Double-click the component to open that
level; double-click the `bar` module line for its class card.

Wrong (invented partitions):

```edn
:packages [{:id :domain :nses [ir geom source]}
           {:id :engine :nses [layout route]}]
```

Right (the ns tree):

```edn
{:title "UML viewer"
 :src "src"
 :prefix "uml-viewer"
 :lang :clojure
 :out "examples/uml-viewer.edn"
 :hierarchical true
 :foreign [quil]
 :order [main adapters application engine source graph clojure-language domain]
 :levels [[domain source graph clojure-language]
          [engine]
          [application]
          [adapters]
          [main]]
 :edge-kinds {[:engine.compose :engine.layout] :association}}
```

| Key | Role |
|-----|------|
| `:prefix` | Strip this from each ns; remaining dots are the tree |
| `:hierarchical` | Namespace tree (default when `:packages` is omitted) |
| `:order` | Order of **existing** top-level ns segments, not new component names |
| `:levels` | Groups of those segments, **inner (higher-level) first**. Same group = same rank |
| `:proposals` | Named groupings of real segments; **not** instantiated in source. Inspector **Real diagram** row returns to the ns tree |
| `:omit` | On a proposal or the policy: nses (and their children) left off the diagram |
| `:edge-kinds` | Override parser kind for `[from to]` (usually `:association`) |
| `:omit-edges` | Drop `[from to]` |
| `:lang` | Which `LanguageGraph` to use (default `:clojure`) |
| `:foreign` | External libs as ovals. A listed prefix collapses `quil.core` to `quil`. |

**Viewer Grok loop** (passed with `--rules` to the companion session only)

On launch: from the examined directory, write or update the hierarchical
policy and regenerate the IR, then wait.

After **every** later source or policy change: `clj -M:crap`, `clj -M:mutate`
on the changed `src/` files, then `clj -M:ir`. Uncovered mutants remaining are
coverage gaps; keep the snapshot and do not re-run the file or force a full
mutation because mutate exited non-zero.

- Add/rename/delete a namespace: the tree updates on `clj -M:ir`. Put a new
  top-level **segment** in `:order` if you care about box order.
- Nested nses appear as contents of the parent component.
- “This require is really an association”: one `:edge-kinds` entry.
- Show a library like quil as an oval: add it to `:foreign`.
- Do not add `:packages` to fake Clean Architecture components. Use
  `:proposals` to view a grouping that is not in the code.
- Preserve `:proposals` when rewriting policy. Do not invent them on launch.
  If instructed, add a named proposal (default name is a timestamp).

Hand-written sample IRs (e.g. `examples/library.edn`) are still valid; they
are not generated.

### Dependency rule

A `:dependency` edge is **violating** when it runs from a **higher-level**
(inner) component to a **lower-level** (outer) one. That is the Clean
Architecture dependency rule: source-code dependencies point inward.

Evaluation is deterministic given `:levels`:

1. Take the first dotted segment of each end (`engine.layout` → `engine`).
2. Look up that segment in `:levels`. Rank is the group's index; **smaller
   is inner / higher-level**.
3. If both ends have a rank and `from-rank < to-rank`, the edge is
   `:violating true`. Same rank is allowed. `:implements` and
   `:association` are never violating. Foreign / unranked ends are not
   compared.
4. Collapsed component arrows keep the flag if any bundled leaf dependency
   was violating. Remapping a pair to `:association` clears it.

`:order` is visual box order, not rank. Nesting is not layering: you cannot
infer inner vs outer from the namespace tree alone, so `:levels` must group
segments that sit at the same architectural level (e.g. `domain`, `source`,
and `graph`). Omit `:levels` and nothing is marked. If `:levels` is omitted
and `:proposals` is set, rank follows the first proposal's component order.

### Proposed components

`:proposals` is a list of named groupings of **existing** top-level segments.
Those names are not namespaces. Each item is `{:id :name :layers [...] :omit [...]}`
(`:layers` here are named components; `:omit` keeps listed nses off
**Unassigned**). The as-is diagram stays the ns tree.
The inspector lists the real diagram (the namespace tree) just above
**Proposals**; click it to return to the tree. Click a proposal to show it
(canvas marked **PROPOSAL — not instantiated in code**). Either click
tells the companion that diagram is the context of discussion. **New
Proposal** adds an empty proposal named with a timestamp. Right-click a
name to rename or delete it. Double-click a ns box to drill; **←** at the
top returns to the proposal.

The **Declutter** button cycles **Declutter arrows** (one arrow per
component pair per direction) → **Remove arrows** (triangles on each box
and on nested classes instead of lines; hover lists deps; red if any pair
is violating) →
**Declutter elements** (also hide nested names, members, and ports) →
**Declutter classes** (also hide classes inside components) →
**Declutter none**.

When a proposal is shown, rank follows **that** proposal's component order
and violating arrows are re-evaluated. The real diagram uses `:levels`.
A component inherits the **max** level of its elements.
Class boxes show the current view's rank (innermost **0**)
at the upper left; the class card repeats **Level n**. Level 0 is drawn at
the **bottom**. Good arrows (outer → inner) point down; violating arrows
(inner → outer) point up and stay red. When arrows are collapsed, selecting a class highlights
the component arrows it belongs to. Collapsed components keep their color
and C/M dots; double-click still opens a component.

```edn
:proposals [{:id :ccp
             :name "2026-09-18 10:30:00"
             :layers [{:id :playfield :label "Playfield"
                       :nses [entities world missiles cities batteries flyers]}
                      {:id :hosts :label "Hosts" :nses [jvm browser]}
                      {:id :jvm :label "JVM"
                       :nses [jvm.cli jvm.main
                              {:id :quil-swing :label "Quil/Swing"
                               :nses [jvm.sketch jvm.window]}]}]
             :omit [cli]}]
```

Companion Grok must not invent `:proposals` on launch and must keep them when
updating `:order`. If instructed, add a named proposal and regenerate the IR.

The viewer draws a violating arrow **red**, and **bold red** when a selected
element highlights it. Hand-written IR may set `:violating true` directly.

## Companion mailbox

The viewer and the companion Grok talk through `.uml-viewer/` in the examined
project (gitignored). The file is the mail; tmux is only a doorbell.

| File | Direction |
|------|-----------|
| `.uml-viewer/to-viewer.edn` | Grok → viewer |
| `.uml-viewer/to-agent.edn` | viewer → Grok |

Each mailbox file is a small queue `{:next-id n :queue [cmd …]}` (tmp-then-rename).
Commands have a rising `:id`. Append; do not overwrite. Handling a command
**pops** it from `:queue` and rewrites the file (oldest first). The viewer
does this itself on `to-viewer.edn`. The companion must pop each
`to-agent.edn` command as it handles it.

`.uml-viewer/session.edn` is the last view (depth, pan, zoom, proposal) written
on `:quit-for-restart` and restored by `--restart`. `.uml-viewer/companion.edn`
records this viewer's tmux session and Terminal window id so close/kill never
touches another project's agent.

| `:op` | Meaning |
|-------|---------|
| `:display` | Viewer loads `:path` (relative to the project root) |
| `:regen` | Grok rewrites hierarchical policy, regenerates IR, then `:display` |
| `:quit-for-restart` | Viewer exits the JVM without killing Grok. The associated agent then runs `clj -M:uml-viewer-restart`. |
| `:refresh-crap` | Run CRAP on `:target` (class or component), then IR |
| `:refresh-mutate` | Differential mutate `:target`'s src files, then IR |
| `:refresh-mutate-all` | `clj -M:mutate --mutate-all` on `:target`'s src files, then IR |
| `:omit` | Add `:target` `:id` to proposal or policy `:omit`, then IR |
| `:context` | Inspector selection is the discussion context: `{:context :real}` or `{:context :proposal :proposal-id id :name "…"}` |

Clicking **Real diagram** or a proposal (including **New Proposal**) writes
`:context` and wakes Grok. Stay on that architecture until the next
`:context`.

Right-click ops include `:target {:id :ns :kind :class|:component :proposal-id?}`.
`:kind` is `:component` for a layer box (and its nested nses) and `:class`
for a module.

**Regen** in the inspector queues `:regen` and wakes Grok with literal text, a
150ms pause, `C-m`, 50ms, then `C-j` (same timing as SwarmForge). The wake-up
does not contain the command. If Grok is busy, it finishes first, then reads
the mailbox. If tmux is missing, the button still writes the file and the
inspector says the session is not attached.

### Other companions

Grok is the default, not a requirement. The mailbox above is the whole
contract, so any agent that reads `to-agent.edn` and writes `to-viewer.edn`
can be the companion.

```bash
clj -M:run --companion-command='["claude" "--append-system-prompt" "{rules}" "{prompt}"]' examples/library.edn
clj -M:run --no-companion examples/library.edn
```

`--companion-command=` takes a vector of strings and starts that agent in the
tmux session instead of Grok. `{rules}` and `{prompt}` become the standing
rules and the launch prompt Grok gets. Nothing else is added: the agent runs
with exactly the flags and permissions you give it.

`--no-companion` starts no agent, no tmux session and no Terminal window, and
closing the window kills nothing. Use it when something else serves the
mailbox, or to look at a diagram alone (press R to load the file).

## Language graphs

Generating the IR asks `uml-viewer.graph` to scan a source tree. `:lang`
selects the scanner (default `:clojure`). Register another implementation
with `(graph/register! :java my-java-scanner)`. The scanner must satisfy
`LanguageGraph`:

| method | role |
|--------|------|
| `scan` | from a root directory and `{:prefix …}`, return `{:classes :edges}` |

Classes are `{:id :name :ns :stereotype}`. Edges are `{:from :to :kind}`
(`:dependency` or `:implements`). The policy layer is language-neutral.

**Clojure** (`uml-viewer.clojure-language.graph-clojure`) is the only
implementation today: it reads `ns` forms (including prefix lists),
`requiring-resolve` of a quoted var (including nested calls), `defprotocol`,
`defrecord`, and `deftype`. Java or C need a different parser; do not
special-case languages in `policy` or `ir-generator`. Main constructs the
implementation and passes it in.

## IR

A hierarchical policy writes one EDN document of all classes and edges
(`:hierarchical true`). The viewer builds each screen from the namespace tree
at the current drill level. A hand-written IR with `:packages` (or
`:diagrams`) is still a static diagram, e.g. `examples/library.edn`.

Metrics on the class card do not have to be authored. If `.metrics/` is
present, the overlay fills CC, coverage, CRAP, killed/survived/uncovered, and any
functions found in the snapshots (including privates). Authored `:crap` /
`:coverage` / `:ops` are the fallback when no snapshot exists.

Overlay keys snapshots by class `:ns` (the real source namespace). The
generator writes `:ns` from the scanned ns. Hand-written IR must set `:ns`
the same way; there is no project-specific fallback.

```edn
{:title "Lending library"
 :direction :tb
 :packages
 [{:id :domain
   :label "Domain"
   :classes
   [{:id :book
     :name "Book"
     :stereotype :class          ;; optional: :interface :enumeration :abstract
     :fields [{:name "isbn" :type "String"}]
     :ops [{:name "find" :args ["isbn"] :returns "Book"}]}]}
  {:id :app
   :label "Application"
   :classes
   [{:id :repo
     :name "CatalogRepo"
     :stereotype :interface
     :ops [{:name "get" :args ["isbn"] :returns "Book"}]}]}]
 :edges
 [{:from :sql-repo :to :repo :kind :implements}
  {:from :loan :to :book :kind :association :label "borrows"}]}
```

Optional authored metrics, used when snapshots are missing:

- `:crap` — a number (`μ`) or `{:mu :max :sigma}`
- `:coverage` — ratio 0–1 on a class or op
- `:cc`, `:killed`, `:survived`, `:uncovered`, `:private` on ops
- `:hide-members true` — compact box

Package and class **color** maps CRAP (`μ + σ`) and mutation score each onto
1–10 using `uml-viewer.domain.config` cutoffs, averages them, and paints a
0–10 red–green fill. Missing CRAP or mutation data counts as red (grade 1),
not unknown. Parents take the worst CRAP and worst mutation of their
children, and a child with no data is the worst. A **C** and **M** dot in
the upper-right show the two scores. The boxes no longer print μ / max / σ.

On the class card, a `Crap μ … max … σ …` line sits above the table (max is
the worst function in the namespace, not a sum). Column groups are labeled
`--crap--` (Crap, CC, Cov) and `--mutation--` (killed, survived, uncovered).
The class row shows average CRAP with a `μ` suffix and omits CC. Killed is
white. Survived and uncovered are green at 0 and red when nonzero. A row
with no mutation operators (`:sites` 0) shows `---no mutation sites---`.
A form with operators still shows killed/survived/uncovered, including zeros.

Edge `:kind` values:

| kind | line | head |
|------|------|------|
| `:inheritance` | solid grey | empty triangle |
| `:implements` | solid grey | empty triangle |
| `:association` | solid grey | open arrow |
| `:dependency` | solid grey | open arrow |
| `:aggregation` | solid grey | empty diamond |
| `:composition` | solid grey | filled diamond |

Layout follows Mermaid's three stages:

1. **Size** each class from its text (padding 12).
2. **Place** packages in document order; classes Sugiyama-ranked inside a
   package; ~40px spacing.
3. **Route** like Mermaid/ELK: ports on facing sides, orthogonal tracks in
   the rank gap, short same-rank connections through the stack gap (local U
   only when a sibling sits in the way), then stroke with D3 `curveBasis`
   cubics. Ordinary arrows are solid grey; violating dependencies are red
   (bold red when selected). Component titles draw after the arrows so
   names stay visible. Paths that pass the target and reverse are rejected.

## Source extractors

Clicking a member asks `uml-viewer.source` for the **whole file** and a
**start line**. The IR (and the class card) only supply an **identity
map**; a language-specific extractor turns that into
`{:title :file :body :line}`. The source window opens on that file and
scrolls to the member (highlighted). Clicking the module name opens the same
file at the top (`:line` omitted).

```clojure
(source/member-source {:lang :clojure
                       :ns "uml-viewer.engine.layout"
                       :name "layout"})
```

`:lang` selects the extractor (default `:clojure`). Register another
implementation with `(source/register! :java my-java-extractor)`. The
extractor must satisfy `LanguageSource`:

| method | role |
|--------|------|
| `locate` | path to the file that should contain the member |
| `extract` | slice that member out of the file text |
| `title` | window title |

**Clojure** (`uml-viewer.clojure-language.source-clojure`) is the only
implementation today: it maps `:ns` to `src/...clj` (or `.cljc` / `.cljs`)
and finds the top-level `(defn name …)` / `(defn- name …)` so the window can
jump to that line. That locate/line step is not enough for Java or C — those
need a parser or language server, and a richer identity (`:class`,
`:signature`, `:file`). The protocol is the seam; do not special-case
languages in the class card. Main constructs the extractor and passes it to
Core.

Quil stays in `adapters.draw` and `adapters.sketch`. The rest of the engine
does not depend on Processing.
