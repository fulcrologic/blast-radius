# Blast Radius — install & usage

Static change-impact candidate generator. Distributed as a **bbin-installed Babashka
launcher** that `exec`s a JVM with the right classpath: bb gives the easy install + fast CLI
startup; the JVM does the heavy build (native clj-kondo analysis + graph transforms), which is
several-fold faster than sci.

## Requirements

- `bb` (Babashka) — the launcher.
- A **JVM** (`java`) — the engine (runs the bundled uberjar; **no clojure CLI needed**).
- `clj-kondo` native binary on PATH — the analysis engine (≈4× faster cold than the JVM library).
- `git` — change detection + read-only tree materialization.
- `clojure` CLI — *optional*, only for the dev / git-coordinate fallback engine paths.

## Build the engine jar

```bash
clojure -T:build uber        # -> target/blast-radius.jar (self-contained, ~10MB)
```

The jar runs standalone: `java -cp target/blast-radius.jar clojure.main -m blast-radius.cli …`.

## Install (bbin)

```bash
bbin install https://raw.githubusercontent.com/fulcrologic/blast-radius/main/bin/blast-radius --as blast-radius
```

This installs the launcher script (a `#!/usr/bin/env bb` file with no extra deps). On first run
it resolves the engine; with nothing else configured it falls back to the public git coordinate
(`io.github.fulcrologic/blast-radius`, pinned `:git/sha` in `bin/blast-radius`) via the clojure
CLI. For the fastest **java-only** runtime, point it at a checkout so it builds + caches a jar:

```bash
git clone https://github.com/fulcrologic/blast-radius
export BLAST_RADIUS_HOME="$(pwd)/blast-radius"
```

For a fully zero-clojure-CLI install, attach the uberjar to a GitHub release and set
`$BLAST_RADIUS_JAR_URL` to the asset URL (private registries: also set `$BLAST_RADIUS_JAR_HEADER`).

The launcher resolves a JAVA-ONLY engine (no clojure CLI at runtime), building/caching the
uberjar on demand so **nothing has to be published**. Resolution order (first match wins):

1. `$BLAST_RADIUS_JAR` — an explicit jar path.
2. `$BLAST_RADIUS_HOME` (a local checkout) — **builds the uberjar into the cache on first run,
   and rebuilds only when `src`/`resources`/`deps.edn`/`build.clj` change** (`clojure -T:build
   uber`; the clojure CLI is needed for this one-time build, not at runtime).
3. `~/.cache/blast-radius/blast-radius.jar` — a previously built/downloaded jar.
4. `$BLAST_RADIUS_JAR_URL` — downloaded to the cache on first run (point at a published release
   asset for a zero-build, zero-clojure-CLI install).
5. a pinned git coordinate via the clojure CLI — last resort (no jar; clojure CLI per run).

So there is **no need to publish a jar**: with `BLAST_RADIUS_HOME` set to a checkout, the first
run builds + caches it and every run after is plain `java` against the cached jar (auto-rebuilt
on source change). Publishing (#4) is only worth it if you want users to skip even the one-time
local build / clojure CLI entirely.

```bash
# No-publish, java-fast: point at a checkout; first run builds + caches the engine.
export BLAST_RADIUS_HOME=/path/to/blast-radius

# Or use a jar you built/obtained directly:
export BLAST_RADIUS_JAR=/path/to/blast-radius.jar
```

## Run (from the repo you want to analyze)

```bash
cd path/to/your-repo

# a PR / branch vs its merge-base:
blast-radius "$(git merge-base origin/main HEAD)..HEAD"

# a single commit (expands to <commit>^..<commit>):
blast-radius 1a2b3c4

# two explicit refs + options:
blast-radius v1.2.0..HEAD --out-file /tmp/br.edn --trust-keywords :db/id,:company/id
```

Writes `blast-radius.edn` (the COMPLETE named candidate set, §7) and prints the affected-file
count + recall frontier. Repo defaults to the current directory.

### Flags

- `--out-file PATH` — output EDN (default `blast-radius.edn`).
- `--repo-root DIR` — repo to analyze (default: CWD).
- `--paths a,b` — source subtrees to analyze (default `src/main`).
- `--ns-prefixes a.,b.` — call-graph project scoping (§27.2).
- `--trust-keywords :a/b,:c/d` / `--trust-vars ns/x,ns/y` / `--trust-namespaces a.b,c.d` —
  declared trust-list (§7 lever 2); every exclusion is disclosed on the recall frontier.
- `--cache-dir DIR` — analysis cache location (default `<repo>/.blast-radius`).
- `--kondo-bin PATH` — clj-kondo binary (default `clj-kondo`).
- `--cached-analysis PATH` — use a prebuilt analysis EDN (skip per-ref analysis).

## Project config — `.blast-radius.edn`

Place at the repo root; merged under CLI flags. Keys mirror the flags:

```clojure
{:ns-prefixes   ["myapp."]
 :paths         ["src/main"]
 :base-clj-path "src/main/myapp/cud_table.clj"   ; optional CUD dispatch table (§22.1)
 :sinks-file    ".blast-radius/sinks.edn"}        ; optional; defaults to the bundled registry
```

## Performance

The static analysis is done at the **tree of the NEW ref** (faithful per-commit) and cached by
**tree-SHA** under `<repo>/.blast-radius`, so the expensive clj-kondo pass runs once per source
tree.

- **Cold** (new tree): ~80s on dataico (≈3,800 files) — dominated by native clj-kondo.
- **Warm** (analysis cache hit, same tree): ~16s.

(A future derived-artifact cache — graph/index/dictionary/profiles by tree-SHA — would take warm
runs to ~2s and make a pure-bb warm-query front-end attractive.)
