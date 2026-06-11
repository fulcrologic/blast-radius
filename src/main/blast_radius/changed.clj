(ns blast-radius.changed
  "Tool 2 — git refs -> directly changed symbols + structural graph delta (design §19).

   Answers the function-level change trigger (§15.1) for the blast-radius pipeline.
   A var is \"changed\" iff its NORMALIZED source (docstring stripped, whitespace
   collapsed, then hashed) differs between two git refs — so reformatting and doc
   edits do NOT trigger a blast radius.

   PUBLIC entry points:

     * `normalize-content` / `content-signature` - the pure normalize+hash core
       VENDORED from fulcro-spec's `signature.clj` (we copy rather than depend on a
       *test* library; this is the shared-substrate intent of §14, §19).
     * `changed-files` / `materialize`            - git plumbing (diff + show).
     * `changed-vars`                             - the directly-changed symbol set.
     * `graph-delta`                              - the appeared/disappeared structural
                                                    delta over two analysis graphs (§19).

   Scope note (§19): Tool 2 emits ONLY the directly-changed set. Transitive expansion
   (a caller is stale because its callee changed) is Tool 6's job and falls out of
   call-edge reachability for free — we do not duplicate transitive-hash machinery here."
  (:require
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as str]
   [rewrite-clj.zip :as z])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

;; =============================================================================
;; Content Normalization (VENDORED from fulcro-spec.signature, §14/§19)
;;
;; Docstring stripping + whitespace collapse, character-positional so it works on
;; arbitrary source text (no eval, no var resolution). Copied so blast-radius does
;; not take a runtime dependency on a test library.
;; =============================================================================

(defn- find-string-end
  "Returns the index just after the closing quote of a string that starts at `idx`
   (the first char after the opening quote) in `s`, or nil if unterminated.
   Handles backslash escapes."
  [^String s idx]
  (loop [i idx]
    (when (< i (count s))
      (let [ch (.charAt s i)]
        (cond
          (= ch \\) (recur (+ i 2))
          (= ch \") (inc i)
          :else (recur (inc i)))))))

(defn- skip-whitespace
  "Returns the index of the first non-whitespace char in `s` at or after `idx`."
  [^String s idx]
  (loop [i idx]
    (if (and (< i (count s))
             (Character/isWhitespace (.charAt s i)))
      (recur (inc i))
      i)))

(defn- find-matching-bracket
  "Returns the index just after the bracket matching the opener at `idx` in `s`, or nil.
   Honors nesting and skips string contents."
  [^String s idx]
  (let [open-ch  (.charAt s idx)
        close-ch (case open-ch
                   \[ \]
                   \( \)
                   \{ \}
                   nil)]
    (when close-ch
      (loop [i     (inc idx)
             depth 1]
        (when (< i (count s))
          (let [ch (.charAt s i)]
            (cond
              (= ch \")
              (if-let [end (find-string-end s (inc i))]
                (recur end depth)
                nil)

              (= ch open-ch)
              (recur (inc i) (inc depth))

              (= ch close-ch)
              (if (= depth 1)
                (inc i)
                (recur (inc i) (dec depth)))

              :else
              (recur (inc i) depth))))))))

(defn- remove-docstring-from-def
  "Returns `s` with docstrings stripped from `def*` forms (`defn`, `defn-`, `>defn`, …).

   Recognizes both placements:
     * `(defn name \"docstring\" [args] body)`
     * `(defn name [args] \"docstring\" body)`

   Non-def string literals are preserved verbatim."
  [^String s]
  (let [len (count s)]
    (loop [i      0
           result (StringBuilder.)]
      (if (>= i len)
        (str result)
        (let [ch (.charAt s i)]
          (cond
            (= ch \")
            (if-let [end (find-string-end s (inc i))]
              (do (.append result (subs s i end))
                  (recur end result))
              (recur (inc i) result))

            (and (= ch \()
                 (< (inc i) len)
                 (let [next-ch (.charAt s (inc i))]
                   (or (= next-ch \d) (= next-ch \>))))
            (let [def-start (if (= \> (.charAt s (inc i))) (+ i 2) (inc i))
                  def-end   (loop [j def-start]
                              (if (and (< j len)
                                       (let [c (.charAt s j)]
                                         (or (Character/isLetterOrDigit c)
                                             (= c \-)
                                             (= c \>))))
                                (recur (inc j))
                                j))]
              (if (and (>= (- def-end def-start) 3)
                       (str/starts-with? (subs s def-start (min (+ def-start 3) len)) "def")
                       (< def-end len)
                       (Character/isWhitespace (.charAt s def-end)))
                (let [after-def  (skip-whitespace s def-end)
                      name-end   (loop [j after-def]
                                   (if (and (< j len)
                                            (not (Character/isWhitespace (.charAt s j))))
                                     (recur (inc j))
                                     j))
                      after-name (skip-whitespace s name-end)]
                  (if (and (< after-name len) (= \" (.charAt s after-name)))
                    (if-let [doc-end (find-string-end s (inc after-name))]
                      (do (.append result (subs s i after-name))
                          (recur doc-end result))
                      (do (.append result ch) (recur (inc i) result)))
                    (if (and (< after-name len) (= \[ (.charAt s after-name)))
                      (if-let [args-end (find-matching-bracket s after-name)]
                        (let [after-args (skip-whitespace s args-end)]
                          (if (and (< after-args len) (= \" (.charAt s after-args)))
                            (if-let [doc-end (find-string-end s (inc after-args))]
                              (do (.append result (subs s i after-args))
                                  (recur doc-end result))
                              (do (.append result ch) (recur (inc i) result)))
                            (do (.append result ch) (recur (inc i) result))))
                        (do (.append result ch) (recur (inc i) result)))
                      (do (.append result ch) (recur (inc i) result)))))
                (do (.append result ch) (recur (inc i) result))))

            :else
            (do (.append result ch) (recur (inc i) result))))))))

(defn- sha256
  "Returns the hex SHA-256 digest of `s` (UTF-8), or nil when `s` is nil."
  [^String s]
  (when s
    (let [digest     (MessageDigest/getInstance "SHA-256")
          hash-bytes (.digest digest (.getBytes s StandardCharsets/UTF_8))]
      (apply str (map #(format "%02x" %) hash-bytes)))))

(defn normalize-content
  "Returns a stable hash of `src-string` after stripping `def*` docstrings and collapsing
   all whitespace runs to a single space.

   This is the change-trigger primitive of §19: two source texts that differ only in
   docstrings or formatting hash EQUAL (so they do NOT trigger a blast radius), while any
   change to the actual body hashes DIFFERENTLY. Pure; no git/IO/eval required.

   Returns nil for nil input. On a normalization failure it falls back to hashing the
   raw text (a conservative over-trigger, recall-first)."
  [src-string]
  (when src-string
    (let [normalized (try
                       (-> (remove-docstring-from-def src-string)
                           (str/replace #"\s+" " ")
                           str/trim)
                       (catch Exception _ src-string))]
      (sha256 normalized))))

(defn content-signature
  "Returns the short (6-char) form of `normalize-content` for `src-string`, the
   compact `:old-sig`/`:new-sig` value attached to changed-var records (§19)."
  [src-string]
  (some-> (normalize-content src-string) (subs 0 6)))

;; =============================================================================
;; Git plumbing
;; =============================================================================

(def ^:private source-ext?
  "Predicate over a path string: true for the Clojure source extensions T2 considers."
  (fn [^String path]
    (or (str/ends-with? path ".clj")
        (str/ends-with? path ".cljc")
        (str/ends-with? path ".cljs"))))

(defn- git
  "Runs `git` with `args` in `repo-root` and returns its stdout string. Throws ex-info
   with the captured stderr when git exits non-zero."
  [repo-root & args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" (str repo-root) args)]
    (if (zero? exit)
      out
      (throw (ex-info "git command failed"
                      {:args args :repo-root (str repo-root) :exit exit :err err})))))

(defn changed-files
  "Returns the vector of Clojure source paths (`.clj`/`.cljc`/`.cljs`) that differ between
   git refs `ref-a` and `ref-b` in `repo-root`, via `git diff --name-only`. Paths are
   repo-relative (as git reports them)."
  [ref-a ref-b repo-root]
  (into []
        (comp (map str/trim)
              (filter seq)
              (filter source-ext?))
        (str/split-lines (git repo-root "diff" "--name-only" ref-a ref-b))))

(defn materialize
  "Returns the full text of `path` at git `ref` in `repo-root` (`git show REF:path`), or
   nil when the path does not exist at that ref (added/removed files)."
  [ref path repo-root]
  (let [{:keys [exit out]} (shell/sh "git" "-C" (str repo-root) "show" (str ref ":" path))]
    (when (zero? exit) out)))

(defn file-diff
  "Returns the unified `git diff` hunk text for `path` between `ref-a` and `ref-b` in
   `repo-root` (LLM context for §9/§11), or nil when empty."
  [ref-a ref-b repo-root path]
  (let [{:keys [exit out]} (shell/sh "git" "-C" (str repo-root)
                                     "diff" ref-a ref-b "--" path)]
    (when (and (zero? exit) (seq out)) out)))

;; =============================================================================
;; Positional var extraction (rewrite-clj) + diff-hunk line ranges
;; =============================================================================

(def ^:private def-head?
  "Predicate over a top-level head symbol: true for the `def*` family that introduces a
   named var whose body we want to track (defn/defn-/def/>defn/>defn-/defmethod/defmacro…)."
  (fn [sym]
    (and (symbol? sym)
         (let [n (name sym)]
           (or (str/starts-with? n "def")
               (str/starts-with? n ">def"))))))

(defn- top-level-name
  "Returns the defined-name symbol of a top-level `def*` form `zloc`, or nil if the
   second element is not a plain symbol (e.g. anonymous/odd forms)."
  [zloc]
  (let [name-loc (some-> zloc z/down z/right)]
    (when name-loc
      (let [s (try (z/sexpr name-loc) (catch Exception _ nil))]
        (when (symbol? s) s)))))

(defn- ns-name-of
  "Returns the namespace name symbol declared by the `ns` form in `src-string`, or nil.
   Read positionally with rewrite-clj so namespaced keywords / reader conditionals in the
   body never break extraction."
  [src-string]
  (try
    (loop [loc (z/of-string src-string {:track-position? true})]
      (when (and loc (not (z/end? loc)))
        (if (and (z/list? loc)
                 (= 'ns (some-> loc z/down z/sexpr)))
          (let [nm (some-> loc z/down z/right z/sexpr)]
            (when (symbol? nm) nm))
          (recur (z/right loc)))))
    (catch Exception _ nil)))

(defn var-defs-of
  "Returns a vector of `{:name :sym :row :end-row :src}` for every top-level `def*` var in
   `src-string`, using rewrite-clj POSITIONAL parsing (no eval, version-independent — §19
   step 2). `:sym` is fully qualified by the file's `ns` when present. `:src` is the exact
   source slice of the form, used for normalized comparison.

   Returns [] when `src-string` is nil or unparseable (added/removed files are handled by
   the caller from the presence/absence of these maps)."
  [src-string]
  (if (str/blank? src-string)
    []
    (let [the-ns (ns-name-of src-string)]
      (try
        (loop [loc (z/of-string src-string {:track-position? true})
               acc (transient [])]
          (if (or (nil? loc) (z/end? loc))
            (persistent! acc)
            (let [head (when (z/list? loc) (some-> loc z/down z/sexpr))
                  acc' (if (and (def-head? head) (top-level-name loc))
                         (let [nm  (top-level-name loc)
                               m   (meta (z/node loc))
                               row (:row m)
                               er  (:end-row m)]
                           (conj! acc {:name    nm
                                       :sym     (if the-ns
                                                  (symbol (str the-ns) (name nm))
                                                  nm)
                                       :row     row
                                       :end-row er
                                       :src     (z/string loc)}))
                         acc)]
              (recur (z/right loc) acc'))))
        (catch Exception _ [])))))

(def ^:private hunk-header-re
  "Matches a unified-diff hunk header `@@ -a,b +c,d @@`, capturing the NEW-side start line
   and length so we can map changed lines back to enclosing vars in the new tree."
  #"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")

(defn diff-new-line-ranges
  "Returns a vector of `[start end]` inclusive NEW-file line ranges touched by `diff-text`
   (a single file's unified `git diff`). Pure parse of the `@@` hunk headers; used to map
   diff hunks onto enclosing vars (§19 step 2). Returns [] for nil/blank diff."
  [diff-text]
  (if (str/blank? diff-text)
    []
    (into []
          (keep (fn [line]
                  (when-let [[_ start len] (re-find hunk-header-re line)]
                    (let [s (parse-long start)
                          l (if len (parse-long len) 1)]
                      [s (+ s (max 0 (dec l)))]))))
          (str/split-lines diff-text))))

(defn- overlaps?
  "Returns true if inclusive integer ranges `[a1 a2]` and `[b1 b2]` intersect."
  [[a1 a2] [b1 b2]]
  (and (<= a1 b2) (<= b1 a2)))

;; =============================================================================
;; changed-vars
;; =============================================================================

(defn- index-by-sym
  "Returns a map of `:sym` -> var-def map for `var-defs`. On duplicate syms (e.g. defmethod)
   keeps the first; the per-var comparison still re-normalizes the right source slice."
  [var-defs]
  (persistent!
   (reduce (fn [m {:keys [sym] :as v}]
             (if (contains? m sym) m (assoc! m sym v)))
           (transient {})
           var-defs)))

(defn changed-vars
  "Returns the directly-changed var records between two git refs (design §19).

   `opts` is a map of:
     * `:repo-root` - (required) the git working-tree root.
     * `:ref-a`     - (required) the OLD ref (compare base).
     * `:ref-b`     - (required) the NEW ref (compare target).

   For every changed Clojure source file (`git diff --name-only`), both versions are
   materialized (`git show`) and parsed positionally into top-level `def*` vars. A var is
   reported when:
     * it exists only in `ref-b`  -> `:added`,
     * it exists only in `ref-a`  -> `:removed`,
     * it exists in both AND its NORMALIZED source differs -> `:modified`.
   Whitespace/docstring-only deltas drop out (equal normalized hashes). To keep cost down,
   modified candidates are restricted to vars whose NEW line range overlaps a diff hunk.

   Each record is `{:sym :file :line-range [r1 r2] :status :added|:removed|:modified
   :old-sig :new-sig :diff}` (§19). `:line-range`/`:diff` come from the side where the var
   exists (ref-b for added/modified, ref-a for removed)."
  [{:keys [repo-root ref-a ref-b]}]
  (into []
        (mapcat
         (fn [path]
           (let [src-a   (materialize ref-a path repo-root)
                 src-b   (materialize ref-b path repo-root)
                 diff    (file-diff ref-a ref-b repo-root path)
                 defs-a  (var-defs-of src-a)
                 defs-b  (var-defs-of src-b)
                 by-a    (index-by-sym defs-a)
                 by-b    (index-by-sym defs-b)
                 hunks   (diff-new-line-ranges diff)
                 added   (into []
                               (comp
                                (filter (fn [{:keys [sym]}] (not (contains? by-a sym))))
                                (map (fn [{:keys [sym row end-row src]}]
                                       {:sym        sym
                                        :file       path
                                        :line-range [row end-row]
                                        :status     :added
                                        :old-sig    nil
                                        :new-sig    (content-signature src)
                                        :diff       diff})))
                               defs-b)
                 removed (into []
                               (comp
                                (filter (fn [{:keys [sym]}] (not (contains? by-b sym))))
                                (map (fn [{:keys [sym row end-row src]}]
                                       {:sym        sym
                                        :file       path
                                        :line-range [row end-row]
                                        :status     :removed
                                        :old-sig    (content-signature src)
                                        :new-sig    nil
                                        :diff       diff})))
                               defs-a)
                 mod     (into []
                               (comp
                                (filter (fn [{:keys [sym row end-row]}]
                                          (and (contains? by-a sym)
                                               ;; restrict to vars a hunk actually touched,
                                               ;; falling back to all when no hunk info.
                                               (or (empty? hunks)
                                                   (some #(overlaps? [row end-row] %) hunks)))))
                                (keep (fn [{:keys [sym row end-row src]}]
                                        (let [old-src (:src (get by-a sym))
                                              old-sig (normalize-content old-src)
                                              new-sig (normalize-content src)]
                                          (when (not= old-sig new-sig)
                                            {:sym        sym
                                             :file       path
                                             :line-range [row end-row]
                                             :status     :modified
                                             :old-sig    (some-> old-sig (subs 0 6))
                                             :new-sig    (some-> new-sig (subs 0 6))
                                             :diff       diff})))))
                               defs-b)]
             (concat added removed mod))))
        (changed-files ref-a ref-b repo-root)))

;; =============================================================================
;; graph-delta (§19)
;; =============================================================================

(defn- by-from-var
  "Returns a map of caller-var symbol -> `{:call-edges #{callee-sym} :produces #{kw}}`
   summarizing one normalized analysis (the shape returned by `analysis/normalize`).

   * `:call-edges` come from `:call-usages` grouped by `:from-var` (the caller var),
     using the canonicalized `:to` callee symbol so a newly-introduced write-sink call
     is visible as an appeared edge (§19).
   * `:produces` come from a var's I/O profile when present (`:profiles` -> per-sym
     `:outputs`). When no profiles are supplied this stays empty and only call-edge
     deltas are computed."
  [{:keys [call-usages profiles]}]
  (let [from-sym (fn [{:keys [from from-var]}]
                   (when (and from from-var) (symbol (str from) (str from-var))))
        calls    (reduce (fn [m u]
                           (if-let [s (from-sym u)]
                             (update m s (fnil conj #{}) (:to u))
                             m))
                         {}
                         call-usages)
        produces (reduce-kv (fn [m sym prof]
                              (let [outs (set (:outputs prof))]
                                (if (seq outs) (assoc m sym outs) m)))
                            {}
                            (or profiles {}))]
    (reduce (fn [m sym]
              (assoc m sym {:call-edges (get calls sym #{})
                            :produces   (get produces sym #{})}))
            {}
            (into #{} (concat (keys calls) (keys produces))))))

(defn graph-delta
  "Returns the per-symbol structural delta between two normalized analyses (design §19).

   `analysis-a` / `analysis-b` are `analysis/normalize` maps (optionally carrying a
   `:profiles` `{sym {:outputs #{kw}}}` map so production deltas can be computed).

   The result is `{sym {:appeared {:call-edges #{} :produces #{}}
                        :disappeared {:call-edges #{} :produces #{}}}}` where, per §10/§19:

     * `:appeared` `:call-edges` and `:produces`   - first-class NEW seeds (a function that
       newly calls a write sink, or newly produces an attribute, is a new contribution even
       if its own signature barely moved).
     * `:disappeared` `:produces`                  - KEPT: the SUPPLY of that attribute
       changed (deleted resolver/writer) so readers of it must be re-checked (§10 supply).
     * `:disappeared` `:call-edges`                - DROPPED: demand-side caller restructuring
       is not significant (§10 demand). They are intentionally returned as `#{}`.

   Only symbols with a non-empty appeared OR disappeared-produces delta are included."
  [analysis-a analysis-b]
  (let [a    (by-from-var analysis-a)
        b    (by-from-var analysis-b)
        syms (into #{} (concat (keys a) (keys b)))]
    (persistent!
     (reduce
      (fn [acc sym]
        (let [{a-calls :call-edges a-prod :produces} (get a sym {:call-edges #{} :produces #{}})
              {b-calls :call-edges b-prod :produces} (get b sym {:call-edges #{} :produces #{}})
              appeared-calls (set/difference b-calls a-calls)
              appeared-prod  (set/difference b-prod a-prod)
              gone-prod      (set/difference a-prod b-prod)]
          (if (or (seq appeared-calls) (seq appeared-prod) (seq gone-prod))
            (assoc! acc sym
                    {:appeared    {:call-edges appeared-calls
                                   :produces   appeared-prod}
                     ;; disappeared call-edges are demand-side -> dropped (#{});
                     ;; disappeared productions are supply-side -> KEPT.
                     :disappeared {:call-edges #{}
                                   :produces   gone-prod}})
            acc)))
      (transient {})
      syms))))
