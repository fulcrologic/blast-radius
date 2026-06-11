(ns blast-radius.infer
  "Tool 4 — neighbor inference, SOURCE-GATED (design §21 / §10).

   This namespace implements the *source-gated* T4 inference described in §10 and §21.
   It is a thin, declarative re-statement of `blast-radius.inferred-io` with the precise
   public surface the §10 prune requires:

   The single biggest prune (§10): **a pure reader contributes only edge (A) — its callers
   — and has NO data blast.** UI queries, pull/stub pattern vars, and attribute registries
   mention many keywords but SOURCE none; they get no fan-out to writers/readers. We do NOT
   compute any inferred read fan-out for such vars. Inference is GATED on a var actually
   being a SOURCE (or a write) — pure readers get nothing here.

   SOURCE definition (§21.1 tier 0). A var F is a data SOURCE iff EITHER:

   - it calls a sink tagged `:source? true` and the result plausibly flows out of F
     (recall-first default: if we cannot prove the result is discarded, treat it as a
     source); OR
   - a declaration plugin (§22) tags it `:source? true` (e.g. a Pathom resolver is a
     source of its `::pco/output`).

   A PURE READER (lookups only, no `:source?` sink, not declared) is NOT a source and gets
   `source? = false` and NO inferred read fan-out (the §10 prune).

   READ inference — Tier 1 caller attribution (§21.2). For each source F and each direct
   caller C, attribute EVERY keyword in `reads(C)` (T3's read-set of C) as an inferred read
   of F. Over-matches when C reads keywords from other sources too — accepted (recall-first).
   Provenance: `{:inferred-via C :proven-flow false}`.

   WRITE inference — symmetric (§21.3). For a write W, the inferred write-set is the union
   of `outputs(W)` and the outputs of W's transitive callers that supply the argument."
  (:require
   [blast-radius.call-graph :as cg]))

;;; ============================================================================
;;; TIER 0: SOURCE GATE  (§21.1 / §10)
;;; ============================================================================

(defn source?
  "Returns true iff `var-sym` is a data SOURCE (§21.1 tier 0), and so is eligible for read
   fan-out (edge B). `io-profile` is the var's own T3/effective I/O profile (may be nil);
   `declared-profile` is the declaration-plugin profile for the var (may be nil).

   A var is a source when EITHER:

   - a plugin DECLARED it `:source? true` (e.g. a Pathom resolver — its `::pco/output` is
     sourced even though its in-process call edges are empty, §10); OR
   - it calls a `:source?` sink and the result plausibly flows out: `(get-in io-profile
     [:io :source?])` (set by T3's sink match), OR `(:source? io-profile)`. Recall-first:
     if a `:source?` sink is present we cannot prove the result is discarded, so we treat
     the var as a source.

   A PURE READER — lookups only (`:reads` populated, no `:source?` sink, not declared) —
   is NOT a source: it returns false and so gets NO data blast (the §10 prune).

   `var-sym` is accepted for call-site symmetry/provenance (the gate decides purely on the
   two profiles)."
  [_var-sym io-profile declared-profile]
  (boolean
   (or (true? (:source? declared-profile))
       (true? (:source? io-profile))
       (true? (get-in io-profile [:io :source?])))))

;;; ============================================================================
;;; READ INFERENCE — TIER 1 CALLER ATTRIBUTION  (§21.2)
;;; ============================================================================

(defn infer-reads
  "Returns the inferred read-set `#{kw}` for SOURCE `source-var` via Tier 1 caller
   attribution (§21.2): for each DIRECT caller C of `source-var` in `call-graph`, union
   `reads(C)` (C's T3 read-set, looked up in `io-profiles`). Over-matches by construction
   when C reads keywords off other sources too — accepted (recall-first).

   `call-graph` is a `blast-radius.call-graph` graph (uses `:call-in` for callers).
   `io-profiles` is `{var-sym profile}` where each profile may carry `:reads #{kw}`.
   Provenance for the resulting keywords is `{:inferred-via C :proven-flow false}` (carried
   by `inferred-io`, not by this set-returning helper)."
  [source-var call-graph io-profiles]
  (into #{}
        (mapcat (fn [caller] (get-in io-profiles [caller :reads])))
        (cg/callers call-graph source-var)))

;;; ============================================================================
;;; WRITE INFERENCE — SYMMETRIC  (§21.3)
;;; ============================================================================

(defn infer-writes
  "Returns the inferred write-set `#{kw}` for WRITE `write-var` (§21.3, symmetric to
   `infer-reads`): the union of `outputs(write-var)` and the `:outputs` of its DIRECT callers
   (tier-1), which supply the argument that flows into the write sink.

   TIER-1, NOT TRANSITIVE (corrected). The earlier transitive-callers union made every
   universally-called sink-facade (`…datomic.utils/pull`, `…api/q`) a 'writer' of ~3000
   keywords — the entire upstream cone's outputs — which then saturated the edge-(B)
   re-derivation cascade. Tier-1 mirrors `infer-reads` (§21.2) and bounds the write-set by
   the var's own construction plus its immediate callers' constructed outputs.

   `call-graph` uses `:call-in` for direct callers. `io-profiles` is `{var-sym profile}` where
   each profile may carry `:outputs #{kw}`. Recall-first: this recovers only a LOWER BOUND on
   writes (fully-dynamic tx-data is on the recall frontier, §21.5)."
  [write-var call-graph io-profiles]
  (let [suppliers (conj (cg/callers call-graph write-var) write-var)]
    (into #{}
          (mapcat (fn [v] (get-in io-profiles [v :outputs])))
          suppliers)))

;;; ============================================================================
;;; PUBLIC ENTRY — SOURCE-GATED INFERRED I/O  (§10 / §21)
;;; ============================================================================

(defn inferred-io
  "Returns `{var-sym {:reads #{kw} :writes #{kw} :source? bool}}` computed ONLY for vars
   that are SOURCES or WRITES — the source-gated entry of T4 (§10 / §21).

   PURE READERS get NOTHING here: a var that only does lookups (no `:source?` sink, not
   declared) is pruned (§10) and never appears in the result with a read fan-out.

   Arguments:

   - `normalized` — the normalized clj-kondo analysis. Provides the universe of vars to
     consider; falls back to the union of `io-profiles`/`declared-profiles` keys when its
     `:var-definitions` are absent.
   - `call-graph` — a `blast-radius.call-graph` graph (`:call-in`/`:call-out`).
   - `io-profiles` — `{var-sym profile}` from T3, each with `:reads`/`:outputs`/`:io`/
     `:source?`.
   - `declared-profiles` — `{var-sym profile}` from T5 declaration plugins, authoritative
     `:source?`/`:outputs`.

   For each source: `:reads` = `infer-reads`, `:source? true`; `:writes` = `infer-writes`
   ONLY if it also reaches a write sink, else `#{}` (a READ-ONLY source — e.g. a `pull`/`q`
   facade — produces no domain writes; its supply is its DECLARED outputs, merged separately).
   This is the second half of the facade-saturation fix: a universally-called read facade is
   `:source?` but must not be credited with WRITING the union of its callers' outputs.
   For each non-source WRITE: `:writes` = `infer-writes`, `:source? false`. Read fan-out is
   emitted only for sources (the §10 gate)."
  [normalized call-graph io-profiles declared-profiles]
  (let [defined  (into #{} (map :sym) (:var-definitions normalized))
        universe (into (into defined (keys io-profiles)) (keys declared-profiles))
        write?   (fn [v]
                   (let [p (get io-profiles v)]
                     (or (true? (get-in p [:io :write?]))
                         (true? (:write? p)))))]
    (reduce
     (fn [acc v]
       (let [iop (get io-profiles v)
             dec (get declared-profiles v)
             src? (source? v iop dec)
             w?   (write? v)]
         (cond
           src? (assoc acc v {:reads   (infer-reads v call-graph io-profiles)
                              :writes  (if w? (infer-writes v call-graph io-profiles) #{})
                              :source? true})
           w?   (assoc acc v {:reads   #{}
                              :writes  (infer-writes v call-graph io-profiles)
                              :source? false})
           ;; pure reader / neither: §10 prune — nothing.
           :else acc)))
     {}
     universe)))
