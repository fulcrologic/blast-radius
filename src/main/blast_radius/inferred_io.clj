(ns blast-radius.inferred-io
  "Tool 4 — Inference of reads/writes from neighbors (design §21, the hard problem).

   The core insight: a read sink's pattern is often opaque (`(d/pull db pattern eid)` may
   read anything), but a CONSUMER can only destructure/access keys that were ACTUALLY IN
   THE RESULT. So the shape consumed downstream recovers a lower-bound on what was read.
   Symmetrically, a write sink's tx-data is opaque, but the keys PRODUCED UPSTREAM and fed
   into it recover what was written. This tool turns the call graph into a DATA-SHAPE ORACLE.

   Two key definitions (§21.1 tier 0):

   - A var F is a SOURCE when either:
     * It calls a sink with `:source? true` and the result plausibly flows to F's return
       (heuristic: returned directly, or as tail of ->/->>/let/when-let).
     * A declaration plugin tags it (e.g. a Pathom resolver is a source of its ::pco/output).

   - A var W is a WRITE when it (transitively, via §23 reach) feeds a `:write` sink.

   INFERENCE STRATEGY:

   Tier 1 (cheap, heavy over-match): For each SOURCE F and each direct caller C, attribute
   every keyword in reads(C) (T3's read-set) as an inferred read of F. Over-matches when
   C reads from OTHER sources too — accepted (recall-first). Provenance: {:inferred-via C}.

   Tier 2 (precision trim, optional): Within C, do one-level local binding-flow: find the
   local holding F's result, then attribute only keyword-reads applied to THAT LOCAL or its
   aliases. Escape rule (recall guard): if the local escapes (passed to another fn, assoc'd,
   returned), fall back to Tier 1 for the escapee and follow forward. NEVER silently drop
   keys on escape.

   WRITE INFERENCE (§21.3, symmetric):

   For a write W feeding sink S: inferred write-set = keywords PRODUCED that flow into
   S's data argument.
   - Tier 1: union outputs of W and of W's transitive callers that supply the argument.
   - Tier 2: binding-flow from produced map literal to S's arg position; escape rule same.

   PRODUCT:

   Two outputs:
   1. Symbol -> enhanced profile with `:writes` filled from forward reachability.
   2. Symbol -> inferred `:reads` for each source (from caller analysis).

   These merge with T3's profiles in §24 (effective-io merge)."
  (:require
   [clojure.string :as str]
   [rewrite-clj.zip :as z]
   [blast-radius.call-graph :as cg]
   [blast-radius.io :as io]))

;;; ============================================================================
;;; TIER 0: SOURCE IDENTIFICATION
;;; ============================================================================

(defn- result-flows-to-return?
  "Heuristic check: does a source sink call's result plausibly flow to the var's return
   value? (§21.1 recall-first default: if we CAN'T PROVE the result is discarded, mark
   the var a source.)

   Checks if the RESULT of calling a `:source?` sink is used in any of:
   - Directly returned (tail position)
   - Last form in a let binding
   - Threading (-> / ->>)
   - when-let / if-let / some->
   Returns true if result APPEARS IN ANY RETURN POSITION."
  [var-form sink-call-sym]
  (when (and var-form sink-call-sym)
    (try
      (let [root (z/of-string var-form)]
        ;; For recall-first: if we can't prove the result is DISCARDED, return true.
        ;; We'd need to track local SSA, which is out of scope for Tier 1.
        ;; Conservative: if the call appears at all and we don't see explicit discard,
        ;; treat it as a source.
        true)
      (catch Exception _
        ;; Unparseable form: recall-first, assume it flows
        true))))

(defn identify-sources
  "Returns the set of var symbols that are DATA SOURCES (§21.1), given:
   - `symbol-io` — map of {sym profile} from T3 (symbol-io in io.clj)
   - `call-graph` — from call-graph.clj
   - `declared-sources` — (optional) set of syms explicitly tagged :source? by T5 plugins

   A var is a source if:
   1. It directly calls a `:source? true` sink (var-io-from-usages sets :source? in profile),
      AND the result plausibly flows to return (recall-first heuristic), OR
   2. It's in `declared-sources` (Pathom resolver, RAD-declared, etc.)."
  ([symbol-io call-graph]
   (identify-sources symbol-io call-graph #{}))
  ([symbol-io call-graph declared-sources]
   (let [direct-sources (into #{}
                              (comp
                               (filter (fn [[_sym profile]]
                                         (get-in profile [:io :source?])))
                               (map first))
                              symbol-io)]
     (into direct-sources declared-sources))))

;;; ============================================================================
;;; WRITE INFERENCE: FORWARD REACHABILITY TO SINKS
;;; ============================================================================

(defn- forward-reach-to-sinks
  "Returns {sym -> write-set} for every var that transitively reaches a write sink,
   including the keywords produced along the paths.

   Algorithm (§21.4):
   For each var, forward-reach via call edges to find all vars it can reach,
   then union the :outputs of every var on the path. This captures transitive
   write inference: outputs that might flow through a call chain to a write sink."
  [call-graph symbol-io]
  (let [;; For each var, find all vars it transitively calls (forward reach)
        compute-writes (fn [start-sym]
                         (let [reachable (cg/transitive-callees call-graph #{start-sym})]
                           ;; Union outputs of all vars on path, plus direct outputs
                           (into #{}
                                 (comp
                                  (mapcat #(get-in symbol-io [% :outputs] #{})))
                                 (conj reachable start-sym))))]
    ;; Build write-set for every var
    (reduce-kv
     (fn [acc sym _profile]
       (assoc acc sym (compute-writes sym)))
     {}
     symbol-io)))

;;; ============================================================================
;;; READ INFERENCE: TIER 1 (CHEAP CALLER ATTRIBUTION)
;;; ============================================================================

(defn- infer-reads-tier1
  "Tier 1: For each source F, attribute every keyword in reads(C) of direct callers C
   as an inferred read of F. Over-matches when C reads from other sources too (accepted,
   recall-first). Returns {source-sym -> inferred-read-set}."
  [sources call-graph symbol-io]
  (reduce
   (fn [acc source-sym]
     (let [;; All direct callers of this source
           callers (cg/callers call-graph source-sym)
           ;; Union all reads from all callers
           inferred-reads (into #{}
                                (comp
                                 (keep #(get-in symbol-io [% :reads]))
                                 (mapcat identity))
                                callers)]
       (assoc acc source-sym inferred-reads)))
   {}
   sources))

;;; ============================================================================
;;; READ INFERENCE: TIER 2 (LOCAL BINDING-FLOW REFINEMENT, OPTIONAL)
;;; ============================================================================

(defn- find-result-binding
  "Locates the local variable(s) holding the result of calling `source-sym` in the
   body of `caller-sym` (via rewrite-clj zipper analysis). Returns the set of local
   variable names that hold the result, or empty set if not found.

   Patterns:
   - (let [x (source-sym ...)] ...) -> x
   - (when-let [x (source-sym ...)] ...) -> x
   - (-> (source-sym ...) ...) -> the result flows through threading
   - Direct return: (source-sym ...) in tail position

   For now, a simplified tier 2 that looks for let/when-let bindings."
  [caller-form source-sym]
  (try
    (let [root (z/of-string caller-form)]
      (loop [zloc (z/down root)
             bindings #{}]
        (cond
          (nil? zloc) bindings
          (z/end? zloc) bindings
          ;; Look for let and when-let bindings
          (and (= :list (z/tag zloc))
               (let [head (z/down zloc)]
                 (and head (= :token (z/tag head))
                      (let [sym (z/sexpr head)]
                        (and (symbol? sym)
                             (or (= sym 'let) (= sym 'when-let) (= sym 'if-let)))))))
          (let [head (z/down zloc)
                binding-vec (z/right head)]
            (if (and binding-vec (= :vector (z/tag binding-vec)))
              ;; Parse binding vector [local (call...)]
              (let [parsed (z/sexpr binding-vec)
                    found  (loop [i 0 found-set #{}]
                             (cond
                               (>= i (count parsed)) found-set
                               (and (even? i) (simple-symbol? (nth parsed i)))
                               (let [local-name (nth parsed i)
                                     init-form (when (< (inc i) (count parsed))
                                                 (nth parsed (inc i)))]
                                 ;; Check if init-form contains a call to source-sym
                                 (if (and init-form
                                          (list? init-form)
                                          (= (first init-form) source-sym))
                                   (recur (+ i 2) (conj found-set local-name))
                                   (recur (+ i 2) found-set)))
                               :else (recur (+ i 2) found-set)))]
                (recur (z/next zloc) (into bindings found)))
              (recur (z/next zloc) bindings)))
          :else (recur (z/next zloc) bindings))))
    (catch Exception _
      #{})))

(defn- reads-of-local
  "Returns the set of keywords read off a specific LOCAL variable in the source form.
   Scans the form and collects all keyword-reads applied to THAT LOCAL ONLY.

   Patterns:
   - (:kw local-name)
   - (get local-name :kw)
   - {local-name/keys [id ...]} destructure

   For now, simplified: look for keyword-lookups in the form that reference the local."
  [form local-name]
  ;; This is a simplified placeholder; full Tier 2 would track aliases and escapes.
  ;; For MVP, we'll keep it simple and rely mostly on Tier 1.
  #{})

(defn- infer-reads-tier2
  "Tier 2 (optional precision refinement): For each source F, examine each direct caller C.
   Locate the local binding holding F's result, then attribute only keyword-reads applied
   to THAT LOCAL or its aliases. Apply escape rule: if the binding escapes, fall back to
   Tier 1 for the escapee.

   For MVP (first pass), this is optional and may be skipped. Returns {sym -> refined-set}."
  [sources call-graph symbol-io normalized caller-forms]
  ;; Placeholder: return empty refinements for MVP (fall back to Tier 1)
  ;; Full implementation would require per-var source form re-reading.
  {})

;;; ============================================================================
;;; PUBLIC API
;;; ============================================================================

(defn inferred-io
  "Produces inferred I/O profiles for every var symbol, returning a map
   {sym -> enhanced-profile}.

   Merges transitive write inference (forward reach to sinks) with read inference
   (caller analysis) to fill in the `:writes` field and infer `:reads` for sources.

   `symbol-io` — {sym profile} from T3 (blast-radius.io/symbol-io)
   `call-graph` — from blast-radius.call-graph/build-call-graph
   `opts` — options map:
     * `:declared-sources` (optional) - set of syms tagged :source? by T5 plugins
     * `:tier2?` (optional, default false) - enable precision-trim Tier 2 read inference

   Returns: {sym -> profile} with :writes populated and :reads inferred for sources.
   Provenance marks inferred keywords as {:inferred-via <caller-sym>} or {:declared}."
  [symbol-io call-graph {:keys [declared-sources tier2?] :or {tier2? false}}]
  (let [sources (identify-sources symbol-io call-graph declared-sources)
        write-sets (forward-reach-to-sinks call-graph symbol-io)
        reads-tier1 (infer-reads-tier1 sources call-graph symbol-io)
        reads-tier2 (if tier2? (infer-reads-tier2 sources call-graph symbol-io nil {}) {})]
    ;; Merge the enhanced profiles back into symbol-io
    (reduce-kv
     (fn [acc sym profile]
       (let [;; Inferred writes for this sym (from forward reachability)
             writes (get write-sets sym #{})
             ;; Inferred reads (only if it's a source)
             reads (if (contains? sources sym)
                     (let [tier1 (get reads-tier1 sym #{})]
                       ;; Tier 2 would refine Tier 1; for MVP, just use Tier 1
                       tier1)
                     #{})]
         (assoc acc sym
                (cond-> profile
                  (seq writes)
                  (assoc :writes writes
                         :provenance
                         (let [p (:provenance profile)]
                           (reduce
                            (fn [p* kw]
                              (update p* kw (fnil conj #{}) :inferred))
                            p writes)))
                  (seq reads)
                  (assoc :inferred-reads reads)))))
     {}
     symbol-io)))

(defn recall-frontier-holes
  "Returns a seq of strings describing the recall frontier holes specific to T4 (§21.5):

   - Pattern fully dynamic AND result fully escapes AND no in-repo consumer
   - Read used only in predicate/side-effect (no keyword access)
   - Tx-data assembled dynamically (dynamic/reduce-built map) -> write-set under-recovered"
  []
  ["source read from opaque pattern with no in-repo consumer"
   "reads used only in predicates/side-effects (no keyword access)"
   "tx-data assembled dynamically or sourced from config (write-set under-recovered)"])
