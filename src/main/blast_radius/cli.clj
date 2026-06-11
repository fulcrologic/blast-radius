(ns blast-radius.cli
  "Runnable entry point for Blast Radius — the 'reliably runnable against dataico'
   deliverable (design §23). Wires the verified components (analysis, call-graph,
   keyword-index, io + declaration plugins, dictionary, changed, attribute-graph,
   infer, blast) into one idempotent pipeline that writes `blast-radius.edn` and
   prints the §23 recall frontier plus the affected-file count to stdout.

   PUBLIC:
     * `run`   — programmatic entry; returns the path to the written EDN.
     * `-main` — CLI entry; parses argv into the `run` option map."
  (:require
   [blast-radius.analysis :as analysis]
   [blast-radius.attribute-graph :as ag]
   [blast-radius.blast :as blast]
   [blast-radius.call-graph :as cg]
   [blast-radius.changed :as changed]
   [blast-radius.dictionary :as dict]
   [blast-radius.infer :as infer]
   [blast-radius.io :as bio]
   [blast-radius.keyword-index :as ki]
   [blast-radius.plugins :as plugins]
   [clojure.edn :as edn]
   [clojure.java.io :as jio]
   [clojure.pprint :as pprint]
   [clojure.string :as str]))

(defn- absolute-path
  "Returns the absolute path string for a clj-kondo (relative) `filename`, resolved
   against `src-root` when not already absolute."
  [src-root filename]
  (let [f (jio/file filename)]
    (if (.isAbsolute f) (.getPath f) (.getPath (jio/file src-root filename)))))

(defn- sink-coupling-by-caller
  "Returns `{caller-sym {:read? :write? :source? :sinks [fq-sym …]}}` — the DIRECT
   sink coupling of every caller, computed in a SINGLE pass over `call-usages`
   (the §17 `var-io-from-usages` coupling, batched so the full 400k+ usage list is
   not rescanned per var, design §12/§27.2 performance). `sinks` is the registry.

   The caller symbol is `(symbol from from-var)` (var-level), matching the call-graph
   node identity; the matched callee is the normalized resolved `:to` symbol."
  [call-usages sinks]
  (persistent!
   (reduce
    (fn [acc {:keys [from from-var to]}]
      (if-let [sink (and from-var (get sinks to))]
        (let [caller (symbol (str from) (str from-var))
              cur    (get acc caller {:read? false :write? false :source? false :sinks #{}})
              ios    (:io sink)]
          (assoc! acc caller
                  {:read?   (or (:read? cur) (contains? ios :read))
                   :write?  (or (:write? cur) (contains? ios :write))
                   :source? (or (:source? cur) (boolean (:source? sink)))
                   :sinks   (conj (:sinks cur) to)}))
        acc))
    (transient {})
    call-usages)))

(defn- fast-symbol-io
  "Returns `{var-sym profile}` in the §17 shape `blast-radius.io/symbol-io` produces,
   but built for whole-repo scale: file source is read ONCE per file (vars sliced from
   a cached line vector) and sink coupling comes from the precomputed
   `coupling` index. Reuses the verified `bio/classify-roles` for role extraction.

   This is a performance specialization of `symbol-io` for the runnable CLI — it does
   not change `io.clj`'s semantics, only the iteration strategy (single-pass coupling +
   per-file read caching) so the pipeline completes against the full dataico tree."
  [normalized sinks src-root]
  (let [coupling   (sink-coupling-by-caller (:call-usages normalized) sinks)
        ;; Cache file -> line vector so each file is read exactly once.
        file->lines (volatile! {})
        lines-of   (fn [filename]
                     (or (get @file->lines filename)
                         (let [path (absolute-path src-root filename)
                               ls   (try
                                      (with-open [r (jio/reader path)]
                                        (vec (line-seq r)))
                                      (catch Exception _ []))]
                           (vswap! file->lines assoc filename ls)
                           ls)))]
    (persistent!
     (reduce
      (fn [acc {:keys [sym filename row end-row]}]
        (let [lines (lines-of filename)
              end   (or end-row row)
              slice (when (and row (seq lines))
                      (subvec lines (min (dec row) (count lines))
                              (min end (count lines))))
              src   (when (seq slice) (str/join "\n" slice))
              roles (if src (bio/classify-roles src)
                        {:inputs #{} :outputs #{} :reads #{}})
              io*   (get coupling sym {:read? false :write? false :source? false :sinks #{}})
              kws   (into (into (:inputs roles) (:outputs roles)) (:reads roles))]
          (assoc! acc sym
                  {:sym        sym
                   :file       filename
                   :inputs     (:inputs roles)
                   :outputs    (:outputs roles)
                   :reads      (:reads roles)
                   :writes     #{}
                   :io         {:read? (:read? io*) :write? (:write? io*) :sinks (vec (:sinks io*))}
                   :source?    (:source? io*)
                   :provenance (zipmap kws (repeat #{:syntactic}))})))
      (transient {})
      (:vars normalized)))))

(def ^:private default-opts
  "General defaults (project-specifics come from `<repo-root>/.blast-radius.edn`)."
  {:ns-prefixes ["dataico."]            ; sensible default; override in .blast-radius.edn
   :paths       ["src/main"]
   :out-file    "blast-radius.edn"
   :trust-list  {}})

(defn- repo-config
  "Reads `<repo-root>/.blast-radius.edn` (project config) if present, else `{}`. Keys mirror
   `run` opts: `:ns-prefixes` `:paths` `:sinks-file` `:base-clj-path` `:trust-list` …"
  [repo-root]
  (let [f (jio/file repo-root ".blast-radius.edn")]
    (if (.exists f) (edn/read-string (slurp f)) {})))

(defn- resolve-path
  "Resolves `p` against `repo-root` when relative; returns nil for nil `p`."
  [repo-root p]
  (when p (let [f (jio/file p)] (if (.isAbsolute f) (.getPath f) (.getPath (jio/file repo-root p))))))

(defn- sinks-source
  "Returns a `read-sinks`-able source for `sinks-file`: an explicit (repo-relative or absolute)
   path when configured, else the BUNDLED `sinks.edn` from the tool's classpath."
  [repo-root sinks-file]
  (or (resolve-path repo-root sinks-file)
      (jio/resource "sinks.edn")))

(defn- resolve-refs
  "Returns `[ref-a ref-b]` for the comparison. When a single `commit` is supplied it
   expands to `[\"<commit>^\" \"<commit>\"]`; otherwise `ref-a`/`ref-b` are used as-is."
  [{:keys [ref-a ref-b commit]}]
  (cond
    (and ref-a ref-b) [ref-a ref-b]
    commit            [(str commit "^") commit]
    :else             (throw (ex-info "Provide either :commit or both :ref-a and :ref-b"
                                      {:ref-a ref-a :ref-b ref-b :commit commit}))))

(defn- load-analysis
  "Returns the normalized clj-kondo analysis for the repo, analyzed at the TREE OF `ref-b`
   (the NEW side of the comparison) so the static graph is FAITHFUL to the commit under
   analysis — not to whatever HEAD happens to be (the §12/§19 soundness fix). The per-ref
   analysis is cached by tree-SHA under `<repo-root>/.blast-radius` (`analysis/analyze-ref`),
   so the expensive clj-kondo pass runs once per distinct source tree.

   `:cached-analysis` (an explicit EDN path) still overrides — useful for tests/benchmarks and
   for the common `ref-b == HEAD` case where a prebuilt cache already matches the tree."
  [{:keys [repo-root cached-analysis ref-b cache-dir kondo-bin paths]}]
  (let [raw (if (and cached-analysis (.exists (jio/file cached-analysis)))
              (analysis/read-cached cached-analysis)
              (analysis/analyze-ref repo-root ref-b (cond-> {}
                                                      cache-dir (assoc :cache-dir cache-dir)
                                                      kondo-bin (assoc :kondo-bin kondo-bin)
                                                      paths     (assoc :paths paths))))]
    (analysis/normalize raw)))

(defn- declared->profiles
  "Adapts the T5 DECLARED profile map (`{sym {:outputs :source? :io :type :declares-keyword}}`)
   into the §17 io-profile shape blast's edge (B) consumes, so a changed resolver / mutation /
   `defattr` seed carries the keyword(s) it PRODUCES on the supply side (§24 declaration-primary).

     * resolver / mutation -> `:declared-outputs` (its declared output EQL) and `:source?`/`:io`.
     * `defattr`           -> produces its attribute keyword (`:type`), making the attr a source
                              of that keyword so a model change fires the data cascade (§28/§29).

   Declared production lands in `:declared-outputs` (NOT `:outputs`), kept DISTINCT from the
   syntactic `:outputs` of T3 so the union in `effective-io` preserves both (§24): a seed keeps
   its full constructed output set, while edge-(B) cascade re-derivation (F2) propagates only
   the declared set."
  [declared]
  (reduce-kv
   (fn [acc sym {:keys [outputs source? io type declares-keyword] :as prof}]
     (let [attr-kw  (when (keyword? type) type)
           dk       (when (keyword? declares-keyword) declares-keyword)
           produced (into #{} (concat (when (coll? outputs) outputs)
                                      (when attr-kw [attr-kw]) (when dk [dk])))]
       (assoc acc sym
              (cond-> {:sym sym :declared-outputs produced :reads (set (:inputs prof))
                       :io (or io {}) :source? (boolean (or source? (seq produced)))
                       :provenance :declared}
                attr-kw (assoc :declares-keyword attr-kw)))))
   {}
   declared))

(def ^:private profile-set-keys
  "io-profile keys whose values are keyword SETS — UNIONed (not replaced) when merging the T3 /
   T4 / T5 layers, so declaration-primary is a UNION (§24), never a subtraction."
  [:outputs :declared-outputs :reads :writes :inputs])

(defn- merge-profile
  "Merges io-profile `b` onto `a`: scalar keys (`:source?`/`:io`/`:file`/`:provenance`/…) take
   `b`'s value (declared precedence for direction), but the keyword-set keys (`profile-set-keys`)
   are UNIONed so no layer's keyword membership is lost (§24)."
  [a b]
  (reduce (fn [m k] (let [u (into (set (get a k)) (get b k))]
                      (cond-> m (seq u) (assoc k u))))
          (merge a b)
          profile-set-keys))

(defn- effective-io
  "Returns the merged effective per-var I/O profile map (§24, declaration-primary UNION):
   the syntactic T3 `io-profiles`, the source-gated T4 `inferred` profiles, and the T5
   `declared` producer profiles, layered with `merge-profile` so keyword-set membership is
   UNIONed across layers (a changed resolver/`defattr`/writer seed keeps BOTH its declared
   output set and its syntactic constructed set; edge (B) never silently loses either)."
  [io-profiles inferred declared]
  (let [declared* (declared->profiles declared)]
    (reduce-kv
     (fn [acc sym prof] (update acc sym merge-profile prof))
     (reduce-kv (fn [acc sym prof] (update acc sym merge-profile prof)) io-profiles inferred)
     declared*)))

(defn run
  "Runs the full Blast Radius pipeline against a git repository and writes the
   §23 candidate set to `:out-file`. Returns the path (string) to the written EDN.

   Idempotent: re-running with the same inputs reproduces the same file. The emitted
   `blast-radius.edn` is the COMPLETE named set (§7 — name everything); it is never budget-
   truncated or class-suppressed. The ONLY removal is the user's declared `:trust-list`,
   disclosed on the §23 recall frontier printed to stdout.

   `opts` (merged over the dataico defaults) is a map of:

     * `:repo-root`       - (required) the git working-tree root to analyze.
     * `:ref-a` / `:ref-b`- the OLD/NEW git refs to compare.
     * `:commit`          - a single commit; expands to `<commit>^..<commit>`.
     * `:ns-prefixes`     - namespace-prefix strings scoping the call graph (e.g. `[\"dataico.\"]`).
     * `:sinks-file`      - path to the curated sink registry (`resources/sinks.edn`).
     * `:base-clj-path`   - path to the dataico CUD model file (synthetic edges, §22.1).
     * `:out-file`        - where to write `blast-radius.edn`.
     * `:trust-list`      - optional `{:keywords #{kw} :vars #{sym} :namespaces #{\"prefix\"}}`
                            the user explicitly asserts unbreakable/out-of-scope (§7 lever 2).
                            Defaults to `{}` ⇒ the complete named set; every exclusion is
                            disclosed on the recall frontier.
     * `:cached-analysis` - optional path to a cached clj-kondo analysis EDN (§12 fast path)."
  [opts]
  (let [repo-root       (or (:repo-root opts) (System/getProperty "user.dir"))
        ;; merge order: general defaults < project `.blast-radius.edn` < explicit opts.
        {:keys [ns-prefixes sinks-file base-clj-path out-file trust-list] :as opts}
        (merge default-opts (repo-config repo-root) opts {:repo-root repo-root})
        [ref-a ref-b] (resolve-refs opts)
        ;; (1) static analysis of the tree AT ref-b (faithful per-ref, cached by tree-sha;
        ;;     an explicit :cached-analysis still overrides).
        normalized      (load-analysis (assoc opts :ref-b ref-b))
        ;; (2) call graph scoped to ns-prefixes, UNION synthetic CUD edges (§22.1, optional).
        base-graph      (cg/build-call-graph normalized {:ns-prefixes ns-prefixes})
        synthetic       (plugins/synthetic-edges {:base-clj-path (resolve-path repo-root base-clj-path)})
        call-graph      (reduce
                         (fn [g {:keys [from to]}]
                           (-> g
                               (update-in [:call-out from] (fnil conj #{}) to)
                               (update-in [:call-in to] (fnil conj #{}) from)))
                         base-graph
                         synthetic)
        ;; (3) keyword index (row-range attribution, §20).
        kw-index        (ki/keyword-index (or (:analysis normalized) normalized))
        ;; (4) direct I/O profiles (T3) + declarative profiles (T5 plugins).
        sinks           (bio/read-sinks (sinks-source repo-root sinks-file))
        io-profiles     (fast-symbol-io normalized sinks repo-root)
        declared        (plugins/analyze-forms normalized {:src-root repo-root})
        ;; (5) keyword dictionary (T1) — RAD universe widened with declared kws.
        declared-kws    (into #{} (keep :declares-keyword) (vals declared))
        dictionary      (dict/build-dictionary normalized kw-index
                                               {:rad-attrs declared-kws})
        ;; (6) changed vars over the git range.
        changed         (changed/changed-vars {:repo-root repo-root :ref-a ref-a :ref-b ref-b})
        ;; (7) global producer/consumer attribute graph (§28/§29).
        attribute-graph (ag/build-attribute-graph {:declared-profiles declared
                                                   :io-profiles       io-profiles
                                                   :kw-index          kw-index
                                                   :dictionary        dictionary})
        ;; (8) source-gated inferred I/O (T4) and the merged effective profiles.
        inferred        (infer/inferred-io normalized call-graph io-profiles declared)
        ;; ns -> source file, so call-graph nodes that are NOT clj-kondo var-definitions
        ;; (e.g. macro-registered job vars) still resolve to a review FILE (§27.1) instead
        ;; of collapsing into a nil-file bucket. File is attributed by the node's namespace.
        ns->file        (persistent!
                         (reduce (fn [m {:keys [ns filename]}]
                                   (cond-> m (and ns filename (not (get m ns)))
                                           (assoc! ns filename)))
                                 (transient {}) (:vars normalized)))
        fill-file       (fn [profiles syms]
                          (reduce (fn [p s]
                                    (if (or (not (symbol? s)) (get-in p [s :file]))
                                      p
                                      ;; qualified var -> file of its namespace; bare
                                      ;; ns-fallback node -> file of that namespace itself.
                                      (if-let [f (or (get ns->file (some-> (namespace s) symbol))
                                                     (get ns->file s))]
                                        (assoc-in p [s :file] f)
                                        p)))
                                  profiles syms))
        effective       (-> (effective-io io-profiles inferred declared)
                            (fill-file (keys (:call-in call-graph)))
                            (fill-file (keys (:call-out call-graph))))
        ;; (9) declarative seeds (defattr/defsc/…) — edge-(A) suppressed under F4 (§29 data edge).
        decl-macro?     (fn [{:keys [defined-by]}]
                          (and defined-by (contains? blast/declarative-macro-names (name defined-by))))
        declarative-vars (into #{} (comp (filter decl-macro?) (map :sym)) (:vars normalized))
        declarative-seeds (into #{} (filter declarative-vars) (map :sym changed))
        ;; F5 gate-vars: plain `def` DATA registries (call-staleness does not propagate through).
        gate-vars       (into #{}
                              (comp (filter (fn [{:keys [defined-by]}]
                                              (= "def" (some-> defined-by name))))
                                    (map :sym))
                              (:vars normalized))
        ;; F5 data-side companion (registry passthrough): F5 removes the CALL-staleness through a
        ;; `def` registry, but a reader of a registry that LISTS attribute vars genuinely consumes
        ;; those attrs' keywords (a RAD form's `fo/attributes` built from an attr-list `def`). So
        ;; re-establish that as a DATA edge: each registry `def` re-exports the keywords declared
        ;; by the attr-vars it references to its DIRECT readers (§29 declared-consumer recall;
        ;; bounded to direct readers, so the ~4000-caller transitive explosion stays closed).
        var->declared-kws (into {}
                                (keep (fn [[s p]]
                                        (let [ks (into #{} (concat
                                                            (when (keyword? (:type p)) [(:type p)])
                                                            (when (keyword? (:declares-keyword p)) [(:declares-keyword p)])
                                                            (when (coll? (:outputs p)) (:outputs p))))]
                                          (when (seq ks) [s ks]))))
                                declared)
        attribute-graph (reduce
                         (fn [g D]
                           (let [kws     (into #{} (mapcat var->declared-kws) (get-in call-graph [:call-out D]))
                                 readers (get-in call-graph [:call-in D])]
                             (if (and (seq kws) (seq readers))
                               (reduce (fn [g k] (update-in g [k :consumers] (fnil into #{}) readers)) g kws)
                               g)))
                         attribute-graph
                         gate-vars)
        ;; (10) blast radius generation (§23/§24/§25/§27).
        result          (blast/blast-radius {:changed         changed
                                             :call-graph      call-graph
                                             :attribute-graph attribute-graph
                                             :inferred-io     effective
                                             :io-profiles     effective
                                             :dictionary      dictionary
                                             :trust-list      trust-list
                                             :fixes           (:fixes opts)
                                             :declarative-seeds declarative-seeds
                                             :gate-vars       gate-vars})
        ;; Record the actual refs in the run metadata (audit/idempotence).
        result          (assoc-in result [:run :refs-compared] [ref-a ref-b])
        out             (jio/file out-file)
        frontier        (get-in result [:run :recall-frontier])
        affected        (count (:candidates result))]
    (with-open [w (jio/writer out)]
      (binding [*out* w]
        (pprint/pprint result)))
    (println "Blast Radius:" ref-a ".." ref-b)
    (println "Affected files:" affected
             (str "(named couplings " (get-in result [:run :named-count])
                  ", trust-list excluded " (get-in result [:run :trust-list-excluded]) ")"))
    (println "Recall frontier (§23/§25):")
    (run! (fn [line] (println "  -" (str line))) frontier)
    (.getPath out)))

(defn -main
  "CLI entry point. Usage:

     blast-radius <A>..<B>            # compare two refs (e.g. $(git merge-base main HEAD)..HEAD)
     blast-radius <commit>            # a single commit (expands to <commit>^..<commit>)
     blast-radius <range> [--flags]

   Repo defaults to the CURRENT DIRECTORY; project config is read from `.blast-radius.edn`.
   Flags (`--key value`): `--repo-root`, `--out-file`, `--paths a,b`, `--ns-prefixes a,b`,
   `--cache-dir`, `--kondo-bin`, `--cached-analysis`, and the trust-list (§7 lever 2):
   `--trust-keywords :a/b,:c/d`, `--trust-vars ns/x,ns/y`, `--trust-namespaces a.b,c.d`."
  [& args]
  (let [positional (remove #(str/starts-with? (str %) "--") (take-while #(not (str/starts-with? (str %) "--")) args))
        flag-args  (drop (count positional) args)
        pairs      (into {}
                         (comp (partition-all 2)
                               (keep (fn [[k v]]
                                       (when (and k (str/starts-with? (str k) "--"))
                                         [(keyword (subs (str k) 2)) v]))))
                         flag-args)
        csv->vec  (fn [s] (when s (mapv str/trim (str/split s #","))))
        csv->set  (fn [s f] (when s (into #{} (map (comp f str/trim)) (str/split s #","))))
        range-arg (first positional)
        refs      (cond
                    (and range-arg (str/includes? range-arg "..")) (str/split range-arg #"\.\.+")
                    range-arg                                       [:commit range-arg])
        trust     (cond-> {}
                    (:trust-keywords pairs)   (assoc :keywords   (csv->set (:trust-keywords pairs) #(keyword (subs % 1))))
                    (:trust-vars pairs)       (assoc :vars       (csv->set (:trust-vars pairs) symbol))
                    (:trust-namespaces pairs) (assoc :namespaces (csv->set (:trust-namespaces pairs) identity)))
        opts      (cond-> (dissoc pairs :trust-keywords :trust-vars :trust-namespaces)
                    (:ns-prefixes pairs) (update :ns-prefixes csv->vec)
                    (:paths pairs)       (update :paths csv->vec)
                    (= :commit (first refs)) (assoc :commit (second refs))
                    (and refs (not= :commit (first refs))) (assoc :ref-a (first refs) :ref-b (second refs))
                    (seq trust)          (assoc :trust-list trust))]
    (run opts)
    (shutdown-agents)))
