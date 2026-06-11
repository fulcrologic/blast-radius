(ns build
  "Build tasks for Blast Radius. `clojure -T:build uber` produces a self-contained uberjar at
   `target/blast-radius.jar` that runs WITHOUT the clojure CLI:

     java -cp target/blast-radius.jar clojure.main -m blast-radius.cli <range> [--flags]

   No AOT: the .clj sources + resources + all deps (clojure, clj-kondo, rewrite-clj) are
   bundled, and `clojure.main -m` loads `blast-radius.cli` at runtime. The native `clj-kondo`
   binary is still used for analysis (the jar's clj-kondo dep is only the library fallback)."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/blast-radius.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Builds the self-contained uberjar at `target/blast-radius.jar`."
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src/main" "resources"] :target-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis})
    (println "Built" uber-file)))
