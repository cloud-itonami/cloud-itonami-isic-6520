(ns reinsurance.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:treaty/bind`/`:recovery/pay` must NEVER be a member of
  any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [reinsurance.phase :as phase]))

(deftest treaty-bind-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real treaty binding"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :treaty/bind))
          (str "phase " n " must not auto-commit :treaty/bind")))))

(deftest recovery-pay-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-pays a real claims-recovery amount"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :recovery/pay))
          (str "phase " n " must not auto-commit :recovery/pay")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":treaty/intake and :recovery/file move no capital -- auto-eligible"
    (is (= #{:treaty/intake :recovery/file} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :treaty/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :treaty/bind} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :recovery/pay} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :treaty/intake} :commit)))))
