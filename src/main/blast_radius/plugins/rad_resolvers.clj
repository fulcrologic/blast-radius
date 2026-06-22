(ns blast-radius.plugins.rad-resolvers
  "Declaration plugin (§22.1 / §28.5): synthesizes the Pathom resolvers that Fulcro RAD MINTS
   at runtime for registered identity attributes, so their (otherwise invisible) output
   keywords participate in the Edge-B attribute graph — and so competing producers of the same
   output keyword surface as COLLISIONS.

   The gap this closes: when an identity attribute (`ao/identity? true`, e.g. `:company/id`) is
   listed in RAD's `auto-resolved-attributes` registry, RAD mints an id-resolver whose OUTPUTS
   are every sibling attribute of that entity — every attr declaring `ao/identities #{:company/id}`.
   None of those minted outputs appear as text, so static Edge-B never sees them. If a
   hand-written resolver already produces one of those keywords (e.g. `:company/dataico-tasks`),
   you get TWO producers of one output keyword → nondeterministic Pathom resolution. This plugin
   makes the minted producer visible and flags the collision.

   All static: it works from `defattr` declarations (the §22 `declared` profile map) and a
   rewrite-clj parse of the registry def. No running Pathom `index-oir` (§28.6 — never built)."
  (:require
   [clojure.set :as set]
   [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------------------
;; Identity / sibling indices from the T5 `declared` profile map
;; ---------------------------------------------------------------------------

(defn defattr-index
  "Returns RAD identity indices derived from the §22 `declared` profile map
   (`{var-sym {:declares-keyword :identities :identity? …}}`):

     * `:var->kw`            `{attr-var attr-keyword}`
     * `:identity-vars`      `#{attr-var}` — defattrs with `ao/identity? true`
     * `:ns->identity-kw`    `{model-ns-symbol identity-keyword}` — the identity each ns defines
     * `:identity->siblings` `{identity-keyword #{attr-keyword}}` — every attr declaring that
                             identity in its `ao/identities` (the minted id-resolver's outputs)."
  [declared]
  (let [attrs (filter (fn [[_ p]] (keyword? (:declares-keyword p))) declared)]
    {:var->kw (into {} (map (fn [[s p]] [s (:declares-keyword p)])) attrs)
     :identity-vars (into #{} (keep (fn [[s p]] (when (:identity? p) s)) attrs))
     :ns->identity-kw (into {} (keep (fn [[s p]]
                                       (when (and (:identity? p) (namespace s))
                                         [(symbol (namespace s)) (:declares-keyword p)]))
                                     attrs))
     :identity->siblings
     (reduce (fn [m [_ p]]
               (let [k   (:declares-keyword p)
                     ids (:identities p)]
                 ;; The auto id-resolver mints only STORED attrs (those with an `ao/schema`);
                 ;; purely computed attrs (no schema, resolved by their own resolver) are not
                 ;; minted, so they cannot collide. `ao/identities` is usually a literal set but
                 ;; may be a symbol/var ref — only enumerate when it is a collection of keywords.
                 (if (some? (:schema p))
                   (reduce (fn [m id] (update m id (fnil conj #{}) k))
                           m (when (coll? ids) (filter keyword? ids)))
                   m)))
             {} attrs)}))

;; ---------------------------------------------------------------------------
;; rewrite-clj parse of model.cljc: ns name + aliases + registry symbols
;; ---------------------------------------------------------------------------

(defn- top-level
  "Returns the zipper loc of the first top-level list whose head symbol is `head` and whose
   (optional) second element equals `name-sym` (when given), or nil."
  ([root head] (top-level root head nil))
  ([root head name-sym]
   (loop [loc root]
     (cond
       (nil? loc) nil
       (and (z/list? loc)
            (= head (some-> loc z/down z/sexpr))
            (or (nil? name-sym) (= name-sym (some-> loc z/down z/right z/sexpr))))
       loc
       :else (recur (z/right loc))))))

(defn- require-aliases
  "Returns `{alias-symbol target-ns-symbol}` from the `:require` clause of `ns-form` (sexpr)."
  [ns-form]
  (let [req (->> (rest ns-form) (filter seq?) (filter #(= :require (first %))) first)]
    (into {} (keep (fn [e]
                     (when (vector? e)
                       (let [i (.indexOf ^java.util.List e :as)]
                         (when (>= i 0) [(nth e (inc i)) (first e)]))))
                   (rest req)))))

(defn parse-registry
  "Parses RAD model file `model-clj-path` and returns
   `{:ns model-ns :aliases {alias ns} :registry-symbols #{sym} :line registry-def-line}`
   for the `(def <registry-def> …)` registry (default `auto-resolved-attributes`). `#_` discards
   are dropped (sexpr semantics), so commented-out entries do not count. nil if not found."
  ([model-clj-path] (parse-registry model-clj-path 'auto-resolved-attributes))
  ([model-clj-path registry-def]
   (let [root    (z/of-file model-clj-path {:track-position? true})
         ns-loc  (top-level root 'ns)
         reg-loc (top-level root 'def registry-def)]
     (when (and ns-loc reg-loc)
       (let [ns-form (z/sexpr ns-loc)]
         {:ns               (second ns-form)
          :aliases          (require-aliases ns-form)
          :line             (first (z/position reg-loc))
          :registry-symbols (into #{} (filter symbol?) (tree-seq coll? seq (z/sexpr reg-loc)))})))))

(defn- resolve-sym
  "Resolves `sym` to a fully-qualified symbol through the `aliases` map (alias → ns)."
  [aliases sym]
  (if-let [np (namespace sym)]
    (if-let [target (get aliases (symbol np))] (symbol (str target) (name sym)) sym)
    sym))

;; ---------------------------------------------------------------------------
;; registered identities -> minted outputs -> collisions
;; ---------------------------------------------------------------------------

(defn registered-identities
  "Returns the set of identity keywords whose RAD id-resolver the registry mints. A registry
   symbol that resolves to an identity defattr contributes that var's keyword; a
   `<ns>/attributes` (or `/auto-resolved-attributes`) sub-registry reference contributes that
   entity ns's identity keyword. Generic: never hardcodes an entity."
  [registry-symbols aliases {:keys [identity-vars var->kw ns->identity-kw]}]
  (into #{}
        (keep (fn [sym]
                (let [fq (resolve-sym aliases sym)]
                  (cond
                    (contains? identity-vars fq) (var->kw fq)
                    (#{"attributes" "auto-resolved-attributes"} (name sym))
                    (get ns->identity-kw (some-> (namespace fq) symbol))
                    :else nil))))
        registry-symbols))

(defn minted-outputs
  "Returns the set of keywords the minted id-resolvers produce: the union, over every
   `registered-id`, of that identity's siblings (`identity->siblings`)."
  [registered-ids identity->siblings]
  (into #{} (mapcat #(get identity->siblings % #{})) registered-ids))

(defn direct-resolver-producers
  "Returns `{K #{resolver-var}}` — for each keyword, the Pathom RESOLVERS that DIRECTLY resolve
   it (`K` is a TOP-LEVEL `::output` key, `:output-keys`). Built from the §22 `declared` map;
   only resolver profiles (`:source?`, non-write) contribute. Top-level (not nested) is the
   key distinction: a resolver outputting `{:b [:c]}` resolves `:b`, not `:c`."
  [declared]
  (reduce (fn [m [s p]]
            (if (and (:source? p) (not (get-in p [:io :write?])))
              (reduce (fn [m k] (update m k (fnil conj #{}) s)) m (:output-keys p))
              m))
          {} declared))

(defn collisions
  "Returns `[{:output-keyword K :producers [{:file :line :sym :kind}…]}]` for every minted
   output keyword that ALSO has a hand-written RESOLVER directly producing `K` — the bug (two
   producers of one auto-resolved output keyword → nondeterministic Pathom resolution).

   `direct-producers` is `{K #{resolver-var}}` (`direct-resolver-producers`). Mutations, the
   attribute's own `defattr` declaration, UI forms, and nested (sub-query) outputs are NOT
   competitors. `var-loc` maps a producer var to `{:file :line}`. Sorted by keyword."
  [minted-kws direct-producers registry-loc registry-sym var-loc]
  (->> minted-kws
       (keep (fn [K]
               (let [others (disj (get direct-producers K #{}) registry-sym)]
                 (when (seq others)
                   {:output-keyword K
                    :producers (into [(assoc registry-loc :sym registry-sym :kind :minted)]
                                     (map (fn [s] (assoc (or (var-loc s) {}) :sym s :kind :hand-written)))
                                     (sort others))}))))
       (sort-by :output-keyword)
       vec))

;; ---------------------------------------------------------------------------
;; public entry
;; ---------------------------------------------------------------------------

(defn minted-resolvers
  "Top-level analysis. Given the §22 `declared` profile map, the RAD model file path, and the
   base `attribute-graph`, returns:

     * `:registry-sym`   the registry var (a SOURCE of the minted keywords; seeds Edge B when
                         the registry changes)
     * `:registry-file`  / `:registry-line`
     * `:registered-identities` `#{identity-keyword}`
     * `:minted-keywords` `#{output-keyword}` (feed these into Edge B as producer outputs)
     * `:collisions`     `[{:output-keyword :producers […]}]` (the headline feature)

   Returns nil when the model file has no registry def. `var-loc` maps a producer var-sym to
   `{:file :line}` (from the static analysis) for collision provenance."
  [{:keys [declared model-clj-path model-rel var-loc registry-def prior-registry-symbols]
    :or   {registry-def 'auto-resolved-attributes}}]
  (when-let [{:keys [ns aliases line registry-symbols]} (parse-registry model-clj-path registry-def)]
    (let [idx          (defattr-index declared)
          registered   (registered-identities registry-symbols aliases idx)
          ;; diff-scoping: identities the change ADDED to the registry (vs the prior tree). Their
          ;; collisions are the ones THIS change introduced — surfaced first.
          newly        (when prior-registry-symbols
                         (set/difference registered (registered-identities prior-registry-symbols aliases idx)))
          minted-kws   (minted-outputs registered (:identity->siblings idx))
          introduced-kws (minted-outputs (or newly #{}) (:identity->siblings idx))
          registry-sym (symbol (str ns) (str registry-def))
          registry-loc {:file (or model-rel model-clj-path) :line line}
          direct-prod  (direct-resolver-producers declared)
          cols         (->> (collisions minted-kws direct-prod registry-loc
                                        registry-sym (or var-loc (constantly nil)))
                            (mapv (fn [c] (assoc c :introduced? (contains? introduced-kws (:output-keyword c)))))
                            ;; introduced (this-change) collisions first, then by keyword
                            (sort-by (juxt (complement :introduced?) (comp str :output-keyword)))
                            vec)]
      {:registry-sym          registry-sym
       :registry-file         (or model-rel model-clj-path)
       :registry-line         line
       :registered-identities registered
       :newly-registered      newly
       :minted-keywords       minted-kws
       :collisions            cols})))
