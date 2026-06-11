# Blast Radius — Formal Model & Soundness Analysis

> Companion to `design.md`. This is a notation-forward, math-like artifact. It formalizes
> the static change-impact construction (Part I §10, §8; Part II §17–§24), states the
> ground-truth coupling relation and the assumptions under which the static signals
> approximate it, and proves (sketch-level) coverage-modulo-frontier and local
> minimality. Section 6 is an adversarial soundness verdict.
>
> Convention: `BR` denotes the static candidate operator; the LLM/human judge is the
> *precision filter* `Φ` and is explicitly **outside** any soundness claim (design §11.2,
> §14). All theorems are about the static layer only.

---

## 0. Reading guide / scope of claims

We prove two things about the **static layer**:

- **T1 (Coverage modulo frontier).** Every genuinely behavior-affected location, except
  those in a disclosed frontier `F` of admitted coupling classes, is in `BR(Δ)`.
- **T2 (Local minimality / no gratuitous inclusion).** `BR(Δ)` is the least fixpoint of
  its own inclusion rules; every member carries a *witness* (a connecting keyword + a
  path, or a supply perturbation). The gap between `BR` and the true minimum is
  characterized and shown to coincide exactly with the places where value-level dataflow
  is unavailable, all delegated to `Φ`.

Neither theorem claims `Φ` is sound. `Φ` only *removes* candidates; by §4/§7 it is a
ranking/cost layer, so it cannot violate T1 (it never adds the false negatives T1
forbids). Whether `Φ` is *correct* is out of scope (design §11.2).

---

## 1. Objects, sets, relations

### 1.1 Static universe

| Symbol | Meaning |
|---|---|
| `V` | set of vars (fully-qualified symbols; the global key, §17) |
| `A` | set of qualified-keyword attributes — the global data schema (§3) |
| `S ⊆ V` | curated I/O **sink** vars (`sinks.edn`, §13/§20) |
| `Sʷ ⊆ S` | sinks tagged `:write` |
| `Sʳ ⊆ S` | sinks tagged `:read` |
| `Sˢ ⊆ Sʳ` | sinks tagged `:source?` (return carries read data, §20) |
| `call ⊆ V×V` | static call edges, clj-kondo `:var-usages`, resolved (§12) |
| `callₚ` | `call` restricted to project namespaces via `scope-ns-prefixes` (§27.2/§27.5) |

`call*` / `callₚ*` denote reflexive-transitive closure. `Reach→(x) = { y : (x,y) ∈ callₚ* }`
(forward, what `x` can reach); `Reach←(x) = { y : (y,x) ∈ callₚ* }` (callers of `x`).

### 1.2 Per-var profile (§17, the `effective-io.edn` unit)

For each `v ∈ V` the merged profile (§24) yields four attribute sets ⊆ `A`:

```
inputs(v)   declared/syntactic args destructured     (schema view)
outputs(v)  keywords v PRODUCES (map-key / assoc pos) (schema view, §8/§20)
reads(v)    keywords v CONSUMES (lookup/get/destr.)   (I/O-coupling view)
writes(v)   keywords that cross a WRITE sink on a path from v (I/O-coupling view, §21.3)
```

plus role flags

```
src?(v) ∈ {⊤,⊥}   v is a data SOURCE (§21.1): reaches a :source? read sink with
                   plausible result-flow to its return, OR declared source (resolver, §22)
wr?(v)  ∈ {⊤,⊥}   v (transitively, T6 reach) feeds a :write sink (§21.1/§21.3)
```

Merge law (§24), important for later: keyword **membership** is a *union* over
`{declared, inferred, syntactic}`; only **direction/role** uses precedence
(`declared > inferred > syntactic`). Union-not-subtract is the structural guarantee
that the merge never removes a recall edge.

### 1.3 Sources and the source-of-K predicate

The pivotal unification (§10): "source" subsumes DB-writers and Pathom resolvers.

```
SRC(v, K)  :⟺  K ∈ writes(v)                                  (write-path source)
                 ∨ ( declared-resolver(v) ∧ K ∈ outputs(v) )  (declared output source)
```

`Producers(K) = { v : SRC(v,K) }`.   `Readers(K) = { v : K ∈ reads(v) }`.

Note the asymmetry baked into the *definitions*: a pure reader (a UI query, a
pull-pattern var, an attribute registry — §10, §27.4) has `writes = ∅` and is not a
declared resolver, hence `SRC(v,·) = ⊥` for all `K`: it is in no `Producers` set. This
is the "pure reader has no data blast" prune, recovered definitionally, not by an ad-hoc
label.

### 1.4 The two edges (§10)

```
(A)  E_A  =  call-staleness  =  { (callee, caller) } = callₚ⁻¹      (ALWAYS active)
(B)  E_B  =  data            =  { (v, r) : ∃K. SRC(v,K) ∧ r ∈ Readers(K) }
```

`E_B` is exactly the §21.4 edge `writer W —K→ reader R for K ∈ writes(W)∩reads(R)`,
generalized so resolvers (whose `K ∈ outputs`, crossing the wire, §10/§22) also emit it.

### 1.5 The change seed Δ (§19)

`Δ` is **not** a bare set of symbols; it is symbols *plus a graph delta*:

```
Δ.changed     ⊆ V         normalized-signature delta (§19 step 3); added/removed ⇒ changed
Δ.appeared    ⊆ call ∪ {produces K}     newly-present call edges / keyword productions
Δ.disappeared.prod  ⊆ {produces K}      vanished productions / SOURCE-edges  (KEPT, §10 supply)
Δ.disappeared.call  ⊆ call              vanished call edges / reader-usages   (DROPPED, §10 demand)
```

Define the **supply-perturbed attribute set**

```
ΔK  :=  { K : ∃v∈Δ.changed. SRC(v,K) }                              (modified producers)
        ∪ { K : (produces K) ∈ Δ.appeared }                         (appeared producers)
        ∪ { K : (produces K) ∈ Δ.disappeared.prod }                 (disappeared producers)
```

This is the formal content of the **supply/demand asymmetry**: producer
*appearance, disappearance, or modification* all enter `ΔK`; demand-side disappearances
(`Δ.disappeared.call`) never do. Roles are read from the *codebase graph*, never from
diff text (§10); diff text is `Φ`-context only.

---

## 2. The construction BR(Δ) as a closure / least fixpoint

Two seed sets, then closure.

```
SEED_A(Δ) := Δ.changed                                  ⊆ V
SEED_B(Δ) := ΔK ⊆ A                                     supply-perturbed attributes
```

Inclusion rules (monotone operator `T` on `2^V`), with `X` the working candidate set:

```
(R0  seed)         Δ.changed ⊆ T(X)
(RA  staleness)    x ∈ X ∧ (x,c) ∈ E_A      ⟹  c ∈ T(X)      [caller of an included var]
(RB  data, seed)   K ∈ ΔK ∧ r ∈ Readers(K)  ⟹  r ∈ T(X)      [reader of perturbed supply]
(RB' data, deriv)  x ∈ X ∧ SRC(x,K) ∧ r ∈ Readers(K) ⟹ r ∈ T(X)   [reader of newly-implicated source]
```

`T` is monotone (each rule only adds), so by Knaster–Tarski the least fixpoint exists:

```
BR(Δ)  :=  μX. T(X)  =  ⋃ₙ Tⁿ(∅).
```

Operationally this is §23's algorithm: (R0)+(RA) is "transitive change closure" (step 2)
∪ caller staleness; (RB)/(RB') is "forward reach to writes → readers of K" (steps 3–4),
where forward-reach is folded into `SRC` via `writes(v) = {K : v reaches a write sink
carrying K}` (§21.3, T6 reach). Provenance (§9, step 5) attaches the witness used by
whichever rule fired. **Pruning/ranking (§7, §27.3) is applied AFTER `μX.T`** and is
modeled separately in §2.1 — it is *not* part of the recall fixpoint.

### 2.1 The pruning layer and the frontier F

Ranking (IDF, signal-class, delta-role gate, hub down-weight) reorders and may *drop*
candidates under budget. We split it into two kinds (this distinction is essential to
keep T1 honest):

- **Down-weighting / reordering** (`hub` IDF, specificity): permutes `BR(Δ)`; removes no
  element from the *covered* set, only from the *budget-visible prefix*. Does not affect
  coverage of `BR`.
- **Hard suppression that removes a candidate from the set** (hard-drop top hubs §27.2.4;
  the delta-role gate suppressing a low-signal class as a connector §27.3): this *does*
  remove elements. We model it as moving those elements onto the **disclosed frontier**:

```
F  :=  F_classes  ∪  F_static
F_classes := { y : y∈BR(Δ) removed only because its sole connecting K is a suppressed
                   low-signal class (identity/ownership/deprecated/hub-hard-drop) and the
                   delta does not produce/manage K }                       (§27.3 gate)
F_static  := admitted static blind spots (§6.2, §25): dynamic keywords; stringly/EDN/
             config payloads; ordering/time/accumulated-state coupling; pre-macro-expansion
             edges; opaque pattern + fully-escaping result + no in-repo consumer (§21.5);
             predicate-only reads (no keyword fingerprint, §21.5)
```

The design *requires* `F` be printed on every run (§6.2, §23, §27.3 last ¶). So the
delivered set is `BR(Δ) \ F_classes` with `F = F_classes ∪ F_static` disclosed. T1 is
stated **modulo `F`**, which is exactly this disclosed set.

---

## 3. Ground-truth coupling relation ⇝

Let a *change* at `x ∈ V` be any edit to `x`'s observable behavior. Define the semantic
relation we wish to cover:

```
Δ ⇝ y   :⟺   there exists an executable program context and an input under which
             editing the changed sites of Δ changes the observable behavior of y.
```

This is the true (uncomputable) blast radius. We cannot compute `⇝`; we approximate it
with `BR` under assumptions A1–A8. The assumptions are precisely the bridge from the
static signals to `⇝`.

### Assumptions

- **A1 (Keyword-mediation of non-call coupling).** *Every* behavioral coupling between two
  locations that is **not** carried by an in-process call is carried by a shared qualified
  keyword `K ∈ A` that is *produced* at the source side (DB write / resolver output /
  wire payload key) and *read* at the consumer side. (Design §3 "qualified keywords are a
  global data schema"; §2 the cross-time/cross-process examples.)
  *Used in:* T1 case (B) — it is what lets a keyword join stand in for value flow across
  time/process. **Its negation is the recall frontier `F_static`** (dynamic keywords,
  stringly/EDN payloads, ordering/time/accumulated state, §6.2).

- **A2 (Stable roles).** A surviving named function does not change its abstraction-level
  behavior (coding standard, §10): a function that did not read/write the DB does not
  silently begin to. Hence a *surviving* var's `src?`/`wr?`/role is constant across the
  diff; behavioral role change can only surface as a *graph-structure* change (an edge
  appears/disappears).
  *Used in:* the reduction of "role change" to `Δ.appeared`/`Δ.disappeared.prod`. **Note
  A2 is a convenience, not load-bearing** — §10 explicitly states even a *violating* role
  change surfaces as a supply-side edge appearance caught structurally; see Lemma 2.

- **A3 (Source-identification soundness — direction = supply).** If `K` flows from a
  location `p` to a location `c` semantically, then statically `SRC(p,K)` holds (`p` is on
  a write path carrying `K`, or declares output `K`) and `K ∈ reads(c)`. I.e. `SRC` and
  `reads` are **over-approximate lower-side-complete**: they never *miss* a real producer
  or a real reader (they may add spurious ones — that is intended over-approximation).
  *Used in:* T1 (B): `Producers`/`Readers` cover the real producer/reader of any real
  keyword coupling.

- **A4 (In-repo consumer).** Both endpoints of a coupling are inside the analyzed
  source set (single repo, or the cross-process stitch §15.4 has been performed so CLJS+CLJ
  share one keyword-keyed graph).
  *Used in:* T1 (B) existence of `r ∈ Readers(K)` in `V`. **Its negation is in `F_static`**
  (§21.5 cross-process consumer pre-stitch).

- **A5 (Macro-expansion completeness).** All call edges and keyword usages that exist
  post-expansion are visible to the analyzer (clj-kondo core macros known; project macros
  given hooks §15.8). *Used in:* completeness of `call`, `reads`, `outputs`. **Its negation
  is in `F_static`** (pre-macro-expansion edges, §6.2 / §25 T2).

- **A6 (Call-graph soundness of synchronous coupling).** If `y` is behaviorally affected by
  `x` through **synchronous in-process calls only**, then `(x,y) ∈ call*` (a static call
  path exists), and after project scoping `(x,y) ∈ callₚ*` provided `y` is a project var.
  *Used in:* T1 (A). Scoping to `callₚ` drops `clojure.core`/instrumentation reach (§27.2);
  the dropped vars are non-project (out of review scope), consistent with A4.

- **A7 (Produce-signal lower bound).** The syntactic produce/read discrimination (§8/§20)
  and the inference tiers (§21) never *under*-collect the keyword that actually flows: when
  ambiguous they keep both roles (recall-first), and the inference escape rule (§21.2) never
  lets a binding silently drop keys. *Used in:* `reads`/`writes`/`outputs` being lower-side
  complete (feeds A3).

- **A8 (Change seed completeness).** The normalized-signature delta plus graph delta (§19)
  flags as `Δ.changed` every var whose behavior changed, and records every appeared/
  disappeared production. Reformatting/doc-only edits correctly drop out (no behavior
  change ⇒ not in `⇝`'s domain). *Used in:* `SEED_A`, `ΔK` completeness.

**Frontier–assumption duality (key honesty property).** Each assumption that can fail in
practice has its failure set *explicitly* placed in `F`:
`¬A1, ¬A4, ¬A5 ⟶ F_static`. A2 is non-load-bearing (Lemma 2). A3/A6/A7/A8 are the
"we built the static layer correctly" assumptions — if violated, that is an implementation
bug, not a disclosed frontier (see §6, Issue 5).

---

## 4. Theorem 1 — Coverage / soundness relative to the frontier

> **Theorem 1.** Under A1–A8,  `{ y : Δ ⇝ y } \ F  ⊆  BR(Δ)`.

I.e. every genuinely behavior-affected location, except the disclosed-frontier classes, is
a static candidate. (Recall is the deliverable, §6.)

**Proof sketch.** Let `y` with `Δ ⇝ y` and `y ∉ F`. By definition of `⇝` there is an
execution where editing `Δ`'s sites changes `y`'s behavior. Take a *minimal* causal chain
of behavioral dependence from a changed site to `y`; each link is either an in-process
call or a non-call (over-time / cross-process) coupling. We show, by induction on the
length `n` of this chain, that `y ∈ BR(Δ)`.

*Base (n=0).* `y` is itself a changed site, `y ∈ Δ.changed = SEED_A(Δ) ⊆ BR(Δ)` by (R0).

*Inductive step.* Chain `x₀ → x₁ → … → x_{n-1} → y`, predecessor `p := x_{n-1}` already in
`BR(Δ)` (IH). Two link types:

- **Call link (synchronous).** `y` is affected by `p` through in-process calls, so `y`
  calls (transitively) `p`: `(p,y) ∈ call⁻¹*`. By **A6**, since `y ∉ F` ⇒ `y` is a project
  var ⇒ `(p,y) ∈ callₚ⁻¹* = E_A*`. Repeated (RA) from `p ∈ BR(Δ)` gives `y ∈ BR(Δ)`.
  *(A6 used here, and only here.)*

- **Data link (non-call).** `y`'s behavior depends on a value `p` placed into shared/
  persisted/wire state. Since this link is **not** a call, by **A1** it is mediated by some
  `K ∈ A` produced at `p` and read at `y`. Because `y ∉ F_static`, `K` is not a dynamic/
  stringly/ordering coupling — so A1's keyword exists statically. By **A3+A7**, `SRC(p,K)`
  holds and `K ∈ reads(y)`, i.e. `y ∈ Readers(K)`. By **A4**, `y ∈ V` (or stitched in).
  Now case on *why* `p` is implicated:

  - If `p ∈ Δ.changed` and `p`'s edit perturbed the supply of `K`: by **A8** the
    modified-producer clause puts `K ∈ ΔK = SEED_B`, so (RB) fires: `y ∈ Readers(K)
    ⟹ y ∈ BR(Δ)`. The **supply/demand case split** is discharged here: the only ways
    `K`'s supply is perturbed are producer modify / appear / disappear, all three in `ΔK`
    by construction (§1.5); a demand-side change (a reader/call vanishing) does **not**
    perturb `K`'s value and so does **not** create a new `⇝` edge into a *third* location
    — it is correctly absent from `ΔK`. (See Lemma 1 for soundness of dropping it.)
  - Else `p` is implicated only because it was reached along the chain (IH `p ∈ BR(Δ)`)
    and is itself a source of `K`: (RB') fires directly, `y ∈ BR(Δ)`.

  *(A1 used for the keyword's existence; A3+A7 for source/reader coverage; A4 for
  in-repo-ness; A8 for ΔK.)*

Finally, **frontier discharge.** If at any link the mediating coupling is dynamic/
stringly/ordering/pre-macro/cross-process-unstitched, then `y`'s connection runs through
exactly the classes constituting `F_static`; but then `y ∈ F`, contradicting `y ∉ F`. And
if `y`'s only connecting keyword is a hard-suppressed low-signal class with a non-managing
delta, `y ∈ F_classes ⊆ F`, again excluded. So every remaining link is coverable by (RA)/
(RB)/(RB'). ∎

**Where the proof is tight vs. loose.** The induction is honest about (A): it is the
classical staleness argument (a caller is stale iff a callee it transitively calls
changed), and A6 is the standard call-graph soundness assumption. Case (B) is *only as
strong as A1+A3*: it converts "value flowed" into "shared keyword existed and was
statically seen as produced-then-read." Everything A1+A3 cannot witness is, by
construction, in `F` — so the theorem is **vacuously safe on its frontier but makes a real
claim off it**. The supply/demand split is not an extra assumption; it is a *definition*
of `ΔK` plus Lemma 1.

### Lemma 1 (Soundness of dropping demand-side disappearances)

> Dropping `Δ.disappeared.call` (vanished call edges and vanished reader-usages) loses no
> `⇝`-edge that is not already covered.

**Sketch.** A vanished *call edge* `(a,b)`: the behavioral change lives in `a` (it stopped
calling `b`); `a`'s normalized signature changed ⇒ `a ∈ Δ.changed` (A8), so `a` and its
callers are covered by (R0)+(RA). No *new* location depends on the absence beyond `a`'s own
call-staleness cone. A vanished *reader* `r` of `K`: `r` no longer reads `K`, so no
execution routes `K`'s value into `r`'s behavior anymore — `r` is "dead demand," it cannot
be a `⇝`-target of this change (nothing flows to it). Hence neither contributes a `⇝`-edge
missing from `BR`. ∎  *(This is where A8 + the definition of `⇝` jointly justify the
asymmetry.)*

### Lemma 2 (A2 is not load-bearing)

> Even if A2 (stable roles) fails — a function genuinely changes its abstraction-level
> behavior, e.g. newly performs `d/transact` — coverage still holds via the graph delta.

**Sketch.** A role flip is, structurally, a new sink-ward call edge or a new production:
`(F, d/transact) ∈ Δ.appeared` or `(produces K) ∈ Δ.appeared`. By §1.5 this puts the
affected `K` into `ΔK`, firing (RB) to all `Readers(K)`. So the *structural* detector
catches the role change regardless of A2. A2 therefore only buys *efficiency/parsimony of
modeling* (we needn't union an old-role and new-role profile), not soundness — matching
§10's claim "the tool never depends on the coding standard for correctness." ∎

---

## 5. Theorem 2 — Local minimality (no gratuitous inclusion) + slack characterization

> **Theorem 2 (Least-fixpoint witnessed minimality).** `BR(Δ) = μX.T(X)`, and every
> `y ∈ BR(Δ)` carries a **witness**: either
> (i) `y ∈ Δ.changed` (seed), or
> (ii) a finite `E_A`-path `y → … → x₀ ∈ Δ.changed` (staleness witness), or
> (iii) a keyword `K` with `K ∈ reads(y)` together with a source justification —
>       `K ∈ ΔK` (supply-perturbation witness) or a source `x ∈ BR(Δ)` with `SRC(x,K)`
>       (derived-source witness).
> Consequently no inclusion rule is gratuitous: removing any one of (R0,RA,RB,RB') strictly
> shrinks `BR` and (by T1) breaks coverage for some `Δ`.

**Proof sketch.** Least fixpoint: `T` is monotone, `BR = ⋃ₙ Tⁿ(∅)`; membership is
introduced *only* by one of the four rules, and each application records its premise, which
is precisely the witness in (i)–(iii) (this is the §9 provenance: connecting keyword(s),
sink location, call path, delta). By Knaster–Tarski `μX.T(X)` is contained in every
fixpoint, so `BR` adds nothing a fixpoint of the rules could omit — it is *rule-minimal*.
Non-redundancy: each rule has a `Δ` for which it is the *unique* introducer of some
`y ∈ {y:Δ⇝y}\F` (e.g. a pure cross-process resolver consumer is reachable *only* by (RB),
since `E_A` is empty across the wire, §10), so dropping that rule forfeits a covered
element — by T1 that is a recall failure. ∎

### 5.1 Slack between `BR` (model-minimal) and the true minimum `{y:Δ⇝y}\F`

`BR` is minimal *with respect to its coupling model*, but the model over-approximates `⇝`
because value-level dataflow is unavailable. We name each over-approximation source, bound
it, and show it is exactly a point where precise flow is missing — hence delegated to `Φ`
(the design's intended division of labor, §4, §7).

| # | Over-approximation (slack) source | Where in design | Why static layer cannot avoid it | Delegated to Φ how |
|---|---|---|---|---|
| O1 | **Co-occurrence without value flow.** `K ∈ reads(r)` does not prove *this* change's `K`-value reaches `r`'s behavior (§4 "self-healing over-approx"). | §4, §8, §21 | clj-kondo cannot prove value flow; key-position is the safe upper signal. | Φ answers "does this delta actually break this reader?" pairwise (§4). |
| O2 | **T4 tier-1 caller attribution.** A source `F`'s inferred read-set = *all* `reads(C)` of each caller `C`, including keys `C` got from *other* sources (§21.2 tier 1). | §21.2 | Without local SSA, can't separate which reads came off `F`'s result. Tier 2 trims but is optional and escapes to tier 1. | Φ sees the call path + keyword and rejects mis-attributed `K`. |
| O3 | **Opaque pull/transact patterns.** `(d/pull db pattern eid)` read-set is unknown from the call; `pattern` may be dynamic (`:pattern-arg`, §20). Recovered only via downstream consumer shape (§21). | §10, §20, §21 | The pattern is a runtime value; static read of the call yields nothing. | Two outcomes: recovered → normal `Φ` candidate; unrecoverable (escapes + no consumer) → moved to `F_static` (§21.5), *not* silently dropped. |
| O4 | **Write-set under a `reduce`/dynamic tx-data.** Tx assembled by accumulation has no static produce fingerprint (§21.5). | §21.5 | No value-level flow; only the produce-signal union partially mitigates. | This is an *under*-recovery → it is a **recall hole**, so it lives in `F_static`, not `Φ`. (See Issue 4.) |
| O5 | **Source heuristic "can't prove discarded ⇒ source".** Any var calling a `:source?` sink without proof the result is dropped is marked a source (§21.1). | §21.1 | Proving a result is discarded needs dataflow. Recall-first default over-marks. | Φ rejects spurious sources via provenance `:proven-flow false` (§21.2). |
| O6 | **Class-suppression-as-frontier.** Hard-dropped hub/identity/ownership connectors (§27.2.4, §27.3). | §27 | A genuinely-`:company/id`-only coupling is real but low-information; keeping it floods the budget. | Modeled as `F_classes` and **disclosed** (§27.3 last ¶); the *delta-role gate* keeps it if the delta produces/manages `K`. |

**Crucial structural observation.** O1–O3, O5 are *over*-inclusions: they enlarge `BR`
beyond `{y:Δ⇝y}` but **preserve T1** (more candidates, never fewer) and land on `Φ`. Only
O4 is an *under*-recovery; it is therefore correctly **not** Φ's job but a *frontier*
entry. This is the cleanest possible separation: every place value-flow is missing is
either (a) over-approximated and handed to the precision filter, or (b) genuinely
unrecoverable and disclosed on `F`. There is **no third category** that silently drops a
candidate — *provided* the implementation honors the union-not-subtract merge (§24) and the
escape rules (§21.2). That proviso is the soundness-critical surface; see §6.

---

## 6. Soundness verdict — adversarial review

**Verdict: SOUND relative to its frontier, conditional on three implementation invariants
and with one genuine modeling gap (Issue 4) that must be moved onto `F`.** The two-edge
model, the supply/demand asymmetry, and the source-unification are internally consistent
and the coverage-modulo-`F` claim (T1) holds under A1–A8. Local minimality (T2) holds as a
least-fixpoint statement with per-element witnesses. The pruning/recall boundary is *almost*
clean; it stays clean only if class-suppression and hub hard-drops are modeled as `F` (which
the design does mandate, §27.3) and if O4 is acknowledged as a frontier item.

Concrete issues, most to least serious:

- **Issue 1 — `ΔK` "modified producer" depends on detecting a supply perturbation, which is
  itself value-flow.** A change to `v` with `SRC(v,K)` enters `ΔK` only if the *behavior* of
  the production changed. The design uses normalized-signature delta (§19) as the proxy:
  any non-cosmetic edit to `v` ⇒ `v ∈ Δ.changed` ⇒ all `K ∈ writes(v)` enter `ΔK`. This is
  **safe (over-approximate)** — it fires (RB) for *every* `K` `v` sources on *any* edit,
  even edits that don't touch `K`'s value. Slack, not unsound. But note it means the
  "delta produces/manages it" gate of §27.3 is doing real work to keep this from flooding;
  if the gate is mis-implemented as a *recall* filter rather than a *rank/F* filter, T1
  breaks. **Risk: the gate must drop to `F_classes`, never silently.**

- **Issue 2 — The supply/demand asymmetry has a latent hole: a *modified* (not vanished)
  reader.** §10 handles reader *appearance* (reviewed as its own change) and reader
  *disappearance* (dropped, dead demand). A reader that is *modified to read a new keyword
  K'* is covered because it is in `Δ.changed` (its signature moved) and `K' ∈ reads(r)` now
  — but does its *new* dependence on `K'` pull in `Producers(K')`? It should not need to for
  recall (we want readers-of-changed-producers, not producers-of-changed-readers): a reader
  newly depending on `K'` is only at risk if `K'`'s *supply* is also perturbed, which is a
  separate supply-side event. **This is consistent**, but the design never states the dual
  ("producers of a newly-read keyword are NOT seeded") explicitly; an implementer could
  wrongly add a demand→supply edge and either bloat cost or (worse) believe it adds recall
  it doesn't. **Recommend stating: `E_B` is supply-seeded only; demand never seeds.**

- **Issue 3 — Source-unification across DB (cross-time) and resolver (cross-process) assumes
  the *same* keyword carries the *same* meaning end to end (A1).** For DB this is strong
  (it's literally the schema). Across the wire it relies on Pathom/EQL using the identical
  qualified keyword client and server (true in idiomatic Fulcro/RAD). But a resolver that
  *renames* an attribute (outputs `:ui/total` from `:invoice/total`) breaks the join — the
  client reads `:ui/total`, no static producer of `:ui/total` exists except the resolver,
  and the resolver's own change is caught, but a *DB-writer* of `:invoice/total` would NOT
  reach the `:ui/total` reader. This is a real **cross-keyword aliasing recall hole** not
  named in §6.2's frontier list. **Recommend adding "attribute renaming / aliasing across a
  resolver boundary" to `F_static`.** (Live-mode `index-oir`, §18/§22, would recover it —
  so it's a frontier item only in static mode.)

- **Issue 4 — O4 (dynamic/`reduce`-built tx-data) is an UNDER-recovery presented as merely
  "partially mitigated" (§21.5), but under-recovery is precisely the one unrecoverable
  error (§6).** The design says the write-set is "under-recovered… never guaranteed." That
  is a *false negative* on edge (B): writers of `K` whose `K` never surfaces as a static
  produce signal will not seed `Readers(K)`. T1 only holds if this is on the frontier. §21.5
  does list it as an "honest recall hole specific to T4," and §25 routes T4 holes to the
  printed frontier — so it *is* technically disclosed. **Verdict: acceptable, but the model
  must classify O4 as `F_static`, not as Φ-collapsible slack.** I have done so in §5.1; the
  design's prose slightly blurs this by grouping it with the over-approximations.

- **Issue 5 — A3/A6/A7 are "correctness" assumptions with no frontier fallback.** Unlike
  A1/A4/A5, if source-identification, call-graph extraction, or the produce-signal lower
  bound is *buggy* (misses a real producer/reader/edge), there is no disclosed class
  capturing it — it is a silent false negative outside `F`. T1 is stated *under* A3/A6/A7,
  so this is formally fine, but operationally these are the assumptions most likely to fail
  silently (e.g. a `:source?` sink not in the curated `sinks.edn`; a destructure form T3's
  rewrite-clj classifier doesn't recognize). **The curated `sinks.edn` completeness is a
  single point of recall failure** with no in-band signal. Recommend: a meta-frontier line
  "coupling through I/O functions not in the sink registry is invisible" — currently *not*
  in §6.2/§25.

- **Issue 6 — "Graph distance is explicitly NOT a signal" (§7.1/§27.3) is correct for
  recall but interacts oddly with §23 step 6, which lists `:distance` in `:rank-why` and
  §15.3 lists graph distance as a ranking signal.** Internal inconsistency in the *design
  text* (not the model): §27.3 supersedes §7.1/§15.3/§23 and says distance is not used even
  as a tiebreaker. The model uses no distance anywhere (correct). **Flag: §23's example
  `:rank-why {:distance 2}` and §15.3 contradict §27.3 and should be reconciled in the
  doc.** Not a soundness defect of the model, but a latent implementation trap.

- **Issue 7 — `Φ`'s "no = deprioritize, not proven safe" (§11.2) is correctly outside
  soundness, but the *budget cap* (§7.2) is a silent recall cut at delivery time.** Even
  with perfect `BR`, a budget-capped run shows only a prefix. The design treats this as
  cost, and the frontier discloses *classes* but a budget cut drops *specific* covered
  candidates without listing them. **This is sound for `BR` but the delivered artifact can
  under-report; recommend the run header state "N candidates beyond budget not shown" so the
  budget cut is as disclosed as the frontier.** (Borderline: arguably already implied by
  §7.2, but not mandated like §6.2.)

**Things that hold up well (explicitly checked):**

- The **two-edge model** is consistent: (A) and (B) are genuinely independent (resolver case
  has empty (A), full (B), §10), and the union (A)∪(B) is what the fixpoint closes over.
- The **supply/demand asymmetry** is sound (Lemma 1): dropping demand-side disappearances
  loses no uncovered `⇝`-edge, and producer disappearance is correctly symmetric to
  appearance (a deleted writer/resolver can leave readers stale).
- **Source-unification** (DB-writer ≡ resolver ≡ any source-of-K) is a clean definitional
  move (§1.3) and is what makes (RB)/(RB') uniform.
- **Pure-reader = no-data-blast** falls out definitionally (`SRC(v,·)=⊥`), subsuming the
  pattern-var phenomenon (§27.4) without special-casing.
- **Recall-first union merge** (§24, union-not-subtract) is the invariant that keeps the
  whole construction monotone and `T1`-safe; declared sets only *raise* direction precedence,
  never subtract membership.

**Can anything not be made covering even modulo `F`?** No fundamental obstruction found,
**given** the curated `sinks.edn` is complete (Issue 5) and O4/Issue-3 aliasing are added to
`F`. The construction is covering-modulo-`F` exactly when `F` honestly includes: ¬A1
(dynamic/stringly/ordering), ¬A4 (cross-process pre-stitch), ¬A5 (pre-macro), O4 (dynamic
tx-data), resolver attribute-aliasing, and (meta) coupling via unregistered sinks. With
those six classes on `F`, T1 stands.
