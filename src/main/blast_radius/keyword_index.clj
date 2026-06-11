(ns blast-radius.keyword-index
  "Row-range attribution of qualified-keyword occurrences to their enclosing var (§20/§18).

   Attribute every occurrence to the var-definition whose `[:row :end-row]` range encloses
   it (top-level forms do not nest, so at most one var encloses an occurrence). We use
   row-range rather than clj-kondo's `:from-var` for three verified reasons (measured on the
   dataico tree, clj-kondo v2026.05.25):

     1. VERSION-INDEPENDENT — does not bet on clj-kondo populating `:from-var`. (Note: this
        version DOES set `:from-var` for top-level `def`/`defn`, so the two indices agree on
        ~60.4k of 60.5k (kw,var) pairs; the earlier worry that top-level pattern/registry
        defs are dropped by `:from-var` was a mistaken premise.)
     2. Recovers the ~84 occurrences clj-kondo leaves with no `:from-var` but inside a
        tracked var — chiefly `defrecord`/`deftype` bodies.
     3. Surfaces ORPHANS explicitly: ~14.4k occurrences sit in no def at all (`ns` forms,
        `defmethod`, top-level registration calls). Those are kept at FILE level in
        `:kw->files` (recall) and reported in `:orphans` rather than silently lost."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(defn qualified-keyword-rows
  "Returns the qualified-keyword occurrences in clj-kondo `analysis` as
   `{:kw :filename :row}` maps. Drops unqualified keywords and `:X/keys` destructuring
   modifiers (which name a destructure form, not a real attribute)."
  [analysis]
  (into []
        (comp
         (filter :ns)
         (remove :keys-destructuring-ns-modifier)
         (map (fn [{:keys [ns name filename row]}]
                {:kw (keyword (str ns) name) :filename filename :row row})))
        (:keywords analysis)))

(defn var-defs-by-file
  "Returns `{filename […{:sym :row :end-row}]}` from `analysis`, each file's vector sorted
   by `:row`. `:end-row` falls back to `:row` for single-line defs."
  [analysis]
  (-> (reduce (fn [m {:keys [ns name filename row end-row]}]
                (update m filename (fnil conj [])
                        {:sym (symbol (str ns) (str name)) :row row :end-row (or end-row row)}))
              {} (:var-definitions analysis))
      (update-vals #(vec (sort-by :row %)))))

(defn enclosing-var
  "Returns the `:sym` of the def in `sorted-defs` (a file's sorted var-defs) whose
   `[:row :end-row]` range contains the occurrence at `at-row`, or nil when the occurrence
   sits outside any def (e.g. inside the `ns` form)."
  [sorted-defs at-row]
  (some (fn [{:keys [sym row end-row]}]
          (when (<= row at-row end-row) sym))
        sorted-defs))

(defn keyword-index
  "Builds the qualified-keyword index from a clj-kondo `analysis`, attributing each
   occurrence to its enclosing var by ROW RANGE (design §20). Returns:

     * `:kw->vars`  `{kw #{enclosing-var-sym}}`
     * `:kw->files` `{kw #{filename}}`
     * `:var->kws`  `{var-sym #{kw}}`
     * `:orphans`   `#{kw}` occurrences with no enclosing var (ns-form / file-level)

   `:kw->vars`/`:var->kws` are the reader index the data edge (§10/§23) consumes."
  [analysis]
  (let [defs-by-file (var-defs-by-file analysis)]
    (reduce
     (fn [acc {:keys [kw filename row]}]
       (let [v (enclosing-var (get defs-by-file filename) row)]
         (cond-> (update-in acc [:kw->files kw] (fnil conj #{}) filename)
           v        (-> (update-in [:kw->vars kw] (fnil conj #{}) v)
                        (update-in [:var->kws v] (fnil conj #{}) kw))
           (nil? v) (update :orphans (fnil conj #{}) kw))))
     {:kw->vars {} :kw->files {} :var->kws {} :orphans #{}}
     (qualified-keyword-rows analysis))))

(defn read-analysis
  "Reads a clj-kondo analysis EDN file (the `{:analysis {…}}` map written with
   `:format :edn`) and returns its `:analysis` value."
  [edn-file]
  (with-open [r (io/reader edn-file)]
    (:analysis (edn/read (java.io.PushbackReader. r)))))
