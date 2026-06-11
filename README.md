# Blast Radius

**Static change-impact candidate generator for Clojure(Script).** Given a commit (or a pair of
refs), it names the code that change could affect — including **data-mediated coupling through
qualified keywords** that call-graph tools miss (a resolver that writes `:invoice/total`, a
form whose `fo/attributes` lists a changed attribute, a derived-data cascade into accounting).

It is **recall-first by design**: the static layer over-approximates and *names everything that
is genuinely coupled*; a downstream per-candidate LLM/human pass decides what actually breaks.
Output is keyed to **affected files** — one review unit per file.

Built for Fulcro / RAD + Datomic + Pathom apps (developed against `dataico`), but the core is a
general Clojure call-graph + qualified-keyword data-flow analysis.

## Why

Two functions can be tightly coupled with **no call edge between them**: one writes a Datomic
attribute, another reads it; a resolver produces an EQL key a client query consumes. Renaming or
changing the semantics of `:invoice/status` can break code in a different namespace, process, or
even across the client/server wire. Blast Radius models this as a **two-edge graph**:

- **Edge A — call staleness:** a caller may be stale when a callee it (transitively) depends on changes.
- **Edge B — data coupling:** when a changed var is a *source* of a keyword, every reader of that
  keyword is reached (supply/demand: only *sources* fan out, so pure readers don't explode).

The named set is the **complete** blast radius (the governing "name everything" rule, design §7);
the only sanctioned ways to shrink it are **structural false-edge elimination** and an explicit,
disclosed **trust-list** — never dropping a coupling for being "unlikely."

## Install

Requires `bb`, `java`, `clj-kondo`, and `git`. Install the launcher with
[bbin](https://github.com/babashka/bbin):

```bash
bbin install https://raw.githubusercontent.com/fulcrologic/blast-radius/main/bin/blast-radius --as blast-radius
```

That's it — **no clone, no env vars, no config.** On first run the launcher downloads the engine
(a self-contained JVM jar) from the GitHub Release and caches it; every run after is plain
`java`. (If the release asset is ever unavailable it falls back to cloning + building from source,
which additionally needs the `clojure` CLI.) See [docs/USAGE.md](docs/USAGE.md) for optional
overrides.

## Usage

Run from the repo you want to analyze (config in its `.blast-radius.edn`):

```bash
blast-radius "$(git merge-base origin/main HEAD)..HEAD"     # a branch/PR vs its merge-base
blast-radius <commit>                                       # one commit (<commit>^..<commit>)
blast-radius v1.2.0..HEAD --out-file /tmp/br.edn --trust-keywords :db/id,:company/id
```

Writes `blast-radius.edn` — the complete ranked candidate set — and prints the affected-file
count plus the **recall frontier** (the blind spots this run could not see: dynamic keywords,
stringly-typed payloads, etc.).

### Project config — `.blast-radius.edn`

```clojure
{:ns-prefixes   ["myapp."]                          ; call-graph project scoping
 :paths         ["src/main"]                        ; trees to analyze
 :base-clj-path "src/main/myapp/cud_table.clj"}     ; optional dispatch table (synthetic edges)
;; :sinks-file optional — defaults to the bundled Datomic/JDBC/HTTP/queue registry.
```

## How it runs

The static analysis is taken at the tree **of the new ref** (faithful per-commit), materialized
read-only via `git archive`, linted by the native `clj-kondo` binary, and cached by **tree-SHA**.
On dataico (~3,800 files): **cold ≈80s, warm ≈16s** (analysis cache hit).

## Output

```clojure
{:run {:refs […] :named-count N :trust-list-excluded M :recall-frontier [...]}
 :candidates [{:change {…} :file "…"
               :affects [{:target-sym … :file … :via-keywords #{…} :rank … :rank-why {…}} …]}
              …]}
```

Each candidate is an affected file with its couplings, the connecting keyword(s), and a local
rank for triage (ranking orders the set; it never removes).

## Documentation

- [design.md](design.md) — the full model and rationale (the source of truth for intent).
- [docs/USAGE.md](docs/USAGE.md) — install, flags, config, performance.
- [formal-model.md](formal-model.md) — the soundness/minimality model.

## Status

Working end-to-end against dataico; recall-first with the over-approximation fixes verified
(the month's worst-case radii drop from a median ~1600 files to ~200, with cross-process and
derived-data couplings preserved).
