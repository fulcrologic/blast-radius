(ns blast-radius.plugins.cud-side-effects
  "Dispatch-table plugin (design §22.1): recovers the synthetic call edges hidden in
   dataico's data-driven CUD side-effect registry, `dataico.data-model.base/entities`.

   The registry is a literal map `{<id-keyword> <config> …}` where `<config>` is either an
   inline map or a top-level `def` in the same file, and each config holds
   `:side-effects {:create [`fq-sym …] :save […] :delete […]}` — syntax-quoted
   fully-qualified symbols. At runtime a generic CUD runner resolves and invokes those
   symbols (`keyword->fn` → `(f db {:entity doc})`), so clj-kondo sees NO runner→side-effect
   call edge — a §10/§6 dispatched-entry-point hole.

   This plugin reads the declaration statically (rewrite-clj, no evaluation) and emits
   synthetic edges `{:cud/entity <id-kw> :cud/verb <verb>} → <fq-sym>`, converting the hole
   into covered edges. With those edges on the graph, a side-effect like `…/add-analytics`
   (which reads invoice items and writes the cached `:doc.analytics/total`) is reachable, and
   the derived-data cascade (§23 step 4) connects end-to-end.

   Navigation is hardened against `::aliased` keywords (which break `z/sexpr` without ns
   context): we match keys by `z/string`, and only `z/sexpr` plain tokens we know are safe."
  (:require
   [rewrite-clj.node :as n]
   [rewrite-clj.zip :as z]))

(def ^:private cud-verbs #{:create :save :delete})

(defn node->sym
  "Returns the fully-qualified symbol named by zipper node `zloc`, unwrapping `quote` /
   syntax-quote wrappers; nil if `zloc` is not (a wrapper around) a symbol token."
  [zloc]
  (case (n/tag (z/node zloc))
    :token (let [s (z/sexpr zloc)] (when (symbol? s) s))
    (:syntax-quote :quote) (some-> zloc z/down node->sym)
    nil))

(defn top-level-defs
  "Returns `{<def-name-symbol> <value-zloc>}` for every top-level `(def NAME VALUE)` reachable
   from the file-root zipper `root` (positioned at the first form, à la `z/of-file`).
   `<value-zloc>` is the zipper of the def's value form."
  [root]
  (loop [loc root, acc {}]
    (if (nil? loc)
      acc
      (let [acc' (if (and (z/list? loc) (= 'def (some-> loc z/down z/sexpr)))
                   (assoc acc (-> loc z/down z/right z/sexpr) (-> loc z/down z/right z/right))
                   acc)]
        (recur (z/right loc) acc')))))

(defn- map-pairs
  "Returns a vector of `[key-zloc val-zloc]` for the map node at `map-zloc` (nil if not a map)."
  [map-zloc]
  (when (and map-zloc (z/map? map-zloc))
    (loop [loc (z/down map-zloc), acc []]
      (if (nil? loc)
        acc
        (let [v (z/right loc)]
          (recur (some-> v z/right) (conj acc [loc v])))))))

(defn- vector-children
  "Returns the child zippers of vector node `vec-zloc` (empty when not a vector)."
  [vec-zloc]
  (when (and vec-zloc (z/vector? vec-zloc))
    (loop [c (z/down vec-zloc), acc []]
      (if (nil? c) acc (recur (z/right c) (conj acc c))))))

(defn- side-effects
  "Returns `{<verb> [fq-sym …]}` for the `:side-effects` block of config node `cfg-zloc`
   (a map zloc), or nil. Only the four known CUD verbs are read; symbol vectors only."
  [cfg-zloc]
  (when-let [se-val (some (fn [[k v]] (when (= ":side-effects" (z/string k)) v))
                          (map-pairs cfg-zloc))]
    (into {}
          (keep (fn [[verb-loc vec-loc]]
                  (let [verb (when (= :token (n/tag (z/node verb-loc))) (z/sexpr verb-loc))]
                    (when (cud-verbs verb)
                      [verb (into [] (keep node->sym) (vector-children vec-loc))]))))
          (map-pairs se-val))))

(defn side-effect-edges
  "Reads the CUD side-effect registry from the dataico model file at `base-clj-path` and
   returns a vector of synthetic edges:

     `{:from {:cud/entity <id-kw> :cud/verb <verb>} :to <fq-sym>}`

   Each edge means: a CUD `<verb>` on the document type identified by `<id-kw>` invokes
   `<fq-sym>` (via runtime dispatch clj-kondo cannot see)."
  [base-clj-path]
  (let [root     (z/of-file base-clj-path)
        defs     (top-level-defs root)
        entities (get defs 'entities)]
    (into []
          (mapcat
           (fn [[key-zloc val-zloc]]
             (let [id-kw (z/sexpr key-zloc)             ; entity id-keyword (e.g. :invoice/id)
                   cfg   (if (= :token (n/tag (z/node val-zloc)))
                           (get defs (z/sexpr val-zloc)) ; symbol → local def value
                           val-zloc)]                    ; inline map
               (for [[verb syms] (side-effects cfg)
                     sym         syms]
                 {:from {:cud/entity id-kw :cud/verb verb} :to sym}))))
          (map-pairs entities))))
