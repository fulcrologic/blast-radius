(ns blast-radius.blast
  "T6 — the blast-radius orchestrator (design §10/§23/§24/§25/§27).

   Ties T1–T5 together and emits the candidate set the §5 fan-out consumes. The
   blast radius is computed as a TRANSITIVE LEAST FIXPOINT over the union of two edge
   kinds (§23 step 4):

     * Edge (A) — CALL edges, ALWAYS. A caller is stale whenever a callee it
       (transitively) depends on changed. Walked over the call-graph `:call-in`
       adjacency (callers).
     * Edge (B) — DATA edges, only fired from SOURCES. When a changed (or reached)
       var is a *source* and PRODUCES a keyword `K`, every var that READS `K`
       (a consumer in the attribute graph) is reached. Crucially, a reached reader
       that is *itself a source* re-derives and produces `K'` — `K'` is fed back into
       the perturbed-supply set and the walk CONTINUES. This is the derived-data
       cascade (`:invoice/items -> :doc.analytics/total -> accounting`, §22.1): pure
       readers re-derive nothing and terminate, so depth is bounded by the number of
       derivation/cache layers, not by fan-out.

   Ranking (§23 step 6 / §27.3) is purely local: keyword signal-class, keyword IDF
   specificity, and delta-role (produces/manages vs reads/carries). NO graph distance.
   Output is keyed to AFFECTED FILES (§27.1) — one review unit per file."
  (:require
   [blast-radius.call-graph :as cg]
   [clojure.set :as set]
   [clojure.string :as str]))

(def default-fixes
  "Structural supply-side false-edge eliminations (design §7 lever 1), all ON by default.
   Each independently reduces edge-(B) OVER-PRODUCTION — the verified cause of hub-keyword
   blast saturation (a 2-keyword seed reaching ~1700 files because ~500–700 vars are credited
   with 'producing' `:db/id`/`:company/id` and §23.4 re-derivation re-injects those hubs):

     :f1 — STRUCTURAL non-domain keyword families are never edge-(B) supply keywords:
            Datomic structural keys (`:db/*`, `:db.*`), Fulcro client-local state (`:ui/*`),
            and clj-kondo synthetic/alias artifacts (namespace contains `?`). These never
            mediate persisted/cross-process domain coupling.
     :f2 — SYNTACTIC `:outputs` (co-occurrence in a body) do NOT count as production; only
            DECLARED outputs (declaration-primary §29) and inferred write-path keywords (§21
            `:writes`) supply. A var produces what it WRITES/DECLARES, not every kw it mentions.
     :f3 — IDENTITY / OWNERSHIP keywords (`:*/id`, `:company/id`; §27.3 classes) do not
            RE-INJECT during cascade re-derivation. A SEED that directly manages such a key
            still fans out (seed supply keeps them); a re-derived intermediate source merely
            carrying a tenant/identity key does not re-fan-out on it — that is the saturator.
     :f4 — EDGE-A SUPPRESSION FOR DECLARATIVE SEEDS. A changed RAD/Fulcro DECLARATION
            (`defattr`/`defsc`/`defsc-form`/`defsc-report`/`defresolver`/`defmutation`/
            `defssattr`) couples to the rest of the app via the §29 DATA edge, not by call-
            staleness: its var is REFERENCED as data (e.g. listed in an attribute registry
            with ~4000 transitive callers), so its transitive-caller closure is a false edge.
            A declarative seed keeps its edge-(B) data fan-out but contributes NO edge-(A)
            call closure. Non-declarative (function/`def`) seeds keep edge (A).
     :f5 — EDGE-A DOES NOT PROPAGATE THROUGH `def` DATA REGISTRIES. Call-staleness chains
            through FUNCTIONS; a plain `def` (attribute list / lookup table / entities
            registry) is DATA — its consumers couple to its VALUE (edge B), not behaviorally.
            So when the edge-(A) up-traversal reaches an intermediate `def` var, it is INCLUDED
            but its callers are NOT followed (the registry-membership amplification that gives
            every attribute ~4000 transitive callers). The seed itself always expands, so a
            changed registry's direct consumers are kept. CAVEAT: a `def`-bound CALLABLE
            (factory/partial/comp) is gated too — a narrow recall risk the precision pass
            should watch; ablate `:f5` to disable."
  #{:f1 :f2 :f3 :f4 :f5})

(def declarative-macro-names
  "Names (namespace-independent) of the RAD/Fulcro DECLARATION macros whose vars couple via
   the §29 data edge, not call-staleness (used by F4). Matched on `:defined-by` macro NAME so
   the rule is portable across the RAD/Fulcro/project namespaces that re-export them."
  #{"defattr" "defsc" "defsc-form" "defsc-report" "defresolver" "defmutation" "defssattr"})

(defn structural-nondomain?
  "F1: true when keyword `k` belongs to a family that never mediates domain data coupling
   (§7 lever 1) — Datomic structural keys (`:db/*`, `:db.*`), Fulcro client-local UI state
   (`:ui/*`, `:ui.*`), or a clj-kondo synthetic/alias keyword (namespace contains `?`, e.g.
   `:??_uism_??/asm-id`). Only the NAMESPACE is tested for `?`, so domain predicate attrs like
   `:payroll-employee/normal-salary?` (real namespace, name ends in `?`) are NOT excluded."
  [k]
  (let [ns (namespace k)]
    (boolean
     (and ns
          (or (= ns "db") (str/starts-with? ns "db.")
              (= ns "ui") (str/starts-with? ns "ui.")
              (str/includes? ns "?"))))))

(defn low-signal-supply?
  "F3: true when keyword `k` is an IDENTITY or OWNERSHIP attribute per the T1 `dictionary`
   signal-class (§27.3) — a stable lookup/tenancy key. Such keys do not re-fan-out from a
   re-derived intermediate source (the hub-saturation driver). Seeds are exempt."
  [k dictionary]
  (contains? #{:identity :ownership} (get-in dictionary [:keywords k :class] :specific)))

(defn produced-keywords
  "Returns the set of keywords `io-profile` produces (supply side, §10/§24). The supply set is
   `:writes` ∪ `:declared-outputs` ∪ `:outputs`, except under F2 (`:f2` ∈ `fixes`) where the
   SYNTACTIC `:outputs` (co-occurrence in a body) are dropped — only DECLARED outputs
   (`:declared-outputs`, declaration-primary §29) and inferred `:writes` (§21) count.

   NOTE the two output keys are DISTINCT (the §24 fix): a declared var keeps BOTH its declared
   set and its syntactic set, and F2 trims only the syntactic one. Callers apply F2 to cascade
   RE-DERIVATION but NOT to seeds (`fixpoint-reach`), so a seed keeps its full constructed
   output set (recall) while the cascade propagates only declared/written keywords."
  ([io-profile] (produced-keywords io-profile default-fixes))
  ([io-profile fixes]
   (let [decl (:declared-outputs io-profile)
         syn  (:outputs io-profile)
         outs (if (contains? fixes :f2) (set decl) (into (set decl) syn))]
     (into (set (:writes io-profile)) outs))))

(defn source?
  "Returns whether `var-sym`'s `io-profile` marks it a SOURCE (§10/§21) — i.e. it may
   re-derive/produce its supply keywords. Edge (B) only fires from sources."
  [io-profile]
  (boolean (:source? io-profile)))

(defn consumers-of
  "Returns the set of var-syms that READ keyword `k` according to the `attribute-graph`
   (its `:consumers` entry, the reader join §28). Empty when `k` has no recorded reader."
  [attribute-graph k]
  (get-in attribute-graph [k :consumers] #{}))

(defn producers-of
  "Returns the set of var-syms that PRODUCE keyword `k` according to the `attribute-graph`
   (its `:producers` entry)."
  [attribute-graph k]
  (get-in attribute-graph [k :producers] #{}))

(defn supply-keywords-of
  "Returns the set of keywords the SOURCE vars in `syms` produce, per `io-profiles` (§10/§24);
   non-source vars contribute nothing. Under F1 (`:f1` ∈ `fixes`) structural non-domain
   families are removed — they are never edge-(B) supply (§7 lever 1)."
  ([io-profiles syms] (supply-keywords-of io-profiles syms default-fixes))
  ([io-profiles syms fixes]
   (let [raw (into #{}
                   (comp
                    (filter #(source? (get io-profiles %)))
                    (mapcat #(produced-keywords (get io-profiles %) fixes)))
                   syms)]
     (cond->> raw (contains? fixes :f1) (into #{} (remove structural-nondomain?))))))

(defn gated-transitive-callers
  "Transitive callers of `seeds` over `call-graph`'s `:call-in`, where call-staleness does NOT
   propagate THROUGH an intermediate var in `gate-vars` (F5: `def` data registries/constants —
   referencing one is a data dependency, edge B, not a behavioral call). A gated var IS included
   when reached, but its own callers are not enqueued. `seeds` always expand (a changed
   registry's direct consumers are kept). Returns the reached set (including seeds; the caller
   removes seeds as usual)."
  [call-graph seeds gate-vars]
  (let [call-in  (:call-in call-graph)
        seed-set (set seeds)]
    (loop [frontier (vec seeds), seen #{}]
      (if-let [v (peek frontier)]
        (let [frontier (pop frontier)]
          (if (contains? seen v)
            (recur frontier seen)
            (let [seen'   (conj seen v)
                  expand? (or (contains? seed-set v) (not (contains? gate-vars v)))
                  callers (when expand? (get call-in v))]
              (recur (into frontier (remove seen') callers) seen'))))
        seen))))

(defn fixpoint-reach
  "Computes the TRANSITIVE LEAST FIXPOINT of the blast radius (§23 step 4) over call ∪
   data edges. Returns `{:reached #{var} :call-reached #{var} :via {var #{kw}} :perturbed #{kw}}`
   where `:reached` is every affected var (excluding the seeds themselves unless re-reached),
   `:call-reached` is the subset reached by a CALL edge (so trust-listing a connecting
   keyword can never drop a var that is also call-coupled, §7), `:via` records per reached
   var the connecting keyword(s) that pulled it in via a data edge (absent for pure call-edge
   reach), and `:perturbed` is the full set of perturbed supply keywords discovered.

   This computes the COMPLETE reach — it NEVER applies the trust-list (§7: name everything).
   Trust-list exclusion is applied downstream in `blast-radius`, against this complete set.

   Arguments:

     * `call-graph` - `{:call-in … :call-out …}` adjacency (call-graph tool).
     * `attribute-graph` - `{K {:producers :consumers …}}` (attribute-graph tool).
     * `io-profiles` - `{sym {:reads :writes :outputs :source?}}` (effective I/O, §24).
     * `seeds` - the changed var-syms.

   Edge (A): the call closure is taken ONCE over the whole call graph (already a least
   fixpoint). Edge (B): a worklist of NEWLY-perturbed keywords drives the data closure.
   Each keyword expands to its readers; readers that are themselves sources re-derive their
   produced keywords, which are fed back as new perturbed keywords. Termination is by the
   `perturbed` keyword set reaching a fixpoint — pure readers re-derive nothing, so the
   cascade depth is bounded by derivation layers, not fan-out.

   The §7-lever-1 false-edge eliminations are configured via `opts`:
     * `:dictionary` (T1, for F3 class lookup), `:fixes` (subset of `default-fixes`),
     * `:declarative-seeds` (`#{sym}`, F4 — excluded from edge-A),
     * `:gate-vars` (`#{sym}`, F5 — `def` data registries that don't propagate edge-A).
   F1/F2 narrow each var's produced set; F3 bars identity/ownership re-injection in the cascade;
   F4 drops declarative seeds from the call closure; F5 stops call-staleness at `def` registries."
  ([call-graph attribute-graph io-profiles seeds]
   (fixpoint-reach call-graph attribute-graph io-profiles seeds {}))
  ([call-graph attribute-graph io-profiles seeds
    {:keys [dictionary fixes declarative-seeds gate-vars]
     :or   {dictionary {} fixes default-fixes declarative-seeds #{} gate-vars #{}}}]
   (let [seed-set    (set seeds)
         ;; F4: declarative seeds couple via the DATA edge only — exclude them from edge-(A)
         ;; call-staleness (their var is referenced as data, e.g. registry membership, §29).
         call-seeds  (if (contains? fixes :f4) (set/difference seed-set (set declarative-seeds)) seed-set)
         ;; F5: call-staleness does not propagate through `def` data registries (gate-vars).
         call-reach  (if (contains? fixes :f5)
                       (gated-transitive-callers call-graph call-seeds gate-vars)
                       (cg/transitive-callers call-graph call-seeds))
         ;; SEED supply is high-recall: a seed produces what it CONSTRUCTS (full syntactic
         ;; `:outputs` included — F2 is NOT applied to seeds). This preserves the genuine
         ;; producer fan-out of a changed writer (e.g. an AP tx-builder constructing 17 AP
         ;; keywords). F2 + F3 apply ONLY to cascade RE-DERIVATION, which is where unchecked
         ;; syntactic-output / identity re-injection saturates the frontier.
         seed-supply (supply-keywords-of io-profiles seed-set (disj fixes :f2))
         rederive    (fn [syms]
                       (let [s (supply-keywords-of io-profiles syms fixes)]
                         (cond->> s
                           (contains? fixes :f3) (into #{} (remove #(low-signal-supply? % dictionary))))))]
     (loop [worklist  (vec seed-supply)            ; keywords not yet expanded
            perturbed seed-supply                  ; all perturbed keywords seen
            readers   #{}                           ; all data-reached vars
            via       {}]                           ; reader -> #{connecting-kw}
       (if (empty? worklist)
         ;; reached = (call-reach ∪ data-readers) minus seeds NOT independently re-reached.
         {:reached      (reduce disj (into call-reach readers) (remove readers seed-set))
          :call-reached (reduce disj (set call-reach) (remove readers seed-set))
          :via          via
          :perturbed    perturbed}
         (let [k          (peek worklist)
               worklist   (pop worklist)
               k-readers  (consumers-of attribute-graph k)
               via'       (reduce (fn [m r] (update m r (fnil conj #{}) k)) via k-readers)
               rederived  (rederive k-readers)
               new-kws    (into #{} (remove perturbed) rederived)]
           (recur (into worklist new-kws)
                  (into perturbed new-kws)
                  (into readers k-readers)
                  via')))))))

;; ---------------------------------------------------------------------------
;; Ranking (§23 step 6 / §27.3) — local signals only, NO graph distance.
;; ---------------------------------------------------------------------------

(defn delta-role-for
  "Returns the delta-role of the changed source var `seed` with respect to keyword `k`
   (§27.3): `:produces` when `seed` produces `k` (supply side), otherwise `:reads`.
   `io-profiles` is `{sym io-profile}`. Feeds ranking (ordering only, §7)."
  [io-profiles seed k]
  (if (contains? (produced-keywords (get io-profiles seed)) k)
    :produces
    :reads))

(defn rank-candidate
  "Returns `{:rank :rank-why}` for a candidate connected via `via-keywords` (the set of
   connecting keywords) with the given `delta-role`, scored against the `dictionary`
   (§23 step 6 / §27.3). The rank is a LOCAL signal — keyword signal-class + IDF
   specificity + delta-role — with NO graph distance.

   * Picks the highest-IDF (most specific) connecting keyword as the dominant signal.
   * `:produces`/`:manages` deltas are weighted above `:reads`/`:carries`.
   * Down-weights hub keywords (low IDF) automatically via the IDF term.
   * Pure call-edge candidates (no connecting keyword) get a small baseline rank.

   `:rank-why` exposes the dominant `:idf`, keyword `:class`, and `:delta-role`."
  [{:keys [via-keywords delta-role]} dictionary]
  (let [idf-of   (get dictionary :idf {})
        class-of (fn [k] (get-in dictionary [:keywords k :class] :specific))
        kws      (seq via-keywords)
        dominant (when kws (apply max-key #(get idf-of % 0.0) kws))
        idf      (if dominant (get idf-of dominant 0.0) 0.0)
        klass    (if dominant (class-of dominant) :call)
        role-w   (if (contains? #{:produces :manages} delta-role) 1.0 0.6)
        ;; squash IDF into a 0..1-ish multiplier; call-only candidates get a floor.
        idf-term (if dominant (/ idf (+ idf 4.0)) 0.15)
        rank     (double (* role-w idf-term))]
    {:rank     rank
     :rank-why (cond-> {:class klass :delta-role (or delta-role :reads)}
                 dominant (assoc :idf idf :keyword dominant))}))

;; ---------------------------------------------------------------------------
;; Recall frontier (§6.2/§25) — always present and non-empty.
;; ---------------------------------------------------------------------------

(def ^:private sink-families
  "The I/O families curated in sinks.edn that coverage is ASSUMED complete for (§25)."
  ["Datomic" "JDBC" "HTTP" "queue" "file I/O"])

(defn recall-frontier
  "Returns the run's recall frontier (§6.2/§25) — a NON-EMPTY vector of the blind-spot
   classes this run could not see, plus the always-printed meta line declaring the stack
   assumed complete. `info` supplies run-specific disclosure:

     * `:trust-list-excluded` - count of couplings removed by the user's DECLARED trust-list
                                (§7 lever 2 — the only sanctioned removal).
     * `:trust-list-fired`    - the trust-list entries (keywords / vars / namespaces) that
                                actually matched ≥1 removed coupling, for full disclosure.

   Per §6.2 a run that found nothing still prints what it could not look at, so the
   structural holes (dynamic keywords, opaque patterns without a consumer, stringly-typed
   sinks) and the sinks.edn-complete meta line are ALWAYS emitted. There is NO budget or
   class-suppression line — the named set is complete and the only removal is the user's
   explicit trust-list, disclosed here (§7)."
  [{:keys [trust-list-excluded trust-list-fired]}]
  (cond-> ["dynamic keywords: runtime-constructed (keyword (str …)) sites not connected (§6/§21.5)"
           "opaque pattern + no in-repo consumer: source under-recovered (§21.5)"
           "nested tx-data: attrs written inside a map nested under a collection key may be missed in a non-declarative writer's supply (§21.5)"
           "stringly-typed / external payloads: queue/EDN/config-driven coupling not named (§6)"
           (str "coverage assumes sinks.edn complete for: " (str/join " / " sink-families))]
    (pos? (or trust-list-excluded 0))
    (conj (str "trust-list excluded " trust-list-excluded " (declared): "
               (str/join ", " (map str (sort-by str trust-list-fired))) " (§7)"))))

;; ---------------------------------------------------------------------------
;; Declared trust-list (§7 lever 2) — the ONLY sanctioned removal.
;; ---------------------------------------------------------------------------

(defn trusted-target?
  "True when var `sym` is excluded by the trust-list's `:vars` (exact match) or
   `:namespaces` (prefix on the var's namespace, or on the symbol itself for bare
   ns-fallback nodes that carry no namespace). Non-symbol targets (e.g. synthetic CUD
   dispatch nodes, §22.1) are never var/ns trust-listed."
  [sym {:keys [vars namespaces]}]
  (boolean
   (or (contains? (or vars #{}) sym)
       (when (symbol? sym)
         (let [ns-str (or (namespace sym) (str sym))]
           (some #(str/starts-with? ns-str %) (or namespaces #{})))))))

(defn- trusted-target-entry
  "Returns the trust-list entry (the var sym, or the matched namespace prefix as `ns/*`)
   that excluded `sym`, for recall-frontier disclosure."
  [sym {:keys [vars namespaces]}]
  (if (contains? (or vars #{}) sym)
    sym
    (let [ns-str (or (namespace sym) (str sym))]
      (str (some #(when (str/starts-with? ns-str %) %) namespaces) "/*"))))

(defn apply-trust-list
  "Applies the DECLARED `trust-list` (§7 lever 2 — the only sanctioned removal) to the
   COMPLETE reach `entries` (`[{:target sym :via-keywords #{kw}}]`). `call-reached` is the
   set of call-coupled vars: a keyword trust-list NEVER drops a var that is also call-coupled.
   Returns `{:kept [{:target :via-keywords}] :excluded n :fired #{entry}}`:

     * a target whose sym/namespace is trust-listed is removed (fires that `:vars`/`:namespaces`
       entry);
     * a trust-listed connecting keyword is removed from a target's `:via-keywords` (fires that
       keyword); if that empties a PURE-DATA target's via (and it is not call-coupled), the
       target is removed too.

   With an empty/nil `trust-list` every entry is kept unchanged and `:excluded` is 0 — the
   named set is COMPLETE by default (§7)."
  [entries trust-list call-reached]
  (let [kw-trusted? (or (:keywords trust-list) #{})]
    (reduce
     (fn [acc {:keys [target via-keywords] :as e}]
       (cond
         (trusted-target? target trust-list)
         (-> acc
             (update :excluded inc)
             (update :fired conj (trusted-target-entry target trust-list)))

         :else
         (let [trimmed (into #{} (remove kw-trusted?) via-keywords)
               dropped (into #{} (filter kw-trusted?) via-keywords)]
           (if (and (seq via-keywords) (empty? trimmed) (not (contains? call-reached target)))
             ;; pure data coupling solely through trusted keyword(s) -> remove the candidate
             (-> acc (update :excluded inc) (update :fired into dropped))
             ;; keep (trim trusted kws from the via display; still disclose any that fired)
             (-> acc
                 (update :kept conj (assoc e :via-keywords trimmed))
                 (update :fired into dropped))))))
     {:kept [] :excluded 0 :fired #{}}
     entries)))

;; ---------------------------------------------------------------------------
;; Candidate assembly (§27.1 — one unit per affected FILE).
;; ---------------------------------------------------------------------------

(defn- file-of
  "Returns the source file of var `sym` from `io-profiles` (its `:file`), or nil."
  [io-profiles sym]
  (get-in io-profiles [sym :file]))

(defn blast-radius
  "Computes the blast radius for a set of `changed` vars and emits the §23 candidate set,
   keyed to AFFECTED FILES (§27.1 — one review unit per file). Returns:

     `{:run {:refs :named-count :trust-list-excluded :recall-frontier […]}
       :candidates [{:change {…} :file :affects [{:target-sym :file :via-keywords :rank :rank-why} …]} …]}`

   The named set is the COMPLETE transitive reach over call ∪ data edges (§7 — name
   everything). NOTHING is dropped for being unlikely: there is no budget truncation and no
   signal-class suppression. The ONLY removal is the user's DECLARED trust-list (§7 lever 2),
   applied by `apply-trust-list` and fully disclosed on the recall frontier. Ranking ORDERS
   the complete set for triage (`rank-candidate`); it never removes.

   Argument map keys:

     * `:changed`         - `[{:sym :file :line-range :diff}]` the changed vars (T2).
     * `:call-graph`      - `{:call-in :call-out}` call adjacency.
     * `:attribute-graph` - `{K {:producers :consumers}}` global producer/consumer graph.
     * `:inferred-io`     - `{sym io-profile}` effective per-var I/O (§24); used for the
                            data closure and delta-role.
     * `:io-profiles`     - alias accepted; same shape as `:inferred-io` (the merged
                            effective profiles). Falls back to `:inferred-io`.
     * `:dictionary`      - `{:idf :keywords :hub-keywords}` (T1) for RANKING (ordering only).
     * `:trust-list`      - optional `{:keywords #{kw} :vars #{sym} :namespaces #{\"prefix\"}}`
                            the user explicitly asserts unbreakable/out-of-scope (§7 lever 2).
                            Defaults to `{}` ⇒ the complete named set.
     * `:fixes`           - optional subset of `default-fixes` (§7-lever-1 structural false-edge
                            eliminations); defaults to ALL (`default-fixes`). Pass a subset
                            (e.g. `#{:f1}`) to ablate.
     * `:declarative-seeds` - optional `#{sym}` subset of the changed seeds that are RAD/Fulcro
                            DECLARATIONS (defattr/defsc/…); under F4 these are excluded from
                            edge-(A) call-staleness (they couple via the §29 data edge).
     * `:gate-vars`        - optional `#{sym}` of `def` data-registry vars; under F5 edge-(A)
                            call-staleness does not propagate through them.

   `:named-count` is the number of couplings in the emitted (complete) named set;
   `:trust-list-excluded` is the number removed ONLY by the trust-list."
  [{:keys [changed call-graph attribute-graph inferred-io io-profiles dictionary trust-list fixes
           declarative-seeds gate-vars]}]
  (let [profiles  (or io-profiles inferred-io {})
        fixes     (or fixes default-fixes)
        seeds     (mapv :sym changed)
        {:keys [reached call-reached via]}
        (fixpoint-reach call-graph attribute-graph profiles seeds
                        {:dictionary dictionary :fixes fixes
                         :declarative-seeds (or declarative-seeds #{}) :gate-vars (or gate-vars #{})})
        ;; delta-role: a kw is PRODUCED when ANY seed source produces it, else read (ranking).
        ;; Seeds use the high-recall production rule (F2 not applied to seeds, as in fixpoint).
        role-of   (fn [k]
                    (if (some (fn [s] (contains? (produced-keywords (get profiles s) (disj fixes :f2)) k)) seeds)
                      :produces :reads))
        ;; (1) COMPLETE reach as flat coupling entries — name everything (§7).
        entries   (mapv (fn [t] {:target t :via-keywords (get via t #{})}) reached)
        ;; (2) the ONLY removal: the user's declared trust-list (§7 lever 2).
        {:keys [kept excluded fired]} (apply-trust-list entries (or trust-list {}) (set call-reached))
        ;; (3) build ranked affects from the kept couplings (ranking = ordering only).
        affect-of (fn [{:keys [target via-keywords]}]
                    (let [delta-role (if (some #(= :produces (role-of %)) via-keywords)
                                       :produces :reads)
                          {:keys [rank rank-why]}
                          (rank-candidate {:via-keywords via-keywords :delta-role delta-role}
                                          dictionary)]
                      {:target-sym   target
                       :file         (file-of profiles target)
                       :via-keywords via-keywords
                       :rank         rank
                       :rank-why     rank-why}))
        affects   (mapv affect-of kept)
        ;; Group affected vars by FILE (§27.1), rank within and across files (ordering only).
        ranked    (->> (group-by :file affects)
                       (mapv (fn [[file vs]]
                               {:file    file
                                :rank    (reduce max 0.0 (map :rank vs))
                                :affects (vec (sort-by :rank > vs))}))
                       (sort-by :rank >)
                       vec)
        ;; A §27.1 candidate carries the changed var(s) originating THIS file's reach (the
        ;; seed(s) whose own file is the affected file when present, else the full changed set).
        ;; Only IDENTIFYING fields are embedded — the per-var diff/signatures would duplicate
        ;; across every affected file, so they are dropped.
        slim       (fn [c] (select-keys c [:sym :file :status :line-range]))
        changed-in (fn [file]
                     (let [here (filterv #(= file (:file %)) changed)]
                       (mapv slim (if (seq here) here changed))))
        candidates (mapv (fn [{:keys [file affects]}]
                           {:change  (changed-in file)
                            :file    file
                            :affects affects})
                         ranked)]
    {:run {:refs                (mapv str seeds)
           :named-count         (count affects)
           :trust-list-excluded excluded
           :recall-frontier     (recall-frontier {:trust-list-excluded excluded
                                                  :trust-list-fired    fired})}
     :candidates candidates}))
