# Blast Radius — Design Notes

> Status: **design draft**. Captured from a design conversation so the work can be
> picked up in a fresh session. Nothing is built yet. This document is the source of
> truth for *intent*; it deliberately states the *why* and the honest limits, not just
> the *how*.

## 1. One-line statement

A static, high-recall **change-impact candidate generator** for Clojure(Script)
codebases, paired with a per-candidate **LLM/human precision filter**. Given a code
change (a diff / a set of touched functions), it produces the set of downstream code
locations whose behavior *might* be affected — including locations coupled only through
**persisted or shared data**, not through direct function calls — and then asks, for
each candidate, "does this specific change actually break this specific consumer?"

The north star: **the goal of testing is to find failures.** This tool is a
failure-finder for PRs and large refactors, not a coverage prover.

## 2. The problem it solves

Existing call-graph / coverage analysis (including the transitive-coverage "proof"
system in `fulcro-spec`) captures **synchronous coupling**: A calls B. It is
*structurally blind* to **data-mediated coupling over time**:

- A function writes `:invoice/status` to Datomic.
- A completely unrelated function reads `:invoice/status` later.
- Neither appears in the other's call tree, yet they are coupled through the database.

A change to the writer can break the reader, and no caller-based tool can see it. In a
full-stack Fulcro/RAD app this coupling even spans **separate processes** (server
writer ↔ Pathom resolver ↔ CLJS component query), i.e. two runtimes with entirely
disjoint call graphs, stitched together by a single qualified keyword.

This is the dominant blind spot of call-graph impact analysis, and it is exactly where
most "I changed something innocuous and broke something far away" bugs live.

## 3. Core insight: qualified keywords are a global data schema

Clojure-centric systems (RAD, Datomic, Pathom, Fulcro client db, re-frame) represent
data that flows through I/O and shared state using **namespaced keywords**
(`:invoice/total`, `:person/id`). These keywords are *global names* — the same keyword
means the same attribute across namespaces, processes, and the wire. They form a de
facto schema.

Therefore: **a data dictionary of qualified keywords appearing at I/O / shared-state
boundaries**, plus the call graph, lets us approximate the data-flow-over-time
dependency graph that pure call analysis cannot reach.

## 4. The architectural move: split RECALL from PRECISION

This is the key design decision and it shapes everything else.

| Layer | Job | Property | Cost |
|-------|-----|----------|------|
| **Static analysis** | Generate candidates (the blast radius) | High recall, low precision, deterministic | Cheap |
| **LLM / human filter** | Decide if each candidate is *actually* affected | High precision, applied pairwise | Moderate, parallelizable |

The static layer is **deliberately over-approximate**. It is *supposed* to over-find.
Its only failure mode that matters is a **false negative** (see §6). The precision
problem is delegated entirely to a downstream model/human that answers a narrow,
grounded question for each candidate:

> "We made this change (here is the delta). It may affect this consumer (here is the
> code, and here is *why* we think it's connected). Does this actually cause a problem
> here?"

This split is what makes the whole thing automatable at scale: the static graph bounds
the candidate set to the blast radius (≪ whole codebase), and each pairwise question is
small, cheap, and embarrassingly parallel.

### Why over-approximation is now a feature

Earlier framings worried that co-occurrence (keyword K appears in the same function as
an I/O call, without proof the value *flows* to the sink) produces false edges. Under
the recall/precision split that is **fine and intended**:

- If we make an invoice (touching many `:invoice/*` keywords) and flow it through a
  3-layer call chain before something does an I/O **write**, we conservatively assume
  *all* those keywords were written.
- Touch *anything* in that chain ⇒ become suspicious of *all* those keywords ⇒
  over-find *all* downstream readers of any of them.
- The LLM filter collapses the over-approximation back down to real problems.

Over-finding is self-healing. Under-finding is silent and permanent.

## 5. Data / control flow of the system

```
                 ┌─────────────────────────┐
   git diff ───▶ │  Touched functions       │
                 │  + their source deltas    │
                 └───────────┬──────────────┘
                             │
            ┌────────────────▼─────────────────┐
            │  Static substrate (clj-kondo)     │
            │  - var-usages   → CALL edges      │
            │  - keyword-usages → DATA edges    │
            │  - I/O sink registry (curated)    │
            │  - keyword dictionary (curated)   │
            └────────────────┬─────────────────┘
                             │  forward reachability:
                             │  touched fns → reachable I/O WRITES
                             │            → readers of those keywords
                             ▼
            ┌──────────────────────────────────┐
            │  Candidate set (BLAST RADIUS)     │
            │  each candidate carries PROVENANCE│
            │  (connecting keyword(s), sink loc,│
            │   call path, delta)               │
            └────────────────┬─────────────────┘
                             │  ranked for triage (COMPLETE set; budget
                             │  paces the pass, never truncates §7)
                             ▼
            ┌──────────────────────────────────┐
            │  Precision filter (fan-out)       │
            │  N parallel LLM/human judges      │
            │  "does this delta break this?"    │
            └────────────────┬─────────────────┘
                             ▼
                   Ranked suspicions + explicit
                   recall-frontier disclosure
```

## 6. Recall is the deliverable — be ruthless about it

Because the LLM only ever sees candidates the static layer produced, **any consumer the
static graph fails to connect is never interrogated and its failure is never
surfaced** — while the tool *looks* like it checked the blast radius. That is the one
unrecoverable error.

Design imperatives:

1. **Aggressively over-connect.** When in doubt, include the edge.
2. **Publish the recall frontier.** Every run must state, explicitly, what classes of
   coupling it does *not* cover, e.g.:
   > "Interrogated everything reachable via call-edges ∪ keyword-co-occurrence-at-I/O.
   > NOT covered: dynamically constructed keywords, stringly-typed/EDN/config-driven
   > payloads, coupling through ordering/time/accumulated state, external systems."
   A failure-finder that hides its own blind spots manufactures false confidence and is
   worse than no tool.

### Known recall holes (the real enemy)

- Dynamic keywords: `(keyword ns n)`, `(keyword (str ...))`.
- Stringly-typed / external payloads: queue messages, external API JSON, EDN config.
- Coupling with no keyword naming it: ordering, time, accumulated mutable state.
- Calls that only appear *after* a custom macro expands (shared static-analysis limit).
- Dynamic / `reduce`-built / externally-sourced tx-data (the write map assembled by
  accumulation, pulled from another var, or handed opaquely to `d/transact`): the
  **write-side keyword set is under-recovered**, so readers of those keywords are
  *silently missed*. This is a genuine **false negative** on edge (B) — not over-finding
  the LLM cleans up (the filter only ever sees candidates that were generated; an
  un-generated write is never interrogated). Must be named per §6.2 (see §21.5).
- Dispatched entry points: functions reached only via runtime dispatch (job/SQS queue
  handlers, RAD control/action maps, multimethod or registry tables, the Pathom resolver
  registry) have no static caller edge, so **edge (A) under-recovers their callers** (§10).
- Missing I/O sink: any real I/O boundary absent from the curated `sinks.edn` is invisible
  as a source/sink ⇒ silent edge-(B) false negative (§13, §25 — single point of failure).

These must be detected-where-possible and *named* where not.

## 7. NAME EVERYTHING — cost is managed without shrinking the named set

**This is the governing invariant of the whole tool, and it overrides any optimization
below.** The static layer's deliverable is the **complete** blast radius: *every*
genuinely-coupled location, named. It **never** removes a coupled candidate on the grounds
that the candidate is *unlikely* to break. "Less likely" is not a reason to drop anything —
likelihood is the *precision layer's* job (§4), and that layer **adjudicates** candidates,
it does not decide which ones get **named**.

The flip side is real: touch a core util, or write a ubiquitous attribute like `:db/id`, and
the named set can become "the whole app." That makes the tool *expensive*, not *wrong*. Only
**two** things may ever shrink the named set, and neither is probabilistic:

1. **False-edge elimination (structural, correctness-preserving).** Not *generating* edges
   that aren't real coupling: supply/demand source-gating (§10), declaration-primary precise
   sets (§29), pure-reader pruning, project-namespace call scoping (§27.2), change
   normalization (§19). These remove **non-coupling** — they make the named set *correct*,
   and never drop a *true* coupling for being improbable.
2. **Declared trust-lists (explicit, user-owned, disclosed).** The user may name
   namespaces / keywords / vars asserted **unbreakable or out-of-scope** — e.g. "trust
   `dataico.lib.foo.*` as stable," "treat `:db/id` as uninteresting." The tool may **suggest
   candidates** (e.g. hub keywords ranked by fan-out, §27.2) but **never auto-applies** them;
   every trust-list exclusion is **printed on the recall frontier** (§6.2). This is a
   deliberate, auditable choice by the user, not a guess by the tool.

Everything else is *presentation*, not filtering:

- **Ranking = ORDERING only.** Order the (complete) named set so a reviewer sees the most
  specific couplings first. Signals: keyword **specificity** (IDF), keyword **signal-class**
  (§27.3 — used to *rank* and to *suggest trust-list candidates*, never to suppress), and
  whether the delta **produces/manages vs. reads/carries** the connecting keyword. Graph
  distance is **not** a signal (§27.3). Ranking re-orders; it does not remove.
- **Budget = PACING THE PRECISION PASS, not the named set.** A budget caps how many
  candidates the *LLM/human fan-out* (§5) adjudicates per run; the **named set in
  `blast-radius.edn` is always complete**. Un-adjudicated candidates remain **named and
  reported** ("named, not yet interrogated"), never dropped. There is no "drop the
  less-likely ones" anywhere in this tool.

Hub keywords (`:db/id`, `:tempid`, audit/status attrs read everywhere) are handled by
**ranking them low** and **surfacing them as trust-list candidates** — not by silent
exclusion. If the user trusts one, that is a declared, disclosed exclusion (lever 2).

## 8. No low-level keyword-role classification (decided against)

An earlier draft proposed syntactic **produce-vs-read** classification (map-key/`assoc`
position ⇒ produces; `get`/lookup ⇒ reads) as a "cheap, safe trim." **We do not do this.**
It is neither cheap nor safe:

- **Not cheap.** It requires re-reading every var's *form* (rewrite-clj) and walking zipper
  context per keyword occurrence — measured to blow past 120 s over ~32k vars (the cause of
  the `io.clj` perf liability and the `fast-symbol-io` workaround). clj-kondo already yields
  keyword *mentions* (file/row/enclosing-var) in one pass; the role walk is the expensive
  bolt-on.
- **Not safe.** "Produce vs read" is not `assoc` vs `get`. It is `assoc-in`/`update`/
  `merge`/`select-keys`/`cond->`/threading/keyword-as-fn/keywords-stored-in-vars/EQL
  patterns/project builders — an unbounded tail a syntactic classifier gets wrong silently,
  with errors not even biased toward recall.

Its three jobs are better served by mechanisms we already have:

1. **Which keywords a writer writes** — *declarations* give the exact produced set for
   declarative vars (§29, the dataico-dominant case); for non-declarative glue, the §4
   doctrine **over-approximates**: a var on a write path is assumed to produce *every
   keyword it mentions en route to the sink* (a free mention-set; the LLM filter + ranking
   collapse it). Role classification was pre-shrinking an over-approximation we deliberately
   tolerate.
2. **Source/writer determination** — *sink-reachability* (sinks.edn + call graph, §10/§13),
   never role-based.
3. **Delta-role ("produces/manages K")** — *source-membership* (§27.3): the change declares
   K as output, or is on a write path carrying K. A better signal than syntax — an
   `(assoc m :company/id …)` building a query scope is not "managing ownership"; reaching a
   transact that writes `:company/id` is.

The one free, robust signal we may keep: clj-kondo's **destructuring flag** marks
inputs/reads at zero cost (no reparse). Everything else is a *mention* — a candidate on
both sides, recall-first. Net: T3 consumes cheap mention-sets + sink matches; precision
comes from **declarations + sink-reachability + the LLM filter**, not from guessing a
keyword's purpose across the function zoo.

## 9. Edges carry PROVENANCE (this is what makes the filter good)

An edge is never just `A → B`. Every candidate records:

- the **connecting keyword(s)**,
- the **I/O sink** location (where the write happens),
- the **call path** from touched code to the sink and on to the reader,
- the **delta** (diff text) of the touched code.

This provenance is simultaneously the **LLM prompt context** and the **audit trail**.
Without it the pairwise question is ungrounded and the filter is unreliable.

## 10. Directionality

Every change propagates along **two independent edges**, and the *role* the changed
function plays in the (existing) analysis graph decides which apply:

- **(A) Call-staleness edge — callee → caller. ALWAYS applies.** Change F ⇒ every function
  that transitively *calls* F may be affected. This is the fulcro-spec staleness edge;
  scoped to project namespaces (§27.2), it is local and generally small.
- **(B) Data edge — source → reader. Applies ONLY if the changed function is a *source*.**
  A source is a function that is on a **write path** (forward-reaches an I/O write sink,
  collecting the produced keyword set along the path) **or** that **declares output** (a
  Pathom resolver, §22). Readers of any keyword in that source-set are the data blast —
  cross-time (DB) and cross-process (wire).

**Both walks are project-scoped (dry-run gap).** §27.2 establishes that the *backward*
caller walk must drop `clojure.core`/`cljs.core`/instrumentation to avoid meaningless
closures. The same scoping applies to the **forward** walk (reaching write sinks /
source detection): walk only project-namespace call edges, treating sinks as the leaves.
Without it, forward reachability explodes through core macros exactly as the backward walk
does.

**Dispatched entry points are an edge-(A) frontier hole (dry-run gap).** A function invoked
only via a *dispatch mechanism* — a job/SQS queue handler, a RAD control/action map, a
multimethod or registry table, the Pathom resolver registry — has **no static caller
edge**, so edge (A) under-recovers its true callers (the dispatcher is the "caller"). This
is the edge-(A) analogue of the resolver disconnection. Detect-where-possible (known
dispatch registries) and **name it on the recall frontier** (§6.2): "entry points reached
only via runtime dispatch — callers not statically recoverable."

The crucial consequence (and the single biggest prune): **a pure reader contributes only
edge (A) — its callers — and has NO data blast.** UI queries, pull/stub pattern vars,
attribute registries, and other read declarations mention many keywords but *source*
none; they get no fan-out to writers/readers, only their compositional callers. (This
subsumes the pattern-var phenomenon of §27.4 — it is just the pure-reader case.) We do
**not** statically label every function reader-or-writer; the role falls out of
forward-reachability-to-a-write plus the declared-source tag.

**"Source" unifies DB-writers and resolvers.** Anything that is a source of attribute K
for some downstream consumer propagates along (B) to K's consumers, whether that consumer
is a future DB *read* or a cross-process client *query*:

- A DB writer sources K for future DB readers (in-process call graph is irrelevant to the
  coupling).
- A **Pathom resolver** does DB *reads* but is a **source of its `::pco/output`**: edge (A)
  is nearly empty for it (the engine dispatches it via indexes, not a static call edge — the
  call graph is *disconnected* here, exactly §2/§3's blind spot), so edge (B) — output
  attributes → all client `df/load`/query readers, joined purely by the keyword across the
  wire (§15.4) — is the *entire* story. This is why the §22 plugin tags resolvers
  `:source? true`: that tag is what places them on edge (B).

**Roles are stable; what changes is graph structure.** A core coding standard applies: *an
existing named function must not change its behavior at its level of abstraction* (a date
fn does not suddenly read the DB). So a surviving function's role does not flip and there
is nothing to "union" — classify role from the codebase analysis graph (never by parsing
the diff; diff text is LLM context only, §9). What the diff actually does is make nodes and
edges **appear** or **disappear**, and the rule depends on **which side of the data flow**
the change sits on:

- **Supply side — a *producer/source* of attribute K appears, disappears, or is modified ⇒
  all three are significant.** Each perturbs the *supply* of K and therefore every consumer
  of K (edge B). A **deleted resolver** (K no longer resolvable for client `df/load`) or a
  **deleted writer** (K no longer written → readers go stale) is exactly as dangerous as a
  newly-added one. Seed edge (B) to all readers of K in every case — even if other producers
  of K survive (the deleted one may have handled a case they don't; recall-first).
- **Demand / call side — a *consumer* (reader) or a plain *call edge* disappears ⇒ ignore.**
  A vanished call edge is just the caller restructuring (already seeded by the changed
  caller, edge A); a vanished reader is dead demand that harms no one. A newly-*appeared*
  reader is reviewed as its own changed function but does not fan out.

So "ignore disappearance" holds **only on the demand/call side**; on the supply side,
disappearance is fully symmetric to appearance. This also **subsumes** the apparent
"role change" case: a function that newly adds a `d/transact` is a *new supply-side edge
appearing* (F → `d/transact`), caught structurally — the tool never depends on the coding
standard for correctness; even a (bad) role change surfaces as a supply-side appearance.

The staleness analogy to the proof system holds: a reader depends on its source exactly as
a caller depends on its callee. If integrating with a signature/staleness model, union the
call-edge dependency set (A) with the data-edge dependency set (B).

## 11. LLM-filter contract (honest limits)

1. **Local questions can have non-local answers.** "Does this break *here*?" may be
   unanswerable from two snippets when breakage manifests one hop further or through
   accumulated state. Mitigation: the graph is transitive — ask at **every node on the
   path**, not only leaves — and let the model **escalate** ("can't tell locally — widen
   context") instead of forcing yes/no.
2. **Treat "no" as *deprioritize*, not *proven safe*.** The model is a high-throughput
   probabilistic filter, not a prover. For a failure-*finder* (triage) this is correct
   and acceptable. Keep it firmly **out of any "proof"/soundness guarantees** — this is a
   complementary tool, not an extension of coverage soundness claims.

## 12. Static substrate: clj-kondo

One analysis pass yields both edge types:

- `--config '{:output {:analysis {:keywords true}}}'`
  - `:var-usages` → **call edges** (`:from` + `:from-var` → `:to` + `:name`, resolved,
    handles `.cljc` with clj/cljs split).
  - `:keyword-usages` (and `:keywords`) → **data edges** (qualified keyword occurrences
    with file/row; attribute to enclosing function).
- Use the **programmatic API** `clj-kondo.core/run!` — it returns
  `{:analysis {:var-definitions [...] :var-usages [...] ...}}`. Add clj-kondo as a
  dependency; do **not** shell out.
- clj-kondo understands core macros (`->`, `let`, etc.) and handles `.cljc`/clj-vs-cljs.

This is the same substrate proposed for replacing the guardrails-registry call-graph in
`fulcro-spec`'s proof system — so the two efforts share a foundation. The data-edge
analysis is purely additive: a second edge type plus an LLM fan-out stage on top of the
same analysis.

## 13. Curated inputs (data files, not code)

- **I/O sink registry.** A relatively small, enumerable set of boundary functions that do
  real I/O, each tagged read / write (or both). **Keys are FULLY-QUALIFIED symbols, never
  aliases** (`datomic.client.api/q`, not `d/q`): aliases (`d`, `dold`, …) are unstable and
  ambiguous across files. Matching is alias-independent because clj-kondo resolves every call
  site's namespace, so the analysis matches on the resolved `:to` symbol (`d/q`, `dold/q`,
  `datomic.client.api/q` all collapse to the FQ form). **The registry MUST include the
  project's own FQ wrappers, not just upstream symbols** — verified on dataico, where the
  dominant sinks are wrappers: `dataico.lib.datomic.api/q` (892×) and `…utils/pull` (585×)
  and `…transaction-auditing/transact` (359×) vastly outnumber the direct
  `datomic.client.api/*` calls (~10–41× each). A library-symbols-only registry would flag
  only the direct handful and recover the wrappers only via fragile transitive reach. FQ
  identity also disambiguates real sinks from false positives (e.g.
  `com.fulcrologic.fulcro.ui-state-machines/transact` is **not** a DB write). Build/extend
  the registry by tallying var-usage callee `:name` by resolved `:to` (see `resources/sinks.edn`).
  Covers Datomic (peer + client + project facade), JDBC, HTTP clients, queue publish/consume,
  file I/O, etc. Curated, extensible.
  **Coverage is conditional on `sinks.edn` being complete for the project's stack.** A
  write/`:source?` sink missing from the registry is a **silent recall hole** (a single
  point of recall failure): that function is never seen as a source, edge (B) never fires
  from it, and its readers are never interrogated — with *no in-band disclosure*. An
  omitted sink is therefore a silent false negative; the run must declare the assumed
  stack on the recall frontier (§25).
- **Keyword dictionary.** The set of qualified keywords that "matter." Built by static
  extraction of *all* qualified keywords, then pruned by the user. Optionally
  auto-seeded from declared sources when present:
  - Pathom resolver `::pco/input` / `::pco/output` (an authoritative, *directional*
    attribute graph — Pathom already computes this at runtime; could be read directly),
  - RAD attributes (`defattr`, `ao/identities`, `ao/target`),
  - Datomic schema (`:db/ident`, ref types).
  Provenance is a *convenience for populating/pruning the dictionary*, **not** on the
  critical path — the co-occurrence engine works without it. (Decided: declarations are
  an add-on, not the foundation.)

## 14. Relationship to the fulcro-spec proof system

- **Shared:** clj-kondo static substrate; the notion of a transitive dependency set;
  signature-delta as a change trigger.
- **Different:** the proof system *proves coverage* (soundness — must not report a false
  "complete"). Blast Radius *finds failures* (recall — must not silently drop a
  candidate). Different guarantees, complementary tools. Keep Blast Radius's
  probabilistic LLM verdicts out of the proof system's soundness claims.
- This is intentionally a **separate project** (`../blast-radius`), not a feature of
  fulcro-spec.

## 15. Open questions / next-session TODO

1. **Trigger granularity.** Function-level (signature delta) vs. line/diff-hunk level.
   Probably function-level to start, with the diff text attached for the LLM.
2. **Output-set computation.** Exact algorithm for "keywords produced along a path to a
   write." Forward propagation over call edges, unioning **mention-sets** of vars on the
   write path (no role classification, §8); declarations (§29) supply the precise set where
   available.
3. **Ranking function.** Concrete scoring: keyword specificity (IDF over the dictionary),
   keyword signal-class (§27.3), produce-vs-consume of the delta site. (No graph
   distance — §7.1/§27.3.)
4. **Cross-process stitching.** ✅ Resolved — see §28. One combined clj-kondo run (the
   keyword join is automatic; `:lang` is provenance, not the join); only the data edge (B)
   crosses the boundary; "cross-process" generalizes to one global attribute
   producer/consumer graph. Open sub-item: attribute *aliasing* across a resolver
   (rename) is recoverable only via live `index-oir` (§28.7).
5. **Fan-out harness.** Likely a workflow: blast-radius node set → N parallel judges →
   ranked suspicions. Budget paces this precision pass (how many candidates get adjudicated);
   it never shrinks the named set (§7) — un-adjudicated candidates stay reported.
6. **Prompt design** for the pairwise judge, including the escalation path (§11.1).
7. **Recall-frontier reporting** format — make the uncovered-coupling classes a
   first-class, always-printed part of the output.
8. **Macro hooks.** Whether to configure clj-kondo hooks for project-specific macros to
   recover call/keyword edges hidden inside macro expansions.
9. **Incremental analysis / caching.** clj-kondo has a `.clj-kondo` cache; decide how to
   keep the graph fresh per-PR cheaply.
10. **Validation strategy.** How do we measure recall (the thing that matters)? Seed
    known data-coupled bug pairs and confirm they appear in the candidate set.

## 16. Summary of decisions already made

- Build as a **new, separate project** in `../blast-radius`.
- **Recall/precision split** is the core architecture; static layer over-approximates
  on purpose.
- **clj-kondo** (`clj-kondo.core/run!`) is the static substrate, providing both call and
  keyword edges; no shelling out.
- **Provenance on every edge** is mandatory.
- **Recall frontier must be disclosed** on every run; false negatives are the only
  unrecoverable error.
- **Name everything (§7).** The named set is the complete blast radius; it is never
  shrunk by probabilistic "less-likely" dropping. Cost is managed by structural false-edge
  elimination + **explicit, disclosed, user-declared trust-lists** only; ranking *orders*
  and budget *paces the precision pass* — neither removes a candidate.
- Declared sources (Pathom/RAD/Datomic) are an **optional dictionary seed**, not the
  foundation.
- The LLM filter's verdicts are **triage**, never **proof**.

---

# Part II — Tools / Build Plan

> Part I states *intent*. Part II is the concrete decomposition into buildable tools,
> their data contracts, and worked-out algorithms for the two hard problems
> (read/write inference, §21; declaration plugins, §22). The six tools below are the
> implementation of the pipeline in §5; nothing here changes Part I's guarantees.

## 17. Pipeline & artifact model

Everything is a **pure transform over two inputs** — a clj-kondo analysis and the git
tree — emitting **EDN artifacts on disk**. EDN-on-disk (not one in-memory pass) is
deliberate: it satisfies tool #1's "static file that can be read in" requirement, makes
every stage independently cacheable per-PR (§15.9), and lets the LLM fan-out (§5)
consume a frozen, inspectable candidate set.

```
   sinks.edn (curated)  ─────────────────────────────┐
   dictionary.edn (T1, static + optional live seed) ──┤
                                                       ▼
 source tree ─▶ clj-kondo run! ─▶ analysis.edn ─▶ symbol-io.edn (T3, direct/syntactic)
                                       │                 │
                                       │                 ▼
                                       │           inferred-io.edn (T4, neighbor inference)
                                       │                 │
   declared-io.edn (T5, plugin overrides) ───────────────┤
                                       │                 ▼
                                       │           effective-io.edn (§24 merge)
                                       │                 │
 git refs ─▶ changed.edn (T2) ─────────┴─────────────────┴─▶ blast-radius.edn (T6)
                                                                     │
                                                                     ▼
                                                         LLM/human precision fan-out (§5)
```

Shared entity shapes (every artifact keys on these):

```clojure
;; a var (fully-qualified symbol is the global key)
{:sym 'com.example.invoice/create  :ns 'com.example.invoice  :name 'create
 :file "src/com/example/invoice.clj" :row 12 :end-row 40 :lang :clj   ; or :cljs
 :arglists '([db params])}

;; a call edge (from clj-kondo :var-usages, resolved)
{:from 'com.example.invoice/create  :to 'datomic.api/transact}

;; per-var I/O profile (the unit T3/T4/T5 all produce, merged in §24)
{:sym 'com.example.invoice/create
 :inputs  #{:invoice/id}                       ; expected: destructured from args
 :outputs #{:invoice/total :invoice/status}    ; produced: map-key / assoc position
 :reads   #{:invoice/id}                        ; consumed: bare lookup / get / destructure
 :writes  #{:invoice/total :invoice/status}     ; reaches a write sink carrying these
 :io      {:read? false :write? true :sinks ['datomic.api/transact]}
 :source? false                                 ; returns read-sink data (T4 tier 0)
 :provenance {:invoice/total #{:syntactic} :invoice/status #{:declared}}}
```

`inputs/outputs` are the **declared/syntactic schema view** (what the fn names);
`reads/writes` are the **I/O-coupling view** (what crosses a sink). They diverge — a fn
can produce `:invoice/total` into a map it never persists (output, not a write).

## 18. Tool 1 — Keyword extractor (dictionary builder)

Implements §13's keyword dictionary. **Two modes, both emit `dictionary.edn`:**

**Static mode (universal, the foundation).** One `clj-kondo.core/run!` with
`{:output {:analysis {:keywords true}}}` → `:keyword-usages` + `:keywords`. Collect every
**qualified** keyword with file/row, attribute each occurrence to its enclosing var
(by row ∈ [var :row, :end-row]), and tally. Output:

```clojure
{:keywords {:invoice/total {:count 47 :files [...] :vars #{...}
                            :sources #{:static}        ; provenance of the *definition*
                            :direction nil}}            ; static can't know direction
 :idf {:invoice/total 4.2  :db/id 0.1}                  ; -log(df/N); hub kws → ~0
 :hub-keywords #{:db/id :tempid}}                       ; auto-flagged by low IDF + manual
```

`:idf` and `:hub-keywords` feed **ranking** (order the named set, §7) and the **suggested
trust-list** (hubs the user *may* explicitly exclude). They are **not** an auto-applied
filter — per §7, nothing is dropped except by the user's declared trust-list, disclosed.
Computed here so T6 can rank without re-scanning.

**Live mode (optional directional seed, callable in the target app).** The target app
exposes a function (e.g. a RAD app ships `blast-radius.extract/dump-dictionary`) that
reads the *authoritative, directional* registries present at runtime and merges them in:

| Source | Where | Yields |
|--------|-------|--------|
| RAD attributes | `@…rad.attributes/*all-attributes*` | qualified-key, `ao/target`, `ao/identities`, type |
| Pathom 3 index | built `env` → `::pci/index-io`, `::pci/index-oir` | full **input→output** attribute graph |
| Pathom 2 index | `::ps/index-io` | same, pathom2 |
| Datomic schema | `(datomic.api/q '[:find ?i :where [_ :db/ident ?i]] db)` + ref attrs | schema idents, ref targets |

Live mode upgrades `:sources` (e.g. `#{:static :pathom-output}`) and fills `:direction`
(`:in`/`:out`/`:both`). The static set is a superset; live mode identifies which keywords
are *real schema attributes* — informing **ranking** and the **declared keyword universe**
(the user-chosen RAD-attribute scope, §13), not a probabilistic prune — and is the only way
to get **direction for free**. Per §13: a convenience for populating the dictionary, never
on the critical path.

## 19. Tool 2 — Commit → changed symbols

Implements the "touched functions" box (§5) and answers §15.1 (function-level trigger).
Reuses fulcro-spec's `signature.clj` approach: a function is "changed" iff its
**normalized** source (docstring stripped, whitespace collapsed, then hashed) differs —
so reformatting and doc edits don't trigger blast radius.

Algorithm, between two git refs (or `COMMIT` vs `COMMIT^`):

1. `git diff --name-only A B` → changed `.clj[cs]` files; `git show A:path` / `B:path` to
   materialize both versions.
2. clj-kondo (or positional rewrite-clj parse) each version → `:var-definitions` with
   `:row`/`:end-row`. Map each diff hunk's line range to its **enclosing var**.
3. For each candidate var in *both* versions: normalize each side and compare. Added /
   removed vars are changed by definition. Whitespace/doc-only deltas drop out here.
4. Emit `changed.edn`, **with the diff text attached** (§9, §11 need it as LLM context):

```clojure
[{:sym 'com.example.invoice/create :file "…/invoice.clj"
  :line-range [12 40] :status :modified
  :old-sig "a1b2c3" :new-sig "f4e5d6"
  :diff "@@ -18,3 +18,4 @@ …"}]
```

**Vendor, don't depend.** `normalize-content` (~150 lines, stable) should be copied into
blast-radius, not pulled as a dependency on a *test* library. Note the shared-substrate
intent (§14) in a comment. **Scope note:** Tool 2 emits only the *directly* changed set;
**transitive** expansion (a caller is stale because its callee changed) is Tool 6's job —
it falls out of call-edge reachability for free, so we don't duplicate the leaf/non-leaf
transitive-hash machinery here.

**Graph delta, not just signature delta (§10).** Because roles are stable and what matters
is structure, T2 also diffs the *analysis graphs* of the two trees and emits the structural
delta alongside the changed-symbol set:

- **appeared** call-edges and keyword-productions ⇒ first-class new seeds (a function that
  newly calls a write sink, or newly produces an attribute, is a new contribution even if
  its own normalized signature barely moved).
- **disappeared *productions / source-edges*** ⇒ first-class seeds too — the *supply* of
  that attribute changed (deleted resolver/writer), so readers of it must be checked
  (§10 supply side). NOT dropped.
- **disappeared *call-edges* / *reader-usages*** ⇒ dropped (caller restructuring / dead
  demand; §10 demand side).

```clojure
[{:sym 'com.example.invoice/create :file "…" :line-range [12 40] :status :modified
  :old-sig "a1b2c3" :new-sig "f4e5d6" :diff "@@ …"
  :appeared {:call-edges #{'datomic.api/transact} :produces #{:invoice/status}}
  :disappeared {:call-edges #{} :produces #{}}}]
```

Appeared `:produces`/write-edges are what promote a function onto data-edge (B) for this
run; disappeared ones are ignored.

## 20. Tool 3 — Per-symbol direct I/O (mention-sets + sinks, no role walk)

The cheap per-var pass, built **only** from clj-kondo's one-pass output — **no rewrite-clj
re-parse, no syntactic role classification** (§8). Produces the per-var profile (§17):

- **mentions**: the set of qualified keywords the var references — taken directly from
  clj-kondo keyword occurrences (attributed by row-range, see below). This is the unit; it
  feeds both the producer side (when the var is a source) and the consumer side, recall-first.
- **inputs** (optional, free): keywords flagged by clj-kondo's **destructuring** marker —
  the one zero-cost read/input signal worth keeping. Everything else stays a plain mention.
- **io / sinks**: match the var's `:var-usages` against `sinks.edn` (fully-qualified, §13).
  A direct call to a `:write` sink sets `:write?`; a `:read`/`:source?` sink sets
  `:read?`/`:source?`. (Transitive reach to sinks is T6's forward walk, not here.)

We deliberately do **not** compute `:outputs` vs `:reads` by `assoc`-vs-`get` position (§8):
it is expensive (per-var form re-parse) and unreliable across the function zoo. Instead,
*which* of a writer's mentions count as produced is resolved by **declarations** (§29,
precise) where available, and otherwise by the §4 over-approximation (a source produces all
mentions en route to its sink). T3 therefore needs only clj-kondo's analysis — markedly
cheaper, and it removes the pipeline's largest perf liability.

Keyword occurrences are **attributed by enclosing-var *row-range*, not `:from-var`** (below).

**Attribute by enclosing-var *row-range*, not `:from-var`.** ⚠️ **Empirical correction
(prototype, clj-kondo v2026.05.25):** an earlier draft claimed `:from-var` is set only
inside `defn` bodies, so top-level pattern/registry `def`s are dropped. **That premise is
false** — this clj-kondo version *does* populate `:from-var` for top-level `def`/`defn`, and
on the dataico tree the two indices agree on ~60.4k of 60.5k `(kw,var)` pairs (the real
cascade's accounting pull-pattern `def` carries `:from-var` and would *not* be dropped).
Row-range is still the chosen attribution, for honest reasons: (1) **version-independent** —
no bet on clj-kondo populating `:from-var`; (2) recovers the ~84 occurrences clj-kondo
leaves unattributed inside tracked vars (`defrecord`/`deftype` bodies); (3) surfaces the
~14.4k **orphan** occurrences (in `ns` forms, `defmethod`, top-level reg calls) explicitly —
kept at file level in `:kw->files` and reported, not silently lost. Implemented in
`blast-radius.keyword-index`; the prototype builds the full index in ~0.5 s.

`sinks.edn` is the curated registry of §13:

```clojure
{datomic.api/transact   {:io #{:write}}
 datomic.api/q          {:io #{:read}  :source? true}        ; result = our attribute schema
 datomic.api/pull       {:io #{:read}  :source? true :pattern-arg 1}
 datomic.api/datoms     {:io #{:read}  :source? true}
 next.jdbc/execute!     {:io #{:read :write} :source? true}  ; next.jdbc returns :table/column qualified keys (determinate, local)
 org.httpkit.client/post{:io #{:write}}                      ; outbound; RESPONSE is remote-schema → NOT :source?, it is §6 frontier
 …}
```

**`:source? true` ⇔ the return value carries read data in a *determinate, locally-defined*
qualified-keyword schema**, so §21 read-inference (recover the read-set from how consumers
destructure the result) yields keywords that connect to *our* writers. Precise rules:

- **Set it** for reads of stores whose result keywords are our own schema: Datomic
  (`:invoice/total` …) and next.jdbc (`:table/column` qualified keys — determinate, local).
  Every such read is a source; omitting one (as the old example did for `execute!`) is the
  inconsistency, not a distinction.
- **Do NOT set it** when the result shape is **remote/server-defined or stringly-typed** —
  HTTP responses, queue payloads, external JSON. Recovering "reads" from those produces
  false edges into a foreign schema; that coupling is the §6 recall **frontier**, named not
  modeled.
- **Never on an outbound write.** `:source?` describes the *result*, not the direction.
  A `POST` is `:write`; its response read-back is remote-schema (frontier), so `POST` carries
  no `:source?`. (`:io` and `:source?` are independent axes: direction vs. result-is-our-schema.)

`:pattern-arg` documents the opaque-pattern position (§ Part I.10) for the recall-frontier
report; we do **not** try to parse it.

## 21. Tool 4 — Inference of reads/writes from neighbors  (the hard problem)

**The problem (Part I, §10 + intro).** A read sink's pattern is opaque:
`(d/pull db pattern eid)` may read anything, and `pattern` can live in another var or be
built dynamically. Static analysis cannot know the read-set from the *call*. **But a
consumer can only destructure/access keys that were actually in the result** — so the
shape *consumed downstream* recovers a lower bound on what was read. Symmetrically, a write
sink's tx-data is opaque, but the keys *produced upstream* and fed into it recover what was
written. T4 turns the **call graph into a data-shape oracle.**

This is recall-first and **over-matches by construction** — that is correct under the
recall/precision split (§4). The LLM filter collapses it.

### 21.1 Source / sink identification (tier 0)

A var **F is a data SOURCE** when either:
- it calls a sink with `:source? true` and the result plausibly flows to F's return value
  (heuristic: F returns it directly, or as the tail of `->`/`->>`/`let`/`when-let`).
  *Recall-first default:* if F calls any `:source?` sink and we **can't prove the result is
  discarded**, mark F a source; or
- a declaration plugin (§22) tags it — e.g. a Pathom resolver **is** a source of its
  `::pco/output`.

A var **W is a data WRITE** when it (transitively, via T6 reach) feeds a `:write` sink.

### 21.2 Read inference — tiers

> Goal: assign each SOURCE F an **inferred read-set** = the keywords its consumers pull off
> its result. This read-set is what couples F to *writers* of those same keywords.

**Tier 1 — caller attribution (cheap, heavy over-match).**
For each source F and each direct caller C: attribute **every keyword in `reads(C)`**
(T3's read-set of C) as an inferred read of F. Over-matches when C reads keywords from
*other* sources too — accepted (recall). Provenance: `{:inferred-via C :proven-flow false}`.

**Tier 2 — local binding-flow refinement (precision trim, optional).**
Within C, do *one-level* local dataflow with rewrite-clj: find the local that holds F's
result — `(let [x (F …)] …)`, `(when-let [x (F …)])`, threading position — then attribute
only keyword-reads applied to **that local or its aliases**: `(:invoice/total x)`,
`(get x …)`, `{:invoice/keys [...]}` destructure of `x`. No SSA; just track which locals
alias F's result.
**Escape rule (recall guard):** if the local is passed to another fn, `assoc`'d into a map,
or returned, the binding **escapes** → fall back to Tier 1 over-match for the escapee, and
follow it forward (the receiver becomes a new consumer to attribute). Never let an escape
silently drop keys.

### 21.3 Write inference — symmetric

For a write W feeding sink `S`: the **inferred write-set** = keywords *produced* (T3
`outputs`) that flow into S's data argument.
- Tier 1: union `outputs` of W and of W's transitive callers that supply the argument.
- Tier 2: binding-flow from the produced map literal to S's arg position; escape rule same.

### 21.4 The data edge that results

```
   writer W  ──writes K──▶   reader R        for every K ∈ writes(W) ∩ reads(R)
```

This is the §10 data edge, now *populated* with inferred sets. Forward reachability for a
change (§5, §10):

```
changed sym ─call edges→ reachable WRITES ─inferred write-set→ K  ─readers-of-K→ blast radius
```

### 21.5 Honest recall holes specific to T4 (must be printed, §6.2)

- Pattern fully dynamic **and** result fully escapes through an untracked path **and** no
  in-repo consumer → the read is invisible (cross-process consumer, e.g. a CLJS query, is
  recovered only after cross-process stitching, §15.4).
- A read whose value is used only in a predicate/side-effect (never destructured by key)
  leaves no keyword fingerprint.
- Tx-data assembled entirely by a dynamic/`reduce`-built map (or sourced from another var /
  handed opaquely to the sink) → write-set under-recovered. The produce-signal union
  recovers only a **lower bound** on writes; fully-dynamic tx-data is on the **recall
  frontier**, *not* self-healing over-approximation. This is the **silent** failure kind
  of §4/§7 (under-finding, not over-finding), so it **must be disclosed** (§6.2) — never
  treated as slack the LLM precision filter can collapse (the filter never sees a write
  that was never generated).

## 22. Tool 5 — Declaration plugins (authoritative overrides)

For macros that **declare** their I/O (Pathom `::pco/input`/`::pco/output`, RAD `defattr`),
the schema is *stated*, not inferred. A **multimethod keyed on the fully-qualified symbol
in call position** lets us read it directly and lets *projects extend it for their own
macros* — exactly the §ask: "the fully-qualified symbol of the macro can be given an
analysis function that takes the form as data and returns the necessary info."

```clojure
(defmulti analyze-form
  "Dispatch on the fully-qualified symbol heading a top-level form.
   `form` is the raw s-expression (re-read by {:file :row} with rewrite-clj/tools.reader,
   *ns* bound so ::aliased keywords resolve — à la fulcro-spec get-source).
   `ctx` carries {:ns-aliases … :dictionary … :sym}.
   Returns an I/O profile (§17) to MERGE OVER inference, or nil to fall through."
  (fn [form _ctx] (resolve-fq (first form) ctx)))

(defmethod analyze-form :default [_ _] nil)   ; fall through to T3/T4
```

**The recognized macro set is PROJECT-CONFIGURABLE (cascade experiment).** Apps wrap the
stock macros: dataico defines its own `dataico.lib.pathom-wrappers/defresolver` /
`defmutation` (591 vars). Matching only built-in `com.wsscode…`/RAD symbols under-covers
badly. Dispatch must key on a **configurable set of fully-qualified macro symbols** (and,
where feasible, follow macro-aliasing) so project wrappers are recognized.

The form comes from re-reading source by position (not a clj-kondo hook): it keeps the
plugin a plain `form→data` function, which is what makes it trivially project-extensible.
(clj-kondo macro **hooks** remain the separate lever of §15.8 — for *recovering call/keyword
edges hidden inside expansions* — orthogonal to this.)

**Shipped plugins** (built-in, override generic analysis):

```clojure
;; Pathom 3 / 2 resolver: input vector + output EQL, and it's a SOURCE of its outputs
(defmethod analyze-form 'com.wsscode.pathom3.connect.operation/defresolver [form ctx]
  (let [{:keys [::pco/input ::pco/output]} (parse-resolver-config form)]
    {:sym (resolver-sym form)
     :inputs  (set input)
     :outputs (eql->keywords output)          ; walk EQL, collect all attrs (joins incl.)
     :source? true                            ; resolver produces ::output ⇒ data source
     :io {:read? true :write? false}}))        ; resolvers read to satisfy output

;; RAD attribute: registers the attr keyword + ref target; direction-neutral dictionary seed
(defmethod analyze-form 'com.fulcrologic.rad.attributes/defattr [form ctx]
  (let [{:keys [k target identities type]} (parse-defattr form)]
    {:declares-keyword k :ref-target target :identities identities :type type}))

;; Fulcro mutation: params destructure = inputs; ::result merge = outputs (optional)
(defmethod analyze-form 'com.fulcrologic.fulcro.mutations/defmutation [form ctx] …)
```

When `analyze-form` returns non-nil, its keywords land in `declared-io.edn` with
provenance `:declared` and take precedence on **direction** (§24) — but, per recall-first,
declared sets are **unioned** with inference, never used to *subtract*.

Worked Pathom example (why this is high-value): a resolver
`::pco/input [:invoice/id] ::pco/output [:invoice/total {:invoice/line-items [...]}]`
declares — authoritatively, no inference — that touching anything producing
`:invoice/total` or `:invoice/line-items` should suspect everything *requesting* this
resolver's output across the **whole Pathom graph**, including the CLJS client query on the
far side of the wire (§15.4). Pathom's own `index-oir` (live mode, §18) makes this a graph
lookup rather than a guess.

### 22.1 Dispatch-table plugins — recovering edges the call graph can't see (validated)

A second plugin kind emits **synthetic CALL/DATA edges** (not just an I/O profile) from a
**declared dispatch/registry table**. This is the most valuable §22 case: it converts the
§10/§6 *dispatched-entry-point* frontier hole into **covered edges** wherever the project
*wrote the dispatch down*. Both dataico experiments confirmed it:

- **CUD side-effect table** — `dataico.data-model.base/entities` (base.clj:295) is a literal
  map `{:invoice/id {… :side-effects {:create/:save/:delete [fq-sym …]}} …}`. Runtime
  dispatch is `keyword->fn` (`require`+`resolve`) → `(f db {:entity doc})`, so clj-kondo
  sees **no** runner→side-effect edge. A plugin reads `entities` as data and emits synthetic
  edges `(CUD T verb) → each declared symbol`. With those edges, `add-analytics`'s read of
  invoice items and **literal** write of `:doc.analytics/total` is on the graph, and the
  derived-data cascade (§23 step 4) to `accounting.automatic/*` is recoverable end-to-end.
  **Prototyped & verified** in `blast-radius.plugins.cud-side-effects` (rewrite-clj, no eval,
  hardened against `::aliased` keys): reads `entities`, emits **363 synthetic edges** across
  **34 doc types / 113 side-effect symbols**; `add-analytics` recovered for 10 doc types ×
  {create,save}; the keyword index then shows `:doc.analytics/total` read by 135 vars / 77
  files incl. the accounting pull-pattern — cascade confirmed end-to-end.
- **RAD `ro/refinements`** — a report option listing literal FQ symbols dispatched via
  `resolve-var`; same shape, same recovery.

**Principle (promote dispatch-recovery over disclosure):** a runtime-dispatched edge is a
frontier hole **only if undeclared**. When a literal declaration names the targets (symbol
tables, registries, RAD control/refinement maps, the Pathom resolver registry), prefer a
§22 dispatch-plugin that recovers the edges; disclose on the frontier only the genuinely
opaque remainder. So `analyze-form` may return, besides an I/O profile, a set of synthetic
`{:from … :to …}` edges to union into the call graph.

**Residual frontier even with the plugin (cascade/CUD experiments):**
- symbols resolved at runtime *outside* the declared table;
- **keyword-named function references inside arg-maps** (`{:total-fn :inv.model/total}` — a
  keyword that `resolve`s to a fn) — an edge hidden as a value;
- **ordering coupling** between side-effects (`coalesce-datoms` merges in declared order) —
  a §6 ordering/accumulated-state hole no edge names;
- hub fan-out of a side-effect declared by many doc types (cost, §7, not recall).

## 23. Tool 6 — Blast-radius generator (orchestrator + review feed)

Ties T1–T5 together and emits the candidate set the §5 fan-out consumes.

Algorithm:

1. Load `changed.edn` (T2), `effective-io.edn` (§24), call edges + `dictionary.edn` (T1).
2. **Transitive change closure:** expand changed syms by call-edge reachability
   (caller stale when callee changed — the transitive piece T2 deferred).
3. **Forward reach to writes (§10, §21.4):** from each changed sym, follow call edges to
   every reachable write; union the inferred **write-set** K along each path.
4. **Readers of K — TRANSITIVELY (least fixpoint over call ∪ data edges).** Every var with
   `K ∈ reads`/inferred-reads is a candidate. **Crucially, a reached reader that is *itself a
   source* (re-derives and writes K′ — e.g. a calc that reads `:invoice/items` and writes the
   cached `:document/total`) feeds K′ back into the perturbed-attribute set, and the walk
   continues.** The blast radius is the *least fixpoint* of `seed ↦ (call-callers ∪
   data-readers-of-perturbed-K)` where reached sources contribute their produced keywords.
   This is the **derived-data cascade** (`:invoice/items → :doc.analytics/total →
   accounting`, §10 — **validated on real dataico code**, §22.1 / experiments: HOP0 readers
   of `:invoice/items` re-derive the cached total, HOP1 reaches the accounting pull-pattern;
   the naive one-hop algorithm stops at the derivation node and misses accounting), a
   multi-hop data chain that a single read-hop misses entirely. Termination: **pure
   readers re-derive nothing and so terminate**; depth is bounded by the number of
   derivation/cache layers, not by fan-out. The data edge needs **no call edge** — a
   queue/trigger-dispatched derivation still couples by keyword (dispatched-entry-point holes
   are edge-(A) only). **Frontier:** if the cache-copy writes its target by a
   computed/config-driven keyword (`(assoc doc cache-key v)`, trigger table), the production
   of that attribute is under-recovered and the cascade is silently severed there
   (dynamic-keyword ∩ dynamic-tx-data, §6/§21.5).
5. **Attach provenance** to every candidate (§9): connecting keyword(s), sink location,
   call path changed→write and write→reader (well, K→reader), and the change's diff text.
6. **Rank — ORDER ONLY, never drop (§7).** Sort the complete candidate set by keyword
   specificity (T1 `:idf`), keyword signal-class (§27.3), and delta-role (produces/manages
   vs. reads/carries). Hub/identity/ownership couplings sort *low* but are **still emitted**.
   **No graph distance** (§7/§27.3). Nothing is removed by ranking.
7. **Apply the declared trust-list (the only removal, §7 lever 2):** drop candidates whose
   connecting keyword / target var / namespace the user has explicitly trust-listed, and
   **record each on the recall frontier**. With no trust-list, the named set is complete.
8. **Emit** `blast-radius.edn` — the **complete named set**. Budget is *not* applied here;
   it paces the downstream §5 precision fan-out, which marks candidates adjudicated vs.
   "named, not yet interrogated" (none are dropped from this file):

```clojure
{:run {:refs ["A" "B"]
       :named-count 412         ; size of the COMPLETE blast radius (every coupled candidate)
       :trust-list-excluded 9   ; candidates removed ONLY by the user's explicit trust-list (§7 lever 2)
       :recall-frontier ["dynamic keywords: 3 sites (file:line …)"
                         "stringly-typed payloads: queue publish at …"
                         "pattern-arg opaque + no consumer: 1 source at …"
                         "coverage assumes sinks.edn complete for: Datomic / JDBC / HTTP / queue / file I/O"
                         "trust-list excluded 9 (declared): :db/id, dataico.lib.audit/* (§7)"]}
 :candidates    ; COMPLETE — ranked for triage, never truncated
 [{:change {:sym 'com.example.invoice/create :file "…" :line-range [12 40] :diff "@@ …"}
   :affects
   [{:target-sym 'com.example.report/aging   :file "…" :line-range [88 120]
     :via-keywords #{:invoice/status}
     :sink {:sym 'datomic.api/transact :file "…" :row 31}
     :call-path ['…/create '…/persist! 'datomic.api/transact]
     :rank 0.81  :rank-why {:idf 4.2 :class :specific :delta-role :produce}}]}]}
```

`:recall-frontier` is **always present and non-empty** (§6.2): T1/T3/T4 each contribute
the blind spots they detected (dynamic keywords, opaque patterns with no consumer,
stringly-typed sinks). A run that found nothing still prints what it *couldn't* look at.
This file is the direct input to the §5 LLM/human fan-out (likely a workflow, §15.5).

## 24. Effective-I/O merge & provenance precedence

`effective-io.edn` unions the three profile sources per var. **Union for keyword sets
(recall — never subtract); precedence only for direction/role:**

```
keyword membership:  declared ∪ inferred ∪ syntactic        (recall-first union)
direction (:in/:out): declared  >  inferred  >  syntactic    (authority wins)
:source? / :io:       declared  >  inferred(T4 tier0) > syntactic(T3 sink match)
```

Every keyword keeps a provenance set (`#{:declared :inferred :syntactic}`) so the LLM
prompt (§9, §11) can say *why* the edge exists and weight a `:declared` edge above a
`:inferred`-via-over-match edge.

## 25. Recall frontier — per-tool blind spots (the always-printed contract)

Mapping §6.2's mandate onto the tools, so each names what it cannot see:

| Tool | Detects-and-names | Silent hole it must disclose |
|------|-------------------|------------------------------|
| T1   | `(keyword …)` dynamic construction sites | keywords built from runtime strings |
| T2   | added/removed/renamed vars | a change inside a macro that expands to the edit |
| T3   | sink calls with opaque `:pattern-arg` | role-ambiguous keyword kept as both (over, not under) |
| T4   | source with opaque pattern **and** no in-repo consumer | cross-process consumer (pre-stitch); predicate-only reads |
| T5   | macros with no registered plugin | declared I/O in an unknown macro |
| sink registry | known sinks in `sinks.edn` (§13) | **any unlisted I/O boundary** — an omitted write/`:source?` sink is invisible as a source, so edge (B) never fires (single point of recall failure) |
| T6   | hub-keyword fan-outs (cost, not recall) | anything none of T1–T5 connected |

The run's recall-frontier MUST **always print a meta line** declaring the stack assumed
complete, e.g. `"Coverage assumes sinks.edn is complete for: Datomic / JDBC / HTTP /
queue / file I/O."` (enumerate the actual registered families). Optionally, flag
suspicious unrecognized calls as **candidates-to-curate**: a heuristic that matches callee
namespaces against `datomic|jdbc|http|sql|s3|queue` (etc.) and reports any *not* in the
registry, so an un-curated I/O boundary surfaces instead of silently vanishing.

## 26. Build sequencing

Dependency order (each depends only on those above):

1. **`analysis.edn` substrate** — the single `clj-kondo.core/run!` wrapper (§12). Everything reads it.
2. **T1 static** + `sinks.edn` curated registry — the dictionaries.
3. **T3** — direct per-var I/O (needs analysis + sinks).
4. **T2** — commit→changed (independent; needs analysis + git + vendored `normalize`).
5. **T5** — declaration plugins (override T3; Pathom/RAD first).
6. **T4** — neighbor inference (needs T3 + call edges; tier 1 before tier 2).
7. **§24 merge** → `effective-io.edn`.
8. **T6** — orchestrator + recall-frontier report.
9. **T1 live** + cross-process stitching (§15.4) — last; an enrichment, not a blocker.
10. **§5 fan-out workflow** — separate, consumes `blast-radius.edn`.

T2 (step 4) is parallelizable with steps 2–3; everything else is a chain.

## 27. Empirical calibration — dataico (worst-case spike)

> Measured against the real `src/main/dataico` production tree (clj-kondo analysis):
> **32,646 vars, 2,344 production files, 230K call-edges.** Prune = RAD attribute
> keywords only (`dataico.model-rad` package, `:db/id` and `:X/keys` destructuring
> modifiers removed) → **2,290 real attributes**; 12,223 vars (~37%) touch ≥1 of them.
> Metric of record = **affected FILES** (the LLM-review unit, see below).

### 27.1 The file-level review unit (decided)

The blast radius is delivered **per affected file, not per var-pair**: the candidate set's
true size is "how many *files* must a reviewer/LLM look at," and that is bounded by the
project (≤ 2,344 here) and usually far smaller. The §5 fan-out is therefore **one judge per
affected file** — prompted with the change delta + that file's coupled locations — which
collapses an O(changed×readers) pair explosion into O(files). The named file set is always
complete (§7); a budget only paces how many files the precision pass adjudicates per run.

**Canonical denominator (dry-run gap).** The "% of files" metric is ambiguous unless pinned
(the dry-run saw 2344 HEAD vs 2379 on-disk vs 2179 with-defs). **Definition: the denominator
is the set of files containing ≥1 `:var-definition` in the analyzed tree** (i.e. files the
analysis can actually attribute code to), reported per run alongside the tree ref. All
percentages in this doc use that basis.

### 27.2 What the named set costs, and what trusting each hub band would save

**This is a cost map and a trust-list decision aid — NOT an auto-applied prune (§7).** It
shows the size of the *complete* named set, and, for each hub band, what recall a user would
*explicitly* sacrifice by trust-listing that band. Nothing here is dropped by the tool.
Per-function **1-hop data blast in files**, then the same if the user trust-lists RAD kws
appearing in > N files:

| trust-list applied | kws kept | p50 | p90 | p99 | max | %fns ≤5f | %fns ≤15f |
|--------------------|---------|-----|-----|-----|-----|----------|-----------|
| **none (full named set)** | 2290 | **63** | 554 | 709 | **1144 (49%)** | 6% | 17% |
| trust >200 files (−2 kws) | 2288 | 53 | 216 | 410 | 978 | 6% | 19% |
| trust >100 files (−15) | 2275 | 45 | 121 | 246 | 871 | 7% | 21% |
| trust >50 files (−70) | 2220 | 30 | 70 | 135 | 546 | 9% | 28% |
| trust >25 files (−214) | 2076 | 17 | 34 | 68 | 275 | 14% | 46% |
| trust >10 files (−623) | 1667 | **7** | 12 | 23 | **89** | 32% | **96%** |

Findings:

1. **The full named set is large.** Median function → **63 files**; worst single function
   (`dataico.data-model.base/entities`) → **1,144 files (49%)**. This is the *complete* blast
   radius; it is expensive, not wrong (§7).
2. **Hubs are extremely concentrated.** **15 keywords account for 11%** of all attribute
   file-occurrences; **214 (9% of the set) account for 47%.** `:company/id` alone → 578 files
   (25%). So a *small declared trust-list* would cut cost enormously — which is exactly why
   the tool **surfaces these hubs as trust-list candidates** (§7 lever 2).
3. **The cost curve is steep.** *If* a user trust-lists the 623 kws appearing in >10 files,
   the median drops 63→**7** and 96% of functions land ≤15 files. That is the *value* of an
   explicit trust-list — offered to the user, never auto-applied.
4. **Trust-listing a hub is a real, disclosed recall sacrifice** (§6/§7). Those 623 are
   *real* attributes; trust-listing `:company/id` means a change coupled *only* through it is
   no longer named — so the tool **never does this for you**. It ranks hubs low (IDF) and
   lists them as candidates; the user decides, and every exclusion is printed on the recall
   frontier. (Hub keywords are low-*information* evidence, so trust-listing them is usually
   cheap in practice — but that judgment is the user's, made explicitly.)
5. **Call-edge staleness MUST be scoped to project namespaces.** Raw caller-closure is
   meaningless — `clojure.core/let` has 7,022 callers and reaches 84% of files;
   `taoensso.tufte/p` (instrumentation) 63%. Real project functions have *small* caller
   closures (12–113 files); **the data side dominates.** Apply `scope-ns-prefixes`
   (`dataico.*`) to call edges before any closure — drop `clojure.core`/`cljs.core`/
   instrumentation. (Fulcro-spec's existing lever; §14.)

### 27.3 Keyword signal-classes — a RANKING + trust-list-suggestion aid (never a filter)

The hub list above is not random frequency — it is **semantic class**. Domain review of
dataico classifies the noise precisely (these classes explain ~9 of the top-15 hubs). **Per
§7, classes never *suppress* a candidate — they only (a) order the named set and (b) suggest
trust-list candidates the user may explicitly, disclosedly exclude.**

- **`:identity` (`:*/id`, name = `id` or `*-id`)** — a constant/lookup key; its value is a
  stable identifier, so it is *low-signal* coupling evidence. Ranked low; prime trust-list
  candidate. Purely syntactic to detect. (Still **named** — never auto-dropped.)
- **`:ownership`/`:tenancy` (`:*/company` and refs to the tenant identity)** — ubiquitous in
  a multi-tenant app. Note this is *already handled structurally*: edge (B) fires only from
  **sources** (§10), so a change merely *reading* `:company/id` for scoping does not fan out
  on it; only a change that is a **source of** `:company/id` (writes it — ownership
  management) names its readers, and that fan-out is **correct and complete**. No class gate
  is applied; ranked low and offered as a trust-list candidate.
- **`:deprecated` (`:entity/*`, or RAD attrs flagged deprecated)** — legacy schema: ranked
  low, **always kept** (it still couples real code today). Trust-list candidate if the user
  asserts it dead.
- **`:hub` (high IDF, no semantic class)** — genuinely-core attributes (`:invoice/items`, …)
  read everywhere. IDF **ranks** them low; they are high-value and **never dropped** except
  by an explicit trust-list choice.

Delta-role (**does the change produce/manage K vs. read/carry it**, via source-membership,
§10) is a **ranking** input — a coupling where the delta is a *source* of the connecting
keyword ranks above one where it merely carries it. It is *not* a gate: nothing is removed
for being read-only. (And it is **not** a distance measure — connectedness is binary and
distance-independent, §10; a write 20 hops downstream couples its readers exactly as one a
hop away does.)

Class assignment in the §18 dictionary is **pluggable**: (a) **auto-detection** —
`:identity` syntactic (`name = id`/`*-id`); `:ownership` from RAD `ao/target` refs to the
tenant identity (dataico: `:company/id`); `:deprecated` from RAD metadata; (b)
**user-supplied** — an explicit class map / classifier fn. Classes feed the rank and the
**suggested trust-list**; the user's actual trust-list exclusions (lever 2 of §7) are the
only thing that removes a candidate, and they are disclosed on the recall frontier (§6.2).

### 27.4 Pattern-var phenomenon (tail driver for §8)

The worst data-blast offenders include `…/pull-pattern`, `…/full-stub-pattern`,
`…/stub-pattern` — **EQL pattern definitions** that *list* 30–40 attributes without
producing or consuming them. Treating "mentions attribute" as "coupled to every reader"
over-counts these the worst. They are **read *declarations***: their attribute set is the
read-set of whoever *uses* the pattern (T4 §21), not a coupling of the pattern var itself.
The fix is **source-gating** (§10): a pattern-var mentions many attributes but reaches no
write sink and declares no output, so it is a pure reader — it contributes only edge (A)
(its callers), never edge (B) fan-out. This trims the tail without any keyword-role
classification (§8).

### 27.4 Tooling note

This calibration ran via clj-kondo CLI + babashka for fast iteration. The production tools
use the programmatic `clj-kondo.core/run!` API (§12) on a JVM REPL — markedly faster for the
repeated graph passes (transitive closures, sweeps) than re-reading a 300 MB EDN per run.

## 28. Cross-process stitching — the global attribute (producer/consumer) graph

Resolves §15.4. The keyword **is** the join (§3); this section makes that operational.

### 28.1 One combined analysis, not two merged

A single `clj-kondo.core/run!` over the whole `src` tree (clj + cljc + cljs) yields **one**
analysis: `.cljc` is split by reader-conditional, and every var-definition / keyword-usage
carries a `:lang` tag. The qualified keyword `:invoice/total` is byte-identical in CLJ and
CLJS, so indexing `keyword → occurrences` unifies server and client **automatically — no
merge step** (empirically confirmed: the dataico `src/main/dataico` probe emitted `:lang
:cljs` keyword-usages from one run). **Two-runs-merged-on-keyword is needed only when server
and client are separate codebases/classpaths** that cannot be analyzed in one pass; the join
is identical either way, merging is just a union of two keyword indices. **`:lang` is
provenance + edge-(A) scoping, not the join mechanism.**

### 28.2 Only the data edge crosses the boundary

- **Call edges (A) never cross.** There is no static call path from a CLJS component to a
  CLJ resolver — the Pathom engine and the wire sit between. Edge (A) stays within a runtime.
- **The data edge (B) is the *sole* cross-boundary edge, and it crosses purely via the
  shared keyword.** §3 made operational: change a server producer of K → client consumers of
  K, with zero call-graph path between them. (The formal model confirms this as two-edge
  independence: a resolver has empty (A), full (B).)

### 28.3 The global attribute graph: who produces / consumes K

Build `K → {:producers, :consumers}` from the combined analysis + declaration plugins (§22):

| role | source signal | runtime |
|------|----------------|---------|
| **producer** | Pathom resolver `K ∈ ::pco/output`; DB write reaching a sink carrying K (§21); mutation returning K; RAD `defattr` K (auto-resolver) | server |
| **consumer** | client `defsc :query` containing K (flatten nested EQL incl. join keys); `df/load!` of a component whose composed query contains K; RAD form `fo/attributes` / report `ro/columns`; server resolver `K ∈ ::pco/input` (downstream resolver); any §20 reader of K | client + server |

The **client-consumer plugin mirrors the resolver-producer plugin**: `defresolver`
(`::pco/output`) → producer; `defsc` (`:query`) → consumer; same machinery, opposite role.
The resolver in/out graph is itself a producer/consumer graph in attribute space — so edge
(B) operates *within* the server resolver chain as well as across to clients.

### 28.4 Client query-composition closure

Fulcro queries compose: a parent `:query` splices `(comp/get-query Child)`. Follow
component-composition usage edges (parent `defsc` → child component var) to flatten the full
consumed-attribute set reachable from each `df/load` root. Mini within-CLJS closure;
recall-first union.

### 28.5 RAD wrinkle: consumers reference attribute VARS, not keywords

RAD forms/reports list attribute **defs** (vars like `invoice/total`), not literal
`:invoice/total`. The consumer edge therefore joins through the `defattr` index
(`attribute-var → ::attr/qualified-key`): combined-analysis var-usage (form → attribute-var)
∘ `defattr` plugin (var → keyword). The §18 RAD live-seed doubles as this resolver.

### 28.6 Live `index-oir` as the authoritative server slice

In live mode (§18), Pathom's `::pci/index-oir` / `index-io` gives the exact, directional
server attribute graph (resolver inputs→outputs, mutations) — *authoritative*, no static
parsing. Use it for the server slice when available; static `defresolver`/`defsc`/RAD plugins
recover the rest and the entire client side (clients are not in `index-oir`). Hybrid:
**live for server, static for client.** Live `index-oir` is also the *only* recovery for the
aliasing hole below.

### 28.7 Provenance & cross-process recall holes

Every cross-process edge records both endpoints, the keyword, and both `:lang` tags, so the
judge sees "server resolver `foo` (`::output` K) ↔ client form `Bar` (`fo/attributes` K)".

Frontier (named per §6.2):
- **Attribute aliasing across a resolver** — a resolver that *renames* `:invoice/total` →
  `:ui/total` (input named differently from output) breaks the keyword join under the
  keyword-mediation assumption (formal model **Issue 3**). Recovered *only* by live
  `index-oir` (which records the in→out mapping); statically, name it as uncovered.
- Dynamically chosen `df/load` component; computed/dynamic EQL (`get-query` built at runtime).
- Attributes that traverse the wire inside opaque blobs (EDN strings, untyped params maps)
  without being named as keywords.

### 28.8 Generalization

"Cross-process" is **not special-cased** — it is one edge type in a single global attribute
producer/consumer graph spanning DB-writers, resolver in/out, mutations, server readers,
client components, and RAD forms/reports, all joined by the keyword. §15.4 is "build this
graph"; T6 edge (B) is a query over it; live `index-oir` is its authoritative server slice.

## 29. Declaration-primary attribute graph (decided)

For RAD/Fulcro-declarative vars, the producer/consumer attribute sets come from the
**declaration** (precise, directional, §22/§28), **not** from co-occurrence. Co-occurrence
(§21) is demoted to the **recall-backstop for hand-written / glue code only.** This inverts
§13's "declarations are an optional seed" framing for declarative targets — declarations are
the **backbone**; co-occurrence fills the gaps.

**Why.** The §27 worst-case noise was *pure co-occurrence*: the dry-run's 277-file phantom
fan-out came from every function that merely *mentions* `:party/*`. But those attributes'
real consumers are declared exactly — the forms whose `fo/attributes` and reports whose
`ro/columns` list them. Declaration-primary pins edge (B) to the handful of declared
consumers instead of every keyword co-mention: a large precision win with no recall loss on
the declarative surface (co-occurrence still covers non-declarative code).

**Mechanics.** Tag each var `declarative?` by its defining macro (clj-kondo `:defined-by` /
the §22 plugin set: `defattr`, `defsc`, `defsc-form`, `defsc-report`, `report`/`form`
options, `defresolver`, `defmutation`). Then:

| var kind | producer/consumer attribute set |
|----------|--------------------------------|
| declarative | the **declared** set (authoritative, directional) — §22/§28 |
| non-declarative | §21 co-occurrence inference (recall-first union, §24) |

**Forms are dual.** A RAD form *consumes* its `fo/attributes` (load/display) **and**
*produces* them (save → middleware → `d/transact`), so it sits on **both** edges for its
attribute set: a producer-change elsewhere blasts to the form (consumer), and editing the
form's attributes/save-logic is a supply-side change (edge B) to all readers of those
attributes. Reports are read-only consumers; `defattr` is the node + auto-generated resolver
(producer).

**The subtlety it forces (refines §24).** §24's *union-not-subtract* recall invariant
applies to the co-occurrence/inference layer. Declaration-primary makes a stronger, bounded
bet for declarative vars: **the declaration is treated as *complete* for that var's
data-layer attribute set**, so co-occurrence extras in its body (local computations, etc.)
are *not* unioned in — that is the precision win. This is a deliberate, **disclosed** bet:
a declarative component that reads an attribute it did not declare (off props, not in its
query) is uncovered — name it on the recall frontier ("declarative vars assumed complete
w.r.t. their declared attributes"). For non-declarative vars, §24 union-not-subtract is
unchanged.
