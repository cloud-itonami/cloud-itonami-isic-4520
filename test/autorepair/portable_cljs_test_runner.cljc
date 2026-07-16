(ns autorepair.portable-cljs-test-runner
  "PRIMARY automated quality gate for this actor under a real
  ClojureScript host (cljs.main --target node) — the same runtime-
  priority rule as the superproject CLAUDE.md:

      kotoba wasm runtime  >  clojurewasm  >  ClojureScript  >  nbb
      (JVM / babashka are last-resort compat, not the design target)

  The whole test suite is portable .cljc and runs UNCHANGED here and on
  the JVM (`clojure -M:dev:test`, secondary compat gate). This includes
  `autorepair.store-contract-test`, which exercises the langchain.db
  Datomic-API-compatible store — the kotoba-server / kotobase datom
  seam — under ClojureScript.

  Invoke from the repo root (the :test alias's :main-opts would steal
  -m if combined, hence -Sdeps for the extra path):

    clojure -Sdeps '{:paths [\"src\" \"test\"]}' \\
      -M:dev:cljs -m cljs.main --target node \\
      -m autorepair.portable-cljs-test-runner"
  (:require [clojure.test :as t :refer [run-tests]]
            [autorepair.store-contract-test]
            [autorepair.governor-contract-test]
            [autorepair.phase-test]))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

(defn -main []
  (run-tests 'autorepair.store-contract-test
             'autorepair.governor-contract-test
             'autorepair.phase-test))
