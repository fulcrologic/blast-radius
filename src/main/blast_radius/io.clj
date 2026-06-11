(ns blast-radius.io
  "Tool 3 — per-var DIRECT (syntactic) I/O profiling (design §8 / §20).

   Produces the `:inputs/:outputs/:reads/:io/:source?` fields of the per-var profile
   (§17) from SYNTAX ALONE — no flow analysis. There are two independent signals:

     1. A keyword's syntactic ROLE inside a var's source form, recovered by re-reading
        the form with a rewrite-clj zipper (`classify-roles`). clj-kondo records keyword
        occurrences with file/row but NOT their role (key-position vs lookup vs arg
        destructure), so we re-read the form positionally à la fulcro-spec `get-source`.
        - inputs  : qualified kws destructured in the ARG VECTOR (`{:keys [...]}`,
                    `{ns/keys [...]}`, `:as`) — the keys the fn EXPECTS.
        - outputs : qualified kw in map-literal KEY position, or the first kw arg of
                    `assoc`/`assoc-in`/`update`/`update-in` — the PRODUCE signal (§8).
        - reads   : qualified kw in LOOKUP position — `(:k x)`, `(get x :k)`, or a
                    destructure off a NON-arg local — the CONSUME signal (§8).
        Ambiguous keyword => classified as BOTH read and output (recall-first, §8).

     2. The var's DIRECT outgoing calls matched against the curated sink registry
        (`var-io-from-usages` against `sinks.edn`). A direct call to a `:write` sink sets
        `:write?`; a `:read` sink sets `:read?`; a `:source?` read sink sets `:source?`.
        TRANSITIVE reach to sinks (forward call walk, §10/§21.4) is the blast generator's
        job, NOT this layer's — here `:writes` is left empty for the transitive pass to
        fill.

   `:reads`/`:writes` (the I/O-coupling view) and `:inputs`/`:outputs` (the schema view)
   intentionally diverge (§17): a fn can produce `:invoice/total` into a map it never
   persists (an output, not a write)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [rewrite-clj.zip :as z]))

;;; ----------------------------------------------------------------------------
;;; Sink registry
;;; ----------------------------------------------------------------------------

(def ^:private default-sinks-resource
  "Classpath resource holding the curated sink registry (§13/§20)."
  "sinks.edn")

(defn read-sinks
  "Returns the curated sink registry read from `file` (a path string, java.io.File, or
   any `clojure.java.io/reader`-able source). The registry is a map
   `{fq-sym {:io #{:read|:write} :source? bool :pattern-arg n}}`, where each key is a
   fully-qualified callee symbol (alias-resolved). See `resources/sinks.edn`."
  [file]
  (with-open [r (io/reader file)]
    (edn/read (java.io.PushbackReader. r))))

(defn load-sinks
  "Returns the curated sink registry from the `sinks.edn` classpath resource (§13/§20).

   The registry maps fully-qualified callee symbols to their I/O profile
   `{:io #{:read|:write} :source? bool :pattern-arg n}`. Sink keys are the RESOLVED
   fully-qualified symbols (e.g. `datomic.api/transact`); a var's outgoing call is matched
   by reconstructing its resolved callee symbol from the analysis `:to`/`:name`
   (clj-kondo already resolves aliases in the `:to` namespace, §307)."
  []
  (read-sinks (io/resource default-sinks-resource)))

;;; ----------------------------------------------------------------------------
;;; Syntactic role classification (rewrite-clj zipper over the var's source form)
;;; ----------------------------------------------------------------------------

(defn- qualified-keyword-node?
  "Returns true when zipper location `zloc` is a qualified-keyword token."
  [zloc]
  (and (= :token (z/tag zloc))
       (let [s (z/sexpr zloc)]
         (and (keyword? s) (some? (namespace s))))))

(defn- keys-destructuring-modifier?
  "Returns true when `kw` is a `:keys`/`ns/keys` destructuring MODIFIER (it names a
   destructure form, not a real attribute), e.g. `:invoice/keys`."
  [kw]
  (= "keys" (name kw)))

(defn- destructure-kws-from-sexpr
  "Returns the set of qualified attribute keywords destructured anywhere within the
   destructure-binding data `form` (a vector/map sexpr from an arg vector): namespaced
   `:keys` (`{:invoice/keys [id]}` => `:invoice/id`), and `:as`-style associative
   destructure keys (`{local :invoice/id}`). Recurses into nested destructures."
  [form]
  (cond
    (map? form)
    (reduce-kv
     (fn [acc k v]
       (cond
         ;; namespaced/plain :keys modifier: {:invoice/keys [id]} or {:keys [...]}
         (and (keyword? k) (= "keys" (name k)) (vector? v))
         (if-let [ns* (namespace k)]
           (into acc (comp (filter simple-symbol?) (map #(keyword ns* (name %)))) v)
           acc)
         ;; associative destructure {local :invoice/id} — value is the source key
         (qualified-keyword? v)
         (conj (into acc (destructure-kws-from-sexpr k)) v)
         :else (into acc (destructure-kws-from-sexpr k))))
     #{} form)

    (vector? form)
    (into #{} (mapcat destructure-kws-from-sexpr) form)

    :else #{}))

(defn- collect-arg-destructure-kws
  "Returns the set of qualified attribute keywords destructured within the arg-vector
   zipper `arg-zloc` (§20 INPUTS). Reads the vector as data and delegates to
   `destructure-kws-from-sexpr` for the structural walk."
  [arg-zloc]
  (destructure-kws-from-sexpr (z/sexpr arg-zloc)))

(defn- arg-destructure-inputs
  "Returns the set of qualified input keywords from the `defn`/`fn` arg vector(s) of the
   top form at `root-zloc`. Handles single-arity `(defn f [args] …)` and multi-arity
   `(defn f ([args] …) ([args] …))` by collecting destructures from EVERY vector child of
   the defn that is an argument vector."
  [root-zloc]
  (let [collect (fn [acc arg-z]
                  (into acc (collect-arg-destructure-kws arg-z)))]
    (loop [z (z/down root-zloc)
           acc #{}]
      (cond
        (or (nil? z) (z/end? z)) acc
        ;; arg vector directly under the form (single-arity)
        (= :vector (z/tag z)) (recur (z/right z) (collect acc z))
        ;; arity list `([args] …)` (multi-arity): its first child is the arg vector
        (= :list (z/tag z))   (let [v (z/down z)]
                                (recur (z/right z)
                                       (if (and v (= :vector (z/tag v)))
                                         (collect acc v)
                                         acc)))
        :else (recur (z/right z) acc)))))

(def ^:private produce-fns
  "Fns whose FIRST keyword argument is in PRODUCE (key-write) position (§8/§20)."
  '#{assoc assoc-in update update-in clojure.core/assoc clojure.core/assoc-in
     clojure.core/update clojure.core/update-in})

(def ^:private lookup-fns
  "Fns whose first non-collection keyword argument is a READ (lookup) (§20)."
  '#{get get-in clojure.core/get clojure.core/get-in})

(defn- list-head-sym
  "Returns the head symbol of list zipper `list-zloc`, or nil when its head is not a
   symbol token."
  [list-zloc]
  (let [h (z/down list-zloc)]
    (when (and h (= :token (z/tag h)) (symbol? (z/sexpr h)))
      (z/sexpr h))))

(defn- map-key-position?
  "Returns true when keyword location `kw-zloc` sits in a map-literal KEY position, i.e.
   its parent is a `:map` node and it is at an even (key) index among the map's children."
  [kw-zloc]
  (when-let [parent (z/up kw-zloc)]
    (when (= :map (z/tag parent))
      ;; count sexpr-bearing siblings to the left; even => key position
      (loop [z (z/down parent), i 0]
        (cond
          (nil? z) false
          (= (z/node z) (z/node kw-zloc)) (even? i)
          :else (recur (z/right z) (inc i)))))))

(defn- kw-arg-of-call?
  "Returns true when keyword location `kw-zloc` is a keyword ARGUMENT (not the head) of a
   call to one of `head-syms`. For `assoc`/`update` family calls the key keyword sits at
   arg position 2 (after the target collection), so we accept any keyword argument of such
   a call as a produce/key signal (§8/§20)."
  [kw-zloc head-syms]
  (when-let [parent (z/up kw-zloc)]
    (when (= :list (z/tag parent))
      (let [head (z/down parent)]
        (and head
             (= :token (z/tag head))
             (symbol? (z/sexpr head))
             (contains? head-syms (z/sexpr head))
             ;; the keyword is an argument, not the call head
             (not= (z/node kw-zloc) (z/node head)))))))

(defn- lookup-position?
  "Returns true when keyword location `kw-zloc` is in LOOKUP (read) position: it is the
   HEAD of a list `(:k x)`, or the keyword argument of `get`/`get-in`."
  [kw-zloc]
  (or
    ;; (:invoice/total x) — kw is the list head
   (when-let [parent (z/up kw-zloc)]
     (and (= :list (z/tag parent))
          (= (z/node kw-zloc) (z/node (z/down parent)))))
    ;; (get x :invoice/total) — kw arg to a lookup fn (any position after head)
   (when-let [parent (z/up kw-zloc)]
     (and (= :list (z/tag parent))
          (when-let [head (list-head-sym parent)]
            (contains? lookup-fns head))))))

(defn- produce-position?
  "Returns true when keyword location `kw-zloc` is in PRODUCE (output) position: map-literal
   key, or first kw arg of `assoc`/`assoc-in`/`update`/`update-in` (§8/§20)."
  [kw-zloc]
  (or (map-key-position? kw-zloc)
      (kw-arg-of-call? kw-zloc produce-fns)))

(defn- classify-occurrence
  "Returns `#{:output}` / `#{:read}` / `#{:output :read}` for the qualified keyword at
   zipper location `kw-zloc`, by syntactic position. Defaults (when neither a clear produce
   nor a clear lookup position is detected) to BOTH (recall-first, §8)."
  [kw-zloc]
  (let [produce? (produce-position? kw-zloc)
        lookup?  (lookup-position? kw-zloc)]
    (cond
      (and produce? (not lookup?)) #{:output}
      (and lookup? (not produce?)) #{:read}
      :else #{:output :read})))

(defn- classify-roles*
  "Implementation of `classify-roles` over an already-parsed top-form zipper `root`."
  [root]
  (let [inputs    (arg-destructure-inputs root)
        ;; keyword occurrences in the BODY (skip those inside the arg vector(s), which are
        ;; inputs already accounted for). We zip-walk the whole form and skip occurrences
        ;; whose role is purely arg-destructure (handled by `inputs`).
        roles     (loop [z (z/down root)
                         acc {:outputs #{} :reads #{}}]
                    (cond
                      (or (nil? z) (z/end? z)) acc
                      (qualified-keyword-node? z)
                      (let [kw (z/sexpr z)]
                        (if (or (contains? inputs kw)
                                (keys-destructuring-modifier? kw))
                          ;; an input destructure key or a :keys modifier — not a body role
                          (recur (z/next z) acc)
                          (let [cls (classify-occurrence z)]
                            (recur (z/next z)
                                   (cond-> acc
                                     (cls :output) (update :outputs conj kw)
                                     (cls :read)   (update :reads conj kw))))))
                      :else (recur (z/next z) acc)))]
    {:inputs  inputs
     :outputs (:outputs roles)
     :reads   (:reads roles)}))

(defn classify-roles
  "Returns `{:inputs #{kw} :outputs #{kw} :reads #{kw}}` for the var whose source is
   `var-form` (a string of Clojure source, e.g. a top-level `defn`), classifying each
   qualified-keyword occurrence by its SYNTACTIC ROLE via a rewrite-clj zipper (§8/§20):

     * `:inputs`  - qualified kws destructured in the ARG VECTOR (`{:keys [...]}`,
                    `{ns/keys [...]}`, `:as`/associative destructure).
     * `:outputs` - qualified kw in map-literal KEY position, or a keyword arg of
                    `assoc`/`assoc-in`/`update`/`update-in` (PRODUCE signal).
     * `:reads`   - qualified kw in LOOKUP position (`(:k x)`, `(get x :k)`).

   Keywords whose role is ambiguous are classified as BOTH `:reads` and `:outputs`
   (recall-first, §8). `:inputs` are EXCLUDED from `:reads`/`:outputs` (a key destructured
   from the args is an expected input, not a body-level produce/read).

   Returns empty role sets when `var-form` is not parseable as a single Clojure form (e.g.
   a row-range slice that split a reader conditional) — recall-first; those keywords are
   still attributed at file level by `blast-radius.keyword-index`."
  [var-form]
  (let [root (try (z/of-string var-form) (catch Exception _ nil))]
    (if (nil? root)
      {:inputs #{} :outputs #{} :reads #{}}
      (classify-roles* root))))

;;; ----------------------------------------------------------------------------
;;; Direct sink coupling (var's outgoing calls vs sinks.edn)
;;; ----------------------------------------------------------------------------

(defn- callee-sym
  "Returns the resolved fully-qualified callee symbol for call `usage`. Accepts the
   normalized shape where `:to` is ALREADY the combined, wrapper-canonicalized fq symbol
   (the `analysis/normalize` contract), and falls back to combining a bare callee namespace
   `:to` with `:name`/`:callee` for raw clj-kondo usages."
  [{:keys [to name callee]}]
  (cond
    (qualified-symbol? to) to
    (and to (or callee name)) (symbol (str to) (str (or callee name)))
    :else to))

(defn var-io-from-usages
  "Returns the DIRECT I/O coupling of `var-sym` from its outgoing `call-usages`, by matching
   each call's resolved callee symbol against `sinks` (the `load-sinks` registry). Returns
   `{:read? :write? :source? :sinks [fq-sym …]}`.

   Only DIRECT calls are considered — transitive reach to sinks (the forward call walk of
   §10/§21.4) is the blast generator's responsibility, not this layer's. `call-usages` is
   the FULL normalized `:call-usages`; we select those whose caller (`:from-var`/`:from`)
   is `var-sym`."
  [var-sym call-usages sinks]
  (let [var-ns   (some-> (namespace var-sym))
        var-name (name var-sym)
        mine?    (fn [{:keys [from from-var]}]
                   (and (= var-name (str from-var))
                        (or (nil? var-ns) (= var-ns (str from)))))
        matched  (into []
                       (comp
                        (filter mine?)
                        (keep (fn [u]
                                (let [c (callee-sym u)]
                                  (when-let [sink (get sinks c)]
                                    [c sink])))))
                       call-usages)
        ios      (into #{} (mapcat (comp :io second)) matched)]
    {:read?   (contains? ios :read)
     :write?  (contains? ios :write)
     :source? (boolean (some (comp :source? second) matched))
     :sinks   (into [] (comp (map first) (distinct)) matched)}))

;;; ----------------------------------------------------------------------------
;;; Source re-reading by row-range (à la fulcro-spec get-source)
;;; ----------------------------------------------------------------------------

(defn- absolute-path
  "Returns the absolute path string for a var-def `:filename` (relative to the project
   `src-root`'s parent when relative). clj-kondo records filenames relative to the lint
   root (e.g. `src/main/dataico/…`); `src-root` is the dataico project root that those
   paths are relative to."
  [src-root filename]
  (let [f (io/file filename)]
    (if (.isAbsolute f)
      (.getPath f)
      (.getPath (io/file src-root filename)))))

(defn source-by-rows
  "Returns the source TEXT of the form spanning rows `[row end-row]` (1-based, inclusive)
   in the file at `path`, à la fulcro-spec `get-source`. Returns nil when the file cannot
   be read."
  [path row end-row]
  (when (and path row)
    (let [end (or end-row row)]
      (with-open [r (io/reader path)]
        (let [lines (into []
                          (comp (drop (dec row)) (take (inc (- end row))))
                          (line-seq r))]
          (when (seq lines)
            (str/join "\n" lines)))))))

(defn symbol-io
  "Returns `{var-sym profile}` — the per-var DIRECT I/O profile (§17) for every var
   definition in `normalized` (the output of `analysis/normalize`). Merges `classify-roles`
   (re-reading each var's source by `[:row :end-row]` from `src-root`) with
   `var-io-from-usages` (direct sink coupling against `sinks`).

   Each profile has the §17 shape:

     `{:sym :inputs :outputs :reads :writes :io :source? :provenance}`

   `:io` is `{:read? :write? :sinks […]}`. `:writes` is left EMPTY here — it is filled
   transitively by the blast generator (forward call walk to write sinks, §21.4).
   `:provenance` tags every classified keyword `#{:syntactic}`.

   `opts`:
     * `:src-root` - (required) the project root the analysis `:filename`s are relative to
                     (e.g. the dataico checkout path)."
  [normalized sinks {:keys [src-root]}]
  (let [call-usages (:call-usages normalized)]
    (reduce
     (fn [acc {:keys [sym filename row end-row]}]
       (let [src   (source-by-rows (absolute-path src-root filename) row end-row)
             roles (if src (classify-roles src) {:inputs #{} :outputs #{} :reads #{}})
             io*   (var-io-from-usages sym call-usages sinks)
             kws   (into (into (:inputs roles) (:outputs roles)) (:reads roles))]
         (assoc acc sym
                {:sym        sym
                 :inputs     (:inputs roles)
                 :outputs    (:outputs roles)
                 :reads      (:reads roles)
                 :writes     #{}
                 :io         (select-keys io* [:read? :write? :sinks])
                 :source?    (:source? io*)
                 :provenance (zipmap kws (repeat #{:syntactic}))})))
     {}
     (:vars normalized))))
