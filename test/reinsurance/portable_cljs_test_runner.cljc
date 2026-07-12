(ns reinsurance.portable-cljs-test-runner
  "PRIMARY automated quality gate for this actor under a real
  ClojureScript host (cljs.main --target node) — the same runtime-
  priority rule as gftdcojp/cloud-itonami's ADR-0016 / the superproject
  CLAUDE.md:

      kotoba wasm runtime  >  clojurewasm  >  ClojureScript  >  nbb
      (JVM / babashka are last-resort compat, not the design target)

  The whole test suite is portable .cljc and runs UNCHANGED here and on
  the JVM (`clojure -M:dev:test`, secondary compat gate). This includes
  `reinsurance.store-contract-test`, which exercises the langchain.db
  Datomic-API-compatible store — the kotoba-server / kotobase datom
  seam — under ClojureScript.

  Invoke from the repo root (the :test alias's :main-opts would steal
  -m if combined, hence -Sdeps for the extra path):

    clojure -Sdeps '{:paths [\"src\" \"test\"]}' \\
      -M:dev:cljs -m cljs.main --target node \\
      -m reinsurance.portable-cljs-test-runner"
  (:require [clojure.test :as t :refer [run-tests]]
            [reinsurance.facts-test]
            [reinsurance.governor-contract-test]
            [reinsurance.kernels.gate-test]
            [reinsurance.phase-test]
            [reinsurance.registry-test]
            [reinsurance.store-contract-test]))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

(defn -main []
  (run-tests 'reinsurance.facts-test
             'reinsurance.registry-test
             'reinsurance.phase-test
             'reinsurance.kernels.gate-test
             'reinsurance.governor-contract-test
             'reinsurance.store-contract-test))
