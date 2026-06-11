(ns blast-radius.call-graph
  "Project-scoped call adjacency and transitive closures (design §10 / §27.2).

   Consumes the normalized `:call-usages` produced by `blast-radius.analysis/normalize`
   (each a clj-kondo var-usage carrying both `:from` (caller ns) and `:to` (callee ns),
   plus `:from-var` (caller var name) and `:name` (callee var name)). It builds two
   adjacency maps:

     * `:call-out` - `{caller-sym #{callee-sym …}}` (who each var calls).
     * `:call-in`  - `{callee-sym #{caller-sym …}}` (the reverse; who calls each var).

   Scoping (§27.2) is MANDATORY and applied here, not in `analysis`: edges that touch
   `clojure.core` / `cljs.core` / `tufte` / instrumentation are dropped. Those are the
   high-fan-in primitives (e.g. `clojure.core/let` has 7022 callers and reaches 84% of
   files); leaving them in would make every blast radius the whole project.

   Both BFS closures (`transitive-callers`, `transitive-callees`) walk the pre-scoped
   graph and use a visited guard, so they terminate on recursive cycles."
  (:require
   [clojure.string :as str]))

(defn project-edge?
  "Returns true when a call `usage` is internal to the project, i.e. BOTH its caller
   namespace `(:from usage)` and callee namespace `(:to usage)` start with one of the
   `prefixes` strings (e.g. `[\"dataico.\"]`).

   This is the §27.2 scoping predicate: it drops edges into/out of `clojure.core`,
   `cljs.core`, `tufte`, and instrumentation, which otherwise dominate the call graph."
  [prefixes usage]
  (let [from (str (:from usage))
        ;; scope on the ORIGINAL resolved callee namespace (`:to-ns`, preserved by
        ;; normalize) so project I/O-wrapper canonicalization (§706) of `:to` does NOT
        ;; change which edges count as in-project (§27.2/§307).
        to   (str (or (:to-ns usage) (some-> (:to usage) namespace) (:to usage)))]
    (boolean
     (and (some #(str/starts-with? from %) prefixes)
          (some #(str/starts-with? to %) prefixes)))))

(defn- caller-sym
  "Returns the fully-qualified caller symbol for call `usage`: `(:from)/(:from-var)`
   when the usage occurs inside a var, else the bare caller namespace symbol `(:from)`
   for top-level usages where `:from-var` is nil."
  [{:keys [from from-var]}]
  (if from-var
    (symbol (str from) (str from-var))
    from))

(defn- callee-sym
  "Returns the fully-qualified callee symbol for call `usage` for CALL-GRAPH node identity.
   Uses the ORIGINAL resolved callee namespace (`:to-ns`) + callee var name (`:callee`,
   else `:name`) so node identity is NOT affected by the §706 wrapper canonicalization that
   normalize applies to `:to` (that canonical form is for sink matching, §20). Falls back to
   a bare-namespace `:to` for un-normalized usages."
  [{:keys [to to-ns name callee]}]
  (let [ns* (or to-ns (when-not (qualified-symbol? to) to))
        nm  (or callee name)]
    (if (and ns* nm)
      (symbol (str ns*) (str nm))
      to)))

(defn build-call-graph
  "Builds project-scoped call adjacency from `normalized` (the output of
   `analysis/normalize`). Returns `{:call-out {from-sym #{to-sym}} :call-in {to-sym #{from-sym}}}`.

   `opts` is a map of:

     * `:ns-prefixes` - (required) seq of namespace-prefix strings used by
       `project-edge?` to keep only intra-project edges (e.g. `[\"dataico.\"]`).

   The caller symbol is var-level when the usage carries `:from-var`, otherwise the
   caller namespace; the callee symbol is always var-level `(:to)/(:name)`."
  [normalized {:keys [ns-prefixes]}]
  (let [edges (into []
                    (comp
                     (filter #(project-edge? ns-prefixes %))
                     (map (juxt caller-sym callee-sym)))
                    (:call-usages normalized))]
    {:call-out (reduce (fn [m [from to]] (update m from (fnil conj #{}) to)) {} edges)
     :call-in  (reduce (fn [m [from to]] (update m to (fnil conj #{}) from)) {} edges)}))

(defn callees
  "Returns the set of symbols directly called by `sym` in `graph` (its `:call-out`
   neighbors), or an empty set when `sym` calls nothing in-project."
  [graph sym]
  (get-in graph [:call-out sym] #{}))

(defn callers
  "Returns the set of symbols that directly call `sym` in `graph` (its `:call-in`
   neighbors), or an empty set when nothing in-project calls `sym`."
  [graph sym]
  (get-in graph [:call-in sym] #{}))

(defn- transitive-reach
  "Returns the set of symbols transitively reachable from `seed-set` by following
   `adjacency` (a `{node #{neighbor …}}` map). Breadth-first with a visited guard, so
   it terminates on cycles. The returned set EXCLUDES the seeds themselves unless they
   are re-reached through the graph."
  [adjacency seed-set]
  (loop [frontier (into #{} (mapcat #(get adjacency % #{})) seed-set)
         visited  #{}]
    (if (empty? frontier)
      visited
      (let [visited' (into visited frontier)
            next-frontier (into #{}
                                (comp
                                 (mapcat #(get adjacency % #{}))
                                 (remove visited'))
                                frontier)]
        (recur next-frontier visited')))))

(defn transitive-callers
  "Returns the set of symbols that transitively CALL any symbol in `seed-set`, by BFS
   over `(:call-in graph)`. This is the edge-A staleness closure (§23): everything whose
   behavior could change because it (transitively) depends on a changed seed. Terminates
   on cycles via a visited guard."
  [graph seed-set]
  (transitive-reach (:call-in graph) seed-set))

(defn transitive-callees
  "Returns the set of symbols transitively CALLED BY any symbol in `seed-set`, by BFS
   over `(:call-out graph)`. This is the forward reach used by `io/blast` to discover
   which write sinks a seed can reach. Terminates on cycles via a visited guard."
  [graph seed-set]
  (transitive-reach (:call-out graph) seed-set))
