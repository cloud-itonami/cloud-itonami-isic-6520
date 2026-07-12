(ns reinsurance.governor-contract-test
  "The governor contract as executable tests -- the reinsurance analog
  of `cloud-itonami-isic-6512`'s `casualty.governor-contract-test`. The
  single invariant under test:

    Treaty-LLM never binds a treaty or pays a claims-recovery amount
    the Reinsurance Governor would reject, `:treaty/bind`/`:recovery/
    pay` NEVER auto-commit at any phase, `:treaty/intake`/`:recovery/
    file` (no capital risk) MAY auto-commit when clean, and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [reinsurance.store :as store]
            [reinsurance.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :reinsurance-underwriter :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- bind-treaty1!
  "Walks treaty-1 through assess -> approve -> bind -> approve, leaving
  treaty-1 :bound. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject "treaty-1"} operator)
  (approve! actor (str tid-prefix "-assess"))
  (exec-op actor (str tid-prefix "-bind") {:op :treaty/bind :subject "treaty-1"} operator)
  (approve! actor (str tid-prefix "-bind")))

(defn- file-recovery!
  [actor tid recovery-id treaty-id underlying-loss claimed]
  (exec-op actor tid {:op :recovery/file :subject recovery-id :treaty-id treaty-id
                      :underlying-loss-amount underlying-loss
                      :claimed-recovery-amount claimed} operator))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :treaty/intake :subject "treaty-1"
                   :patch {:id "treaty-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/treaty db "treaty-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "treaty-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "treaty-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "treaty-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "treaty-1")) "no assessment written"))))

(deftest bind-without-assessment-is-held
  (testing "treaty/bind before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :treaty/bind :subject "treaty-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest treaty-bind-always-escalates-then-human-decides
  (testing "a clean, fully-assessed binding still ALWAYS interrupts for human approval -- actuation/bind is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t6a" {:op :jurisdiction/assess :subject "treaty-1"} operator)
          _ (approve! actor "t6a")
          r1 (exec-op actor "t6" {:op :treaty/bind :subject "treaty-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, treaty-binding record drafted"
        (let [r2 (approve! actor "t6")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :bound (:status (store/treaty db "treaty-1"))))
          (is (= 1 (count (store/binding-history db))) "one draft binding record")))))
  (testing "reject -> hold, nothing bound"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7a" {:op :jurisdiction/assess :subject "treaty-1"} operator)
          _ (approve! actor "t7a")
          _ (exec-op actor "t7" {:op :treaty/bind :subject "treaty-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/binding-history db)) "nothing bound on reject"))))

(deftest recovery-file-against-unbound-treaty-is-held
  (testing "a recovery filed against a never-bound treaty -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (file-recovery! actor "t8" "recovery-1" "treaty-2" 200000 80000)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:treaty-not-bound} (-> (store/ledger db) first :basis)))
      (is (nil? (store/recovery db "recovery-1")) "no recovery written"))))

(deftest recovery-file-against-bound-treaty-auto-commits
  (testing ":recovery/file moves no capital yet -- auto-eligible at phase 3, once the treaty is bound"
    (let [[db actor] (fresh)
          _ (bind-treaty1! actor "t9pre")
          res (file-recovery! actor "t9" "recovery-1" "treaty-1" 1000000 500000)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :filed (:status (store/recovery db "recovery-1"))) "SSoT actually updated"))))

(deftest recovery-pay-with-missing-recovery-is-held
  (testing "paying a recovery id that was never filed -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t10" {:op :recovery/pay :subject "recovery-999"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:recovery-missing} (-> (store/ledger db) first :basis)))
      (is (empty? (store/recovery-history db))))))

(deftest recovery-pay-mismatch-is-held
  (testing "a recovery whose claimed amount does not match this actor's own independent recompute -> HOLD"
    (let [[db actor] (fresh)
          _ (bind-treaty1! actor "t11pre")
          _ (file-recovery! actor "t11file" "recovery-1" "treaty-1" 1000000 999999)
          res (exec-op actor "t11" {:op :recovery/pay :subject "recovery-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:recovery-calculation-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/recovery-history db))))))

(deftest recovery-pay-always-escalates-then-human-decides
  (testing "a clean, correctly-computed recovery still ALWAYS interrupts for human approval -- actuation/pay-recovery is never auto"
    (let [[db actor] (fresh)
          _ (bind-treaty1! actor "t12pre")
          _ (file-recovery! actor "t12file" "recovery-1" "treaty-1" 1000000 500000)
          r1 (exec-op actor "t12" {:op :recovery/pay :subject "recovery-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, recovery-payment record drafted"
        (let [r2 (approve! actor "t12")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :paid (:status (store/recovery db "recovery-1"))))
          (is (= 1 (count (store/recovery-history db))) "one draft payment record")))))
  (testing "reject -> hold, nothing paid"
    (let [[db actor] (fresh)
          _ (bind-treaty1! actor "t13pre")
          _ (file-recovery! actor "t13file" "recovery-1" "treaty-1" 1000000 500000)
          _ (exec-op actor "t13" {:op :recovery/pay :subject "recovery-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t13" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/recovery-history db)) "nothing paid on reject"))))

(deftest recovery-pay-double-payment-is-held
  (testing "paying the same recovery twice -> HOLD on the second attempt, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (bind-treaty1! actor "t14pre")
          _ (file-recovery! actor "t14file" "recovery-1" "treaty-1" 1000000 500000)
          _ (exec-op actor "t14a" {:op :recovery/pay :subject "recovery-1"} operator)
          _ (approve! actor "t14a")
          res (exec-op actor "t14" {:op :recovery/pay :subject "recovery-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-payment} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/recovery-history db))) "still only the one earlier payment"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :treaty/intake :subject "treaty-1"
                          :patch {:id "treaty-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "treaty-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
