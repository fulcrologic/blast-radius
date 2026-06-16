# Blast Radius — install & usage

Static change-impact candidate generator. Distributed as a **bbin-installed Babashka
launcher** that `exec`s a JVM with the right classpath: bb gives the easy install + fast CLI
startup; the JVM does the heavy build (native clj-kondo analysis + graph transforms), which is
several-fold faster than sci.

## Requirements

- `bb` (Babashka) — the launcher.
- `java` (a JVM) — runs the engine jar.
- `clj-kondo` native binary on PATH — the analysis engine.
- `git` — change detection + read-only tree materialization.
- `clojure` CLI — *optional*, only for the build-from-source fallback (if a release jar isn't available).

## Install (bbin)

```bash
bbin install https://raw.githubusercontent.com/fulcrologic/blast-radius/main/bin/blast-radius.bb --as blast-radius
```

That's it — **no clone, no env vars, no config.** On first run the launcher downloads the engine
jar from the GitHub Release into `~/.cache/blast-radius/<version>/` and caches it; every run after
is plain `java`. If the release asset is unavailable it automatically falls back to cloning the
repo at the release tag and building the jar (`clojure -T:build uber`).

### Optional overrides (none required)

- `$BLAST_RADIUS_JAR` — use this jar as-is (skip download/build).
- `$BLAST_RADIUS_HOME` — build the engine from this local checkout (for development).
- `$BLAST_RADIUS_JAR_URL` / `$BLAST_RADIUS_JAR_HEADER` — a custom/private asset URL + auth header.

## Build the engine jar (maintainers)

```bash
clojure -T:build uber        # -> target/blast-radius.jar (self-contained); attach to a GitHub Release
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
