(ns blast-radius.plugins
  "Tool 5 — declaration plugins (design §22 / §22.1 / §28 / §29).

   For macros that *declare* their I/O (Pathom `::pco/input`/`::pco/output`, RAD `defattr`,
   Fulcro `defmutation`) the data-layer schema is **stated**, not inferred. This namespace
   exposes a multimethod `analyze-form`, keyed on the **fully-qualified symbol heading a
   top-level form**, that reads that declaration directly and returns an I/O profile (§17)
   to merge OVER inference (provenance `:declared`). Projects extend coverage by passing a
   `:macro-set` of FQ macro symbols in `ctx`, so app wrappers (dataico's
   `dataico.lib.pathom-wrappers/defresolver` / `…/defmutation`, which wrap the stock
   `com.wsscode` macros) are recognised — matching only the stock macros under-covers badly.

   A second plugin kind (§22.1) emits **synthetic call/data edges** from a declared dispatch
   table. `synthetic-edges` delegates to `blast-radius.plugins.cud-side-effects` to recover
   the CUD side-effect registry edges that clj-kondo cannot see; `analyze-form` may likewise
   return `:synthetic-edges` for table-bearing forms (e.g. RAD `ro/refinements`). Those edges
   are UNIONed into the call graph (never used to subtract — recall-first, §24)."
  (:require
   [blast-radius.plugins.cud-side-effects :as cud]
   [clojure.java.io :as io]
   [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------------------
;; EQL flattening (§22 / §28.3)
;; ---------------------------------------------------------------------------

(defn eql->keywords
  "Returns a vector of every keyword named anywhere in the EQL vector `eql-vec`: leaf
   property keys AND the keys of join maps, recursively flattening nested sub-queries.

   e.g. `[:invoice/total {:invoice/line-items [:line/id]}]` →
        `[:invoice/total :invoice/line-items :line/id]`.

   Idents, parameter forms `(:k {})`, unions `{:k {...}}`, and special symbols (`*`) are
   handled: only keyword nodes (join keys and leaf attrs) are collected. Used both for
   Pathom `::pco/output` and for client `defsc :query` flattening (§28.4)."
  [eql-vec]
  (letfn [(walk [x acc]
            (cond
              (keyword? x) (conj acc x)
              (map? x)     (reduce (fn [a [k v]]
                                     (walk v (walk k a)))
                                   acc x)
              (vector? x)  (reduce #(walk %2 %1) acc x)
              (seq? x)     (if (keyword? (first x))     ; param form (:k {p v})
                             (conj acc (first x))
                             (reduce #(walk %2 %1) acc x))
              :else        acc))]
    (into [] (walk eql-vec []))))

;; ---------------------------------------------------------------------------
;; resolve-fq — alias-following name resolution (§22 / §28.5)
;; ---------------------------------------------------------------------------

(defn resolve-fq
  "Returns the fully-qualified symbol that `sym` (the symbol heading a top-level form)
   denotes in the analysis namespace described by `ctx`, following the namespace aliases
   recorded for that file by clj-kondo. `ctx` keys consulted:

     * `:ns-aliases` - a map `{alias-symbol target-ns-symbol}` for the form's namespace
                       (from clj-kondo `:namespace-usages`).
     * `:refers`     - an optional map `{referred-name-symbol target-ns-symbol}` for
                       `:refer`-ed macros (dataico `:refer [defresolver defmutation]`).

   Resolution rules:
     * already-qualified `a/b` → resolve the namespace part through `:ns-aliases`.
     * bare `b` that is `:refer`-ed → `target-ns/b`.
     * otherwise the symbol is returned unchanged (let the multimethod miss → :default)."
  [sym {:keys [ns-aliases refers]}]
  (when (symbol? sym)
    (if-let [ns-part (namespace sym)]
      (let [target (get ns-aliases (symbol ns-part))]
        (if target (symbol (str target) (name sym)) sym))
      (if-let [target (get refers sym)]
        (symbol (str target) (name sym))
        sym))))

;; ---------------------------------------------------------------------------
;; multimethod
;; ---------------------------------------------------------------------------

(defmulti analyze-form
  "Dispatch on the fully-qualified macro symbol heading a top-level `form` (§22).

   `form` is the raw s-expression of a declarative var (re-read by `{:filename :row}`).
   `ctx` carries `{:ns-aliases :refers :macro-set :sym}` (the var's symbol under `:sym`).
   Returns an I/O profile map (§17) to MERGE OVER inference (provenance `:declared`), and/or
   a `:synthetic-edges` set to union into the call graph — or nil to fall through to
   T3/T4 inference.

   Dispatch value: clj-kondo's `:defined-by` (the FQ macro symbol) when present in `ctx`
   (authoritative — it already accounts for `:refer`/aliasing), else `resolve-fq` of the head
   symbol through `ctx`'s `:ns-aliases`/`:refers`."
  (fn [form ctx] (or (:defined-by ctx) (resolve-fq (first form) ctx))))

(defmethod analyze-form :default [_ _] nil)

;; --- shared parsing helpers (operate on raw s-expressions) -----------------

(defn- name-matches?
  "True when keyword-or-symbol `k` has `nm` as its name regardless of namespace, so
   `::pc/output`, `::pco/output`, `:output` and the RAD option symbol `ao/target` (read by
   rewrite-clj as a plain symbol when the `ao` alias is not loaded) all match by name."
  [k nm]
  (and (or (keyword? k) (symbol? k)) (= (name k) nm)))

(defn- config-map
  "Returns the Pathom/Fulcro/RAD config map of a declarative form, i.e. the first map literal
   appearing after the (optional docstring and) arg-vector, or nil."
  [form]
  (->> (rest form)
       (filter map?)
       first))

(defn- pco-key
  "Returns the value stored under the config key whose name is `nm` (ns-insensitive) in the
   given config `m`, or nil."
  [m nm]
  (some (fn [[k v]] (when (name-matches? k nm) v)) m))

(defn- arg-vector
  "Returns the first vector literal in `form` after the head symbol — the macro arg-vector
   (resolver `[env input]`, mutation `[params]`)."
  [form]
  (->> (rest form) (filter vector?) first))

(defn- destructured-keys
  "Returns a set of qualified keywords named by a destructuring `binding` form: handles
   `{:keys [a b]}`, namespaced `{:ns/keys [a b]}`, and explicit `{sym :some/kw}` entries.
   Used to recover a mutation's input attributes from its params destructure."
  [binding]
  (cond
    (map? binding)
    (reduce (fn [acc [k v]]
              (cond
                (and (keyword? k) (= "keys" (name k)) (vector? v))
                (let [ns (namespace k)]
                  (into acc (map (fn [s] (keyword ns (name s)))) v))

                (keyword? v) (conj acc v)
                :else        acc))
            #{} binding)
    :else #{}))

;; --- built-in methods ------------------------------------------------------

(defn eql->top-keys
  "Returns the TOP-LEVEL property/join keys of EQL `eql-vec` (not recursed): the keywords a
   resolver DIRECTLY resolves. `[:a {:b [:c]} (:e {})]` → `[:a :b :e]`. Distinct from
   `eql->keywords` (which flattens nested sub-queries): a resolver outputting `{:b [:c]}`
   resolves `:b`, not `:c` (`:c` is resolved on the nested entity by other resolvers)."
  [eql-vec]
  (into []
        (mapcat (fn [x]
                  (cond
                    (keyword? x) [x]
                    (map? x)     (keys x)
                    (seq? x)     (when (keyword? (first x)) [(first x)])
                    :else        nil)))
        eql-vec))

(defn- resolver-profile
  "Returns the declared I/O profile of a Pathom resolver `form` (§22): inputs from
   `::pco/input`/`::pc/input`, outputs = flattened `::pco/output`/`::pc/output` EQL, and
   `:output-keys` = the TOP-LEVEL output keys the resolver directly resolves (for collision
   detection). A resolver produces its outputs, so it is a data `:source?` and reads them."
  [form sym]
  (let [m   (config-map form)
        eql (or (pco-key m "output") [])]
    {:sym         sym
     :inputs      (set (pco-key m "input"))
     :outputs     (eql->keywords eql)
     :output-keys (set (eql->top-keys eql))
     :source?     true
     :io          {:read? true :write? false}
     :provenance  :declared}))

(defn- mutation-profile
  "Returns the declared I/O profile of a Fulcro/Pathom mutation `form` (§22): inputs from the
   params destructure UNIONed with any declared `::pc/input`; outputs from `::pc/output` EQL.
   A mutation writes (its action persists), so `:io {:write? true}`."
  [form sym]
  (let [m         (config-map form)
        params    (some-> (arg-vector form) first)
        destructd (destructured-keys params)]
    {:sym     sym
     :inputs  (into destructd (pco-key m "input"))
     :outputs (eql->keywords (or (pco-key m "output") []))
     :source? false
     :io      {:read? true :write? true}
     :provenance :declared}))

(defn- defattr-profile
  "Returns the dictionary-seed profile of a RAD `defattr` form (§22 / §28.5):
   `(defattr <sym> <k> <type> <options?>)`. `:declares-keyword` is the attribute keyword,
   `:ref-target` / `:identities` / `:identity?` / `:type` come from the options map
   (ns-insensitive keys). `:identity?` (RAD `ao/identity?`) marks the entity's identity
   attribute — the one RAD mints an auto id-resolver for (§28.5 / RAD resolver-minting)."
  [form sym]
  (let [[_ _attr-sym k type] form
        opts (config-map form)]
    {:sym              sym
     :declares-keyword k
     :ref-target       (pco-key opts "target")
     :identities       (pco-key opts "identities")
     :identity?        (boolean (pco-key opts "identity?"))
     :schema           (pco-key opts "schema")
     :type             type
     :provenance       :declared}))

;; Pathom 3 / 2 resolver wrappers. The dataico project-configurable :macro-set adds
;; dataico.lib.pathom-wrappers/defresolver (see analyze-forms), but built-ins cover stock.
(defmethod analyze-form 'com.wsscode.pathom3.connect.operation/defresolver [form ctx]
  (resolver-profile form (:sym ctx)))
(defmethod analyze-form 'com.wsscode.pathom.connect/defresolver [form ctx]
  (resolver-profile form (:sym ctx)))
(defmethod analyze-form 'dataico.lib.pathom-wrappers/defresolver [form ctx]
  (resolver-profile form (:sym ctx)))

(defmethod analyze-form 'com.fulcrologic.fulcro.mutations/defmutation [form ctx]
  (mutation-profile form (:sym ctx)))
(defmethod analyze-form 'com.wsscode.pathom.connect/defmutation [form ctx]
  (mutation-profile form (:sym ctx)))
(defmethod analyze-form 'dataico.lib.pathom-wrappers/defmutation [form ctx]
  (mutation-profile form (:sym ctx)))

(defmethod analyze-form 'com.fulcrologic.rad.attributes/defattr [form ctx]
  (defattr-profile form (:sym ctx)))

;; ---------------------------------------------------------------------------
;; driver — re-read declarative vars by {:filename :row} and dispatch
;; ---------------------------------------------------------------------------

(def ^:private default-macro-set
  "The built-in set of declarative macro FQ symbols recognised by `analyze-forms` when a
   project supplies no `:macro-set`. Projects SHOULD extend this with their own wrappers."
  '#{com.wsscode.pathom3.connect.operation/defresolver
     com.wsscode.pathom.connect/defresolver
     com.wsscode.pathom.connect/defmutation
     com.fulcrologic.fulcro.mutations/defmutation
     com.fulcrologic.rad.attributes/defattr
     dataico.lib.pathom-wrappers/defresolver
     dataico.lib.pathom-wrappers/defmutation})

(defn- ns-context
  "Returns `{:ns-aliases {alias target} :refers {referred target}}` for the file `filename`,
   derived from the clj-kondo `:namespace-usages` in `normalized`. Aliases come from `:alias`
   usages; refers from usages whose `:alias` is nil but which were `:refer`-ed (clj-kondo does
   not always emit refer-only ns-usages, so refers are best-effort and the file's `:refers`
   may be supplemented by the caller)."
  [normalized filename]
  (let [usages (filter #(= (str (:filename %)) (str filename))
                       (:namespace-usages normalized))]
    {:ns-aliases (into {} (keep (fn [{:keys [alias to]}]
                                  (when (and alias to) [alias to])))
                       usages)}))

(defn- form-at
  "Returns the s-expression of the top-level form starting at 1-based `row` in file
   `filename`, re-read with rewrite-clj (no evaluation). nil if no form starts there."
  [filename row]
  (loop [loc (z/of-file filename {:track-position? true})]
    (cond
      (nil? loc) nil
      :else
      (let [pos (z/position loc)]
        (cond
          (= (first pos) row) (z/sexpr loc)
          (and pos (> (first pos) row)) nil
          :else (recur (z/right loc)))))))

(defn analyze-forms
  "Returns `{var-sym declared-profile}` for every declarative var in `normalized` whose
   defining macro (clj-kondo `:defined-by`) is in `macro-set` (defaults to
   `default-macro-set`). Each matching var's top-level form is re-read by its
   `{:filename :row}` (rewrite-clj) and dispatched through `analyze-form`; non-nil results
   carry provenance `:declared`.

   `opts` keys:
     * `:src-root`  - directory prefix prepended to each var's (relative) `:filename` before
                      re-reading. Optional when var-defs already carry absolute filenames.
     * `:macro-set` - project-configurable set of FQ declarative macro symbols."
  [normalized {:keys [macro-set src-root] :as _opts}]
  (let [macro-set (or macro-set default-macro-set)
        vars      (:vars normalized)
        abs-path  (fn [filename]
                    (let [f (str filename)]
                      (if (and src-root (not (.isAbsolute (java.io.File. f))))
                        (str (java.io.File. (str src-root) f))
                        f)))]
    (persistent!
     (reduce
      (fn [acc {:keys [sym filename row defined-by]}]
        (if (and defined-by (contains? macro-set defined-by) filename row)
          (let [path    (abs-path filename)
                ctx     (assoc (ns-context normalized filename)
                               :sym sym :defined-by defined-by :macro-set macro-set)
                form    (try (form-at path row) (catch Throwable _ nil))
                profile (when (and (seq? form) (seq form))
                          (try (analyze-form form ctx)
                               (catch Throwable _ nil)))]
            (cond-> acc profile (assoc! sym profile)))
          acc))
      (transient {})
      vars))))

;; ---------------------------------------------------------------------------
;; synthetic edges (§22.1) — dispatch-table recovery
;; ---------------------------------------------------------------------------

(defn synthetic-edges
  "Returns the set of synthetic call/data edges (§22.1) recovered from declared dispatch
   tables, to UNION into the call graph. Currently delegates to the CUD side-effect registry
   sub-plugin (`blast-radius.plugins.cud-side-effects/side-effect-edges`), reading the dataico
   model file at `:base-clj-path`.

   Each edge is `{:from {:cud/entity <id-kw> :cud/verb <verb>} :to <fq-sym>}`: a CUD verb on
   a document type invokes a side-effect symbol via runtime dispatch clj-kondo cannot see.

   `:base-clj-path` is OPTIONAL: when nil or absent (a project without this dispatch table),
   no synthetic edges are produced rather than erroring."
  [{:keys [base-clj-path]}]
  (if (and base-clj-path (.exists (io/file base-clj-path)))
    (into #{} (cud/side-effect-edges base-clj-path))
    #{}))
