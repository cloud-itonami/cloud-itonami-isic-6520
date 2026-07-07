(ns reinsurance.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean quota-share treaty
  through intake -> jurisdiction bordereaux/collateral assessment ->
  treaty-bind proposal (always escalates) -> human approval -> commit,
  then a clean claims-recovery through filing (auto-commits; no capital
  risk) -> recovery-payment proposal (always escalates) -> human
  approval -> commit, then walks a clean excess-of-loss treaty through
  the same intake -> assess -> bind lifecycle, then shows five HARD
  holds (a jurisdiction with no spec-basis, a recovery filed against an
  unbound treaty, a recovery-payment amount that does not match this
  actor's own independent quota-share/excess-of-loss recompute, a
  payment of a nonexistent recovery, and a double-payment of an
  already-paid recovery) that never reach a human at all, and prints the
  audit ledger + the draft treaty-binding and recovery-payment records."
  (:require [langgraph.graph :as g]
            [reinsurance.store :as store]
            [reinsurance.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :reinsurance-underwriter :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== treaty/intake treaty-1 (JPN, quota-share, clean) ==")
    (println (exec! actor "t1" {:op :treaty/intake :subject "treaty-1"
                                :patch {:id "treaty-1" :status :ready}} operator))

    (println "== jurisdiction/assess treaty-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "treaty-1"} operator))
    (println (approve! actor "t2"))

    (println "== treaty/bind treaty-1 (always escalates -- actuation/bind) ==")
    (let [r (exec! actor "t3" {:op :treaty/bind :subject "treaty-1"} operator)]
      (println r)
      (println "-- human reinsurance underwriter approves --")
      (println (approve! actor "t3")))

    (println "== recovery/file recovery-1 against treaty-1 (bound; 1,000,000 underlying loss, 500,000 claimed = correct 50% quota-share; auto-commits, no capital risk) ==")
    (println (exec! actor "t4" {:op :recovery/file :subject "recovery-1" :treaty-id "treaty-1"
                                :underlying-loss-amount 1000000 :claimed-recovery-amount 500000} operator))

    (println "== recovery/pay recovery-1 (always escalates -- actuation/pay-recovery) ==")
    (let [r (exec! actor "t5" {:op :recovery/pay :subject "recovery-1"} operator)]
      (println r)
      (println "-- human reinsurance underwriter approves --")
      (println (approve! actor "t5")))

    (println "== treaty/intake treaty-3 (GBR, excess-of-loss, clean) ==")
    (println (exec! actor "t6" {:op :treaty/intake :subject "treaty-3"
                                :patch {:id "treaty-3" :status :ready}} operator))

    (println "== jurisdiction/assess treaty-3 (escalates -- human approves) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "treaty-3"} operator))
    (println (approve! actor "t7"))

    (println "== treaty/bind treaty-3 (always escalates -- actuation/bind) ==")
    (let [r (exec! actor "t8" {:op :treaty/bind :subject "treaty-3"} operator)]
      (println r)
      (println "-- human reinsurance underwriter approves --")
      (println (approve! actor "t8")))

    (println "== jurisdiction/assess treaty-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t9" {:op :jurisdiction/assess :subject "treaty-2" :no-spec? true} operator))

    (println "== recovery/file recovery-2 against treaty-2 (never bound -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t10" {:op :recovery/file :subject "recovery-2" :treaty-id "treaty-2"
                                 :underlying-loss-amount 200000 :claimed-recovery-amount 80000} operator))

    (println "== recovery/file recovery-3 against treaty-3 (3,000,000 underlying loss, 2,500,000 WRONGLY claimed -- correct excess-of-loss recovery is 2,000,000; filing itself auto-commits) ==")
    (println (exec! actor "t11a" {:op :recovery/file :subject "recovery-3" :treaty-id "treaty-3"
                                  :underlying-loss-amount 3000000 :claimed-recovery-amount 2500000} operator))

    (println "== recovery/pay recovery-3 (claimed amount does not match this actor's own recompute -> HARD hold) ==")
    (println (exec! actor "t11" {:op :recovery/pay :subject "recovery-3"} operator))

    (println "== recovery/pay recovery-999 (nonexistent recovery -> HARD hold) ==")
    (println (exec! actor "t12" {:op :recovery/pay :subject "recovery-999"} operator))

    (println "== recovery/pay recovery-1 AGAIN (double-payment of an already-paid recovery -> HARD hold) ==")
    (println (exec! actor "t13" {:op :recovery/pay :subject "recovery-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft treaty-binding records ==")
    (doseq [r (store/binding-history db)] (println r))

    (println "== draft recovery-payment records ==")
    (doseq [r (store/recovery-history db)] (println r))))
