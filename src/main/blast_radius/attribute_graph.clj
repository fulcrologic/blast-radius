(ns blast-radius.attribute-graph
  "Global producer/consumer attribute graph — the cross-process join (§28/§29/§24).

   The qualified keyword `K` (e.g. `:invoice/total`) is the *sole* edge that crosses the
   client/server boundary (§28.2): it is byte-identical in CLJ and CLJS, so indexing
   `K -> {:producers :consumers}` automatically stitches server producers to client
   consumers with no call-graph path between them. This namespace builds that index from
   three layers and merges them with the §24 precedence rules.

   Layering (§29 declaration-primary):

     * DECLARATION-PRIMARY — for *declarative* vars (those tagged by a §22 plugin macro:
       `defattr`/`defsc`/`defsc-form`/`defsc-report`/`defresolver`/`defmutation`) the
       DECLARED attribute set is treated as COMPLETE for that var's data layer. We do NOT
       union co-occurrence extras from the var body — the precision bet of §29. Producers
       come from a resolver's `::output` / a form's `fo/attributes` (dual: also consumer) /
       a `defattr`; consumers come from a `defsc :query` / report `ro/columns` / a
       resolver's `::input`.

     * CO-OCCURRENCE FALLBACK (§21) — for non-declarative / hand-written glue vars only.
       A var is a producer of `K` when its I/O profile carries `K` on the supply side
       (`:write?`/`:source?`/`:outputs`); a consumer when `K` is in its `:reads` /
       `:inferred-reads`, or — purely from the keyword index — when `K` co-occurs in the
       var (`:kw->vars`, the recall backstop reader join).

   MERGE PRECEDENCE (§24): keyword membership is the recall-first UNION of the three layers
   (declared ∪ inferred ∪ syntactic, never subtract); direction/role authority is
   declared > inferred > syntactic. Each `K` keeps a `:provenance` set so a judge can weigh
   a `:declared` edge above an over-matched `:syntactic` co-occurrence edge."
  (:require
   [clojure.set :as set]))

;; ---------------------------------------------------------------------------
;; declarative classification (§29)
;; ---------------------------------------------------------------------------

(def declarative-macros
  "The §22 plugin macro-set whose vars are treated as declaration-primary (§29): their
   declared attribute set is authoritative and complete for the data layer."
  '#{defattr defsc defsc-form defsc-report defresolver defmutation
     com.fulcrologic.rad.attributes/defattr
     com.fulcrologic.fulcro.components/defsc
     com.fulcrologic.rad.form/defsc-form
     com.fulcrologic.rad.report/defsc-report
     com.wsscode.pathom.connect/defresolver
     com.wsscode.pathom3.connect.operation/defresolver
     dataico.lib.pathom-wrappers/defresolver
     com.fulcrologic.fulcro.mutations/defmutation
     com.wsscode.pathom.connect/defmutation
     dataico.lib.pathom-wrappers/defmutation})

(defn declarative-profile?
  "Returns true when declared `profile` originates from a §29 declarative macro and thus
   carries an authoritative, complete declared attribute set. A profile is declarative when
   its `:provenance` is `:declared` (the §22 plugin tags every declared profile so), or when
   its `:defined-by` macro is in `declarative-macros`."
  [profile]
  (boolean
   (or (= :declared (:provenance profile))
       (contains? declarative-macros (:defined-by profile)))))

;; ---------------------------------------------------------------------------
;; per-var producer / consumer extraction
;; ---------------------------------------------------------------------------

(defn declared-roles
  "Returns `{:produces #{K} :consumes #{K}}` for a declaration-primary `profile` (§29).

   Producers are the supply side of the declaration: a resolver/mutation `:outputs`, a
   `defattr`'s `:declares-keyword`, and (forms being dual, §29) anything the var both
   produces and consumes. Consumers are the demand side: `:inputs` (resolver `::input`,
   `defsc :query`, report `ro/columns`). A var that both reads and writes (`:io` carries
   `:write?` and `:read?`, e.g. a mutation or a dual RAD form) keeps its `:outputs` on BOTH
   edges so an upstream supply change still blasts to it as a consumer."
  [profile]
  (let [outputs    (set (:outputs profile))
        inputs     (set (:inputs profile))
        declared-k (some-> (:declares-keyword profile) hash-set)
        io         (:io profile)
        dual?      (boolean (and (:write? io) (:read? io)))
        produces   (cond-> outputs
                     declared-k (set/union declared-k))
        consumes   (cond-> inputs
                     ;; forms/mutations are dual: they also consume what they declare/output
                     dual?      (set/union outputs)
                     declared-k (set/union declared-k))]
    {:produces produces
     :consumes consumes}))

(defn cooccurrence-roles
  "Returns `{:produces #{K} :consumes #{K}}` for a non-declarative `profile` via §21
   co-occurrence. The var produces `K` when it carries `K` on the supply side
   (`:source?`/`:write?` with `K` in `:outputs`/`:writes`); it consumes `K` when `K` is in
   `:reads` or `:inferred-reads`. Recall-first: a supply-side var that also reads keys is
   still kept as a consumer of those read keys."
  [profile]
  (let [outputs (set (:outputs profile))
        writes  (set (:writes profile))
        reads   (set/union (set (:reads profile)) (set (:inferred-reads profile)))
        supply? (boolean (or (:source? profile)
                             (get-in profile [:io :write?])
                             (seq writes)))
        produces (cond-> (set/union outputs writes)
                   ;; a source resolver-like glue var supplies its outputs even w/o :write?
                   (and supply? (seq outputs)) (set/union outputs))]
    {:produces produces
     :consumes reads}))

(defn profile-roles
  "Returns `{:produces #{K} :consumes #{K}}` for one var's `profile`, dispatching on §29
   declaration-primary vs co-occurrence fallback."
  [profile]
  (if (declarative-profile? profile)
    (declared-roles profile)
    (cooccurrence-roles profile)))

;; ---------------------------------------------------------------------------
;; accumulation into the K -> {:producers :consumers} index
;; ---------------------------------------------------------------------------

(defn- add-edge
  "Adds var `sym` to graph `g` at `[K role]` (`role` is `:producers` or `:consumers`),
   recording `prov` in the per-K provenance set."
  [g K role sym prov]
  (-> g
      (update-in [K role] (fnil conj #{}) sym)
      (update-in [K :provenance] (fnil conj #{}) prov)))

(defn- merge-roles
  "Folds one var `sym`'s `{:produces :consumes}` `roles` (tagged with provenance `prov`)
   into the accumulating attribute graph `g`."
  [g sym {:keys [produces consumes]} prov]
  (as-> g $
    (reduce (fn [acc K] (add-edge acc K :producers sym prov)) $ produces)
    (reduce (fn [acc K] (add-edge acc K :consumers sym prov)) $ consumes)))

;; ---------------------------------------------------------------------------
;; public API
;; ---------------------------------------------------------------------------

(defn build-attribute-graph
  "Builds the global producer/consumer attribute graph `{K {:producers #{var}
   :consumers #{var} :provenance #{:declared|:inferred|:syntactic}}}` (§28/§29/§24).

   Options map:

     * `:declared-profiles` - `{var-sym declared-profile}` from the §22 declaration plugins;
       declaration-primary (§29) source for declarative vars. Provenance `:declared`.
     * `:io-profiles` - `{var-sym io-profile}` from T3/T4 (`blast-radius.io` /
       `blast-radius.inferred-io`); co-occurrence supply/demand for non-declarative vars.
       Provenance `:inferred` when the profile carries `:inferred-reads`/`:writes`, else
       `:syntactic`.
     * `:kw-index` - the `blast-radius.keyword-index` result; its `:kw->vars` is the §28
       recall-backstop reader join: every var a keyword co-occurs in is a `:syntactic`
       consumer of `K` (recall-first, never subtracted, §24).
     * `:dictionary` - (optional, reserved) the §13/§18 keyword dictionary; accepted for
       signature stability, not yet consulted here.

   The keyword is the cross-process join (§28): a byte-identical `:invoice/total` in CLJ and
   CLJS unifies a server producer with its client consumers with no call-graph path between
   them. Declarative vars (§29) contribute ONLY their declared attribute set (precision bet);
   all three layers UNION for keyword membership (§24)."
  [{:keys [declared-profiles io-profiles kw-index _dictionary]}]
  (let [declared-syms (set (keys declared-profiles))
        ;; 1. declaration-primary layer (authoritative for declarative vars)
        g1 (reduce-kv
            (fn [g sym profile]
              (merge-roles g sym (declared-roles profile) :declared))
            {} declared-profiles)
        ;; 2. co-occurrence layer for NON-declarative io profiles only (§29: do not
        ;;    union co-occurrence extras over a var already covered by its declaration)
        g2 (reduce-kv
            (fn [g sym profile]
              (if (or (contains? declared-syms sym)
                      (declarative-profile? profile))
                g
                (let [prov (if (or (seq (:inferred-reads profile))
                                   (seq (:writes profile)))
                             :inferred :syntactic)]
                  (merge-roles g sym (cooccurrence-roles profile) prov))))
            g1 io-profiles)
        ;; 3. keyword-index recall backstop — every co-occurring reader is a :syntactic
        ;;    consumer of K (§28 reader join, §24 union-never-subtract). Declarative vars
        ;;    are excluded so their precise declared consumer set is not diluted (§29).
        g3 (reduce-kv
            (fn [g K vars]
              (reduce
               (fn [acc sym]
                 (if (contains? declared-syms sym)
                   acc
                   (add-edge acc K :consumers sym :syntactic)))
               g vars))
            g2 (:kw->vars kw-index))]
    g3))

(defn producers
  "Returns the set of producer vars of attribute `K` in attribute graph `ag` (§28.3),
   or `#{}` when none."
  [ag K]
  (get-in ag [K :producers] #{}))

(defn consumers
  "Returns the set of consumer vars of attribute `K` in attribute graph `ag` (§28.3),
   or `#{}` when none."
  [ag K]
  (get-in ag [K :consumers] #{}))

(defn provenance
  "Returns the provenance set (`#{:declared :inferred :syntactic}`) recorded for attribute
   `K` in attribute graph `ag` (§24), or `#{}` when `K` is absent."
  [ag K]
  (get-in ag [K :provenance] #{}))
