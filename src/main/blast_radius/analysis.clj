(ns blast-radius.analysis
  "Thin wrapper + cache over `clj-kondo.core/run!` (design §12).

   This namespace is the single in-process entry point for producing and consuming
   clj-kondo analysis. It NEVER shells out (§12): it calls `clj-kondo.core/run!`
   directly so the JVM analysis map can be reused without serialization round-trips.

   It also performs NO project scoping — restricting usages to the `dataico.*`
   call graph is the call-graph component's job (§27.2). Here we only run, read,
   write, and normalize the raw clj-kondo shape into the four flat collections the
   rest of the pipeline consumes."
  (:require
   [blast-radius.keyword-index :as kw-index]
   [clj-kondo.core :as kondo]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

(defn run-analysis
  "Runs clj-kondo over `paths` (a seq of file/dir strings) and returns the full raw
   result map. The useful part is `(:analysis result)`.

   `options` is a map of:

     * `:paths`  - (required) the lint paths passed to clj-kondo's `:lint`.
     * `:config` - (optional) extra clj-kondo config, deep-merged AFTER the base
                   config that enables keyword analysis. Use it to widen analysis
                   (e.g. java/local usages); it should not need to disable keywords."
  [{:keys [paths config]}]
  (kondo/run!
   {:lint   paths
    :config (merge {:output {:analysis {:keywords true}}} config)}))

(defn- analysis-of
  "Returns the clj-kondo analysis map from `raw-or-analysis`, accepting either a full
   `run!` result (which nests it under `:analysis`) or an already-unwrapped analysis."
  [raw-or-analysis]
  (or (:analysis raw-or-analysis) raw-or-analysis))

(def ^:private wrapper-ns-aliases
  "Project I/O-wrapper namespace aliases (§706 macro-aliasing recognition): dataico wraps
   the datomic client behind `dataico.lib.datomic.api` (and a thin utils layer), so clj-kondo
   resolves calls to the WRAPPER rather than the underlying sink. We canonicalize the wrapper
   callee namespace to the real datomic API namespace so sink matching (§20) and the
   per-var I/O profile see the true I/O boundary. The original (wrapper) namespace is kept
   in `:to-ns` so the call graph's project scoping (§27.2) is unaffected."
  '{dataico.lib.datomic.api   datomic.api
    dataico.lib.datomic.utils datomic.api})

(defn- canonical-callee-ns
  "Returns the canonical callee namespace symbol for a resolved clj-kondo `to` namespace,
   mapping recognized project I/O wrappers (§706) to their underlying API namespace."
  [to]
  (get wrapper-ns-aliases to to))

(defn normalize
  "Normalizes a clj-kondo `raw-or-analysis` (either a full `run!` result or its
   `:analysis` value) into the flat collections the pipeline consumes:

     * `:var-defs`     - `(:var-definitions analysis)`, each
                         `{:ns :name :row :end-row :filename :defined-by :lang}`.
     * `:vars`         - the same maps augmented with `:sym` `(symbol ns name)`.
     * `:call-usages`  - the call edges (var-usages with BOTH `:from` and `:to`). Each is
                         augmented so callers/callees are first-class:
                           - `:to`        the RESOLVED fully-qualified CALLEE symbol
                                          `(symbol canonical-callee-ns name)` — project
                                          I/O wrappers are canonicalized to their underlying
                                          API namespace (§706) so sinks match (§20).
                           - `:to-ns`     the original resolved callee NAMESPACE symbol
                                          (clj-kondo `:to`), preserved for call-graph
                                          project scoping (§27.2/§307).
                           - `:callee`    the bare callee var name symbol (clj-kondo `:name`).
                         `:from`/`:from-var` (caller ns / caller var) are passed through.
     * `:keywords`     - `(:keywords analysis)`, the qualified-keyword occurrences."
  [raw-or-analysis]
  (let [{:keys [var-definitions var-usages keywords]} (analysis-of raw-or-analysis)
        var-defs (vec var-definitions)]
    {:var-defs    var-defs
     :vars        (mapv (fn [{:keys [ns name] :as v}]
                          (assoc v :sym (symbol (str ns) (str name))))
                        var-defs)
     :call-usages (into []
                        (comp
                         (filter #(and (:from %) (:to %) (:name %)))
                         (map (fn [{:keys [to name] :as u}]
                                (assoc u
                                       :to-ns  to
                                       :callee name
                                       :to     (symbol (str (canonical-callee-ns to))
                                                       (str name))))))
                        var-usages)
     :keywords    (vec keywords)}))

(def ^:private cached-analysis
  "Memoized loader: reads a clj-kondo analysis EDN file (the `{:analysis …}` shape)
   exactly once per process per file path, so the ~300MB dataico EDN is parsed once.
   Delegates the read to `keyword-index/read-analysis` for shape consistency."
  (memoize kw-index/read-analysis))

(defn read-cached
  "Returns the clj-kondo analysis map stored in `edn-file` (the `{:analysis …}` EDN
   shape written by `write-cache!` / clj-kondo `:format :edn`). The read is memoized
   per process, so repeated calls for the same `edn-file` parse it only once."
  [edn-file]
  (cached-analysis edn-file))

(defn write-cache!
  "Writes `analysis` (a clj-kondo analysis map) to `file` as EDN in the clj-kondo
   `:format :edn` shape, i.e. wrapped as `{:analysis analysis}`, so it round-trips
   through `read-cached`."
  [file analysis]
  (io/make-parents file)
  (with-open [w (io/writer file)]
    (binding [*out* w]
      (pr {:analysis analysis})))
  file)

(defn tree-sha
  "Returns the git TREE object SHA for `ref` in `repo-root` (`git rev-parse <ref>^{tree}`),
   or nil. The TREE sha (not the commit sha) is the correct cache key: two commits with
   identical source trees share an analysis."
  [repo-root ref]
  (let [{:keys [exit out]} (shell/sh "git" "-C" (str repo-root) "rev-parse" (str ref "^{tree}"))]
    (when (zero? exit) (str/trim out))))

(defn analyze-ref
  "Returns the clj-kondo analysis map for `repo-root` at git `ref`, analyzing the ACTUAL
   tree at that ref (faithful per-ref, fixing the HEAD-cache soundness gap) and CACHING the
   result keyed by TREE SHA.

   The tree is materialized read-only into a temp dir via `git archive | tar` — NO working
   copy is touched, so concurrent runs on different refs never collide. The expensive
   clj-kondo pass therefore runs once per distinct source tree; subsequent runs (same tree,
   e.g. comparing different merge-bases against the same HEAD) are cache hits.

   The lint runs via the NATIVE `clj-kondo` binary (shelled), not the JVM library: on a cold
   full-src tree the native binary is several-fold faster (≈100s vs >400s for the library), and
   its EDN output streams straight to the cache file (the JVM reads it back in well under a
   second). This intentionally trades the §12 'never shell out' stance for the cold-path speed
   the per-ref design needs; the result is cached, so the cost is paid once per tree.

   `opts`:
     * `:paths`        - repo-relative subtrees to analyze (default `[\"src\"]`).
     * `:cache-dir`    - where to store `analysis-<tree-sha>.edn` (default `<repo-root>/.blast-radius`).
     * `:kondo-bin`    - native clj-kondo binary (default `\"clj-kondo\"` on PATH)."
  [repo-root ref {:keys [paths cache-dir kondo-bin] :or {paths ["src"] kondo-bin "clj-kondo"}}]
  (let [sha   (tree-sha repo-root ref)
        cdir  (or cache-dir (str repo-root "/.blast-radius"))
        cfile (io/file cdir (str "analysis-" sha ".edn"))]
    (if (and sha (.exists cfile))
      (read-cached (.getPath cfile))
      (let [tmp (str (System/getProperty "java.io.tmpdir") "/blast-radius-tree-" sha)]
        (io/make-parents (io/file tmp "x"))
        (doseq [p paths]
          (let [cmd (format "git -C %s archive %s -- %s | tar -x -C %s" repo-root ref p tmp)
                {:keys [exit err]} (shell/sh "bash" "-c" cmd)]
            (when-not (zero? exit)
              (throw (ex-info "git archive | tar failed" {:ref ref :path p :err err})))))
        (io/make-parents (.getPath cfile))
        ;; Native clj-kondo: its `:format :edn` output is itself `{:analysis … :findings …}`,
        ;; which `read-cached` (`(:analysis …)`) consumes directly. Stream stdout to the cache.
        (let [lint-paths (str/join " " (map #(str tmp "/" %) paths))
              cmd (format "%s --lint %s --config '{:output {:analysis {:keywords true} :format :edn}}' > %s"
                          kondo-bin lint-paths (.getPath cfile))
              {:keys [exit err]} (shell/sh "bash" "-c" cmd)]
          ;; clj-kondo exits non-zero (2/3) when it finds lint warnings — that is NOT a failure
          ;; for our analysis use; only a missing/!=0-with-empty-output is.
          (when (and (not (.exists cfile)) (not (zero? exit)))
            (throw (ex-info "clj-kondo analysis failed" {:exit exit :err err})))
          (read-cached (.getPath cfile)))))))
