(ns blast-radius.dictionary
  "T1 keyword dictionary (design §13/§18/§27.3).

   Builds the set of qualified keywords that \"matter\" from the static keyword index,
   attaching to each:

     * an inverse-document-frequency (`:idf`) weight `-log(df/N)` where `df` is the number
       of files the keyword occurs in and `N` the total file count — the §27.2 specificity
       dial used to ORDER the named set (ranking), never to exclude (§7), and
     * a `:class` signal-class (§27.3): `:identity`, `:ownership`, `:deprecated`, or the
       catch-all `:specific`. Classification is PLUGGABLE — a user may supply a `kw -> class`
       fn or an explicit keyword set; the default auto-detection is purely syntactic plus a
       RAD attribute seed.

   Per the NAME-EVERYTHING invariant (§7), `:idf`, `:class`, and `:hub-keywords` are
   PRESENTATION aids ONLY: they (a) ORDER the complete named set so a reviewer sees the most
   specific couplings first, and (b) SUGGEST trust-list candidates the user MAY explicitly,
   disclosedly exclude (§7 lever 2). They NEVER suppress a candidate — the only sanctioned
   removal is the user's declared trust-list, applied in `blast-radius.blast`."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private default-rad-kw-file
  "Path to the pre-extracted RAD attribute universe (a set of keywords)."
  "/tmp/rad-kw.edn")

(defn read-rad-attrs
  "Returns the RAD attribute universe as a `#{kw}` set read from `edn-file` (default
   `/tmp/rad-kw.edn`), or `#{}` when the file is absent/unreadable."
  ([] (read-rad-attrs default-rad-kw-file))
  ([edn-file]
   (let [f (io/file edn-file)]
     (if (.exists f)
       (with-open [r (io/reader f)]
         (set (edn/read (java.io.PushbackReader. r))))
       #{}))))

(defn- id-name?
  "True when the keyword `name` string denotes an identity attribute — exactly `id` or
   ending in `-id` (the purely syntactic §27.3 identity test)."
  [name-str]
  (or (= "id" name-str) (str/ends-with? name-str "-id")))

(defn auto-class
  "Returns the auto-detected §27.3 signal-class of `kw` given the `rad-attrs` keyword set,
   using only local/syntactic signals:

     * `:ownership` — `kw` is the tenant identity (`:company/id`).
     * `:identity`  — `(name kw)` is `id` or ends with `-id`.
     * `:deprecated`— `(namespace kw)` starts with `entity` (legacy schema being removed).
     * `:specific`  — everything else (the genuinely-core attributes; ranked by IDF).

   `rad-attrs` lets a caller widen ownership/deprecated detection in live mode (RAD
   `ao/target` refs to the tenant identity, deprecated metadata); the dataico default below
   keys off `:company/id` directly."
  [kw rad-attrs]
  (let [nm (name kw)
        ns (or (namespace kw) "")]
    (cond
      (= kw :company/id)                 :ownership
      (and (contains? (or rad-attrs #{}) kw)
           (= nm "company"))             :ownership
      (id-name? nm)                      :identity
      (str/starts-with? ns "entity")     :deprecated
      :else                              :specific)))

(defn classify
  "Returns the §27.3 signal-class of `kw`. `classifier` is PLUGGABLE and overrides the
   default auto-detection (`auto-class` against `rad-attrs`):

     * `nil`        — use auto-detection.
     * a set        — membership marks the keyword's overriding class is unknown; instead a
                      set is treated as the explicit universe of keywords to force to
                      `:specific` (an inclusion set), falling back to auto-detection for
                      non-members.
     * a function   — called as `(classifier kw)`; a non-nil result overrides, otherwise
                      auto-detection is used.

   Designed so the common cases — \"trust auto-detection\", \"here is a fn kw->class\", and
   \"here is a set of keywords I care about\" — all work."
  [kw classifier rad-attrs]
  (cond
    (set? classifier) (if (contains? classifier kw)
                        :specific
                        (auto-class kw rad-attrs))
    (ifn? classifier) (or (classifier kw) (auto-class kw rad-attrs))
    :else             (auto-class kw rad-attrs)))

(defn idf
  "Returns the inverse-document-frequency weight `-log(df/N)` for a keyword occurring in
   `df` files out of `total-files` (`N`). High when rare (specific), low/near-zero for hub
   keywords read in most files (§27.2). `total-files` and `df` must be positive."
  ^double [df total-files]
  (- (Math/log (/ (double df) (double total-files)))))

(defn build-dictionary
  "Builds the T1 keyword dictionary (§13/§18/§27.3) from the `normalized` clj-kondo analysis
   and the `kw-index` produced by `blast-radius.keyword-index/keyword-index`. Returns:

     * `:keywords`     `{kw {:count :files :vars :sources :class}}` — per-keyword metadata.
                       `:count` is the file occurrence count, `:files`/`:vars` the reader
                       file/var sets, `:sources` the var set declaring/producing it (from the
                       index's `:var->kws` inverse, here approximated by the reader vars), and
                       `:class` the §27.3 signal-class.
     * `:idf`          `{kw weight}` — `-log(df/N)` per keyword (§27.2 specificity dial).
     * `:hub-keywords` `#{kw}` — keywords flagged as hubs: those appearing in more than
                       `:hub-file-threshold` files, UNIONed with the manual `:hub-keywords`
                       option set.

   Options map:

     * `:classifier`         — pluggable classifier (set or `kw->class` fn), see `classify`.
     * `:rad-attrs`          — RAD attribute universe `#{kw}`; defaults to `read-rad-attrs`
                               (or the plugins `:declares-keyword` set the caller passes in).
     * `:hub-file-threshold` — file-count above which a keyword is auto-flagged a hub
                               (default 100, the §27.2 >100-files band).
     * `:hub-keywords`       — manual `#{kw}` always added to `:hub-keywords`."
  [_normalized kw-index {:keys [classifier rad-attrs hub-file-threshold hub-keywords]}]
  (let [rad-attrs   (or rad-attrs (read-rad-attrs))
        threshold   (or hub-file-threshold 100)
        manual-hubs (or hub-keywords #{})
        kw->files   (:kw->files kw-index)
        kw->vars    (:kw->vars kw-index)
        total-files (count (into #{} (mapcat val) kw->files))
        all-kws     (keys kw->files)
        idf-map     (persistent!
                     (reduce
                      (fn [m kw]
                        (assoc! m kw (idf (count (get kw->files kw)) total-files)))
                      (transient {}) all-kws))
        keywords    (persistent!
                     (reduce
                      (fn [m kw]
                        (let [files (get kw->files kw #{})
                              vars  (get kw->vars kw #{})]
                          (assoc! m kw {:count   (count files)
                                        :files   files
                                        :vars    vars
                                        :sources vars
                                        :class   (classify kw classifier rad-attrs)})))
                      (transient {}) all-kws))
        auto-hubs   (into #{}
                          (comp (filter (fn [kw] (> (count (get kw->files kw)) threshold)))
                                (map identity))
                          all-kws)]
    {:keywords     keywords
     :idf          idf-map
     :hub-keywords (into auto-hubs manual-hubs)}))
