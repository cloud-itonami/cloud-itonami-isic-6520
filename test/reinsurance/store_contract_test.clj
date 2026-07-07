(ns reinsurance.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [reinsurance.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura General Insurance" (:ceding-insurer (store/treaty s "treaty-1"))))
      (is (= "JPN" (:jurisdiction (store/treaty s "treaty-1"))))
      (is (= :quota-share (:treaty-type (store/treaty s "treaty-1"))))
      (is (= :excess-of-loss (:treaty-type (store/treaty s "treaty-3"))))
      (is (= ["treaty-1" "treaty-2" "treaty-3"] (mapv :id (store/all-treaties s))))
      (is (nil? (store/recovery s "recovery-1")))
      (is (nil? (store/assessment-of s "treaty-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/binding-history s)))
      (is (= [] (store/recovery-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (zero? (store/recovery-sequence s "JPN")))
      (is (false? (store/recovery-already-paid? s "recovery-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :treaty/upsert
                                 :value {:id "treaty-1" :status :ready}})
        (is (= :ready (:status (store/treaty s "treaty-1"))))
        (is (= "Sakura General Insurance" (:ceding-insurer (store/treaty s "treaty-1"))) "ceding-insurer preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["treaty-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "treaty-1"))))
      (testing "binding drafts a treaty record and advances the sequence"
        (store/commit-record! s {:effect :treaty/mark-bound :path ["treaty-1"]})
        ;; binding-history holds the inner "record" sub-map (registry/append's
        ;; convention), whose record-id key is "record_id".
        (is (= "JPN-00000000" (get (first (store/binding-history s)) "record_id")))
        (is (= "binding-draft" (get (first (store/binding-history s)) "kind")))
        (is (= :bound (:status (store/treaty s "treaty-1"))))
        (is (= 1 (count (store/binding-history s))))
        (is (= 1 (store/next-sequence s "JPN"))))
      (testing "recovery filing writes a plain recovery record (no draft/certificate -- filing moves no capital)"
        (store/commit-record! s {:effect :recovery/filed
                                 :payload {:id "recovery-1" :treaty-id "treaty-1"
                                          :underlying-loss-amount 1000000
                                          :claimed-recovery-amount 500000 :status :filed}})
        (is (= :filed (:status (store/recovery s "recovery-1"))))
        (is (= 1000000 (:underlying-loss-amount (store/recovery s "recovery-1")))))
      (testing "recovery payment drafts a payment record with THIS actor's own recomputed amount, not the claimed one, and advances the recovery sequence"
        (store/commit-record! s {:effect :recovery/mark-paid :path ["recovery-1"]})
        (is (= "JPN-RECOV-000000" (get (first (store/recovery-history s)) "record_id")))
        (is (= "recovery-payment-draft" (get (first (store/recovery-history s)) "kind")))
        (is (= 500000.0 (get (first (store/recovery-history s)) "recovery_amount"))
            "50% quota-share of 1,000,000 recomputed independently, matching the (correct) claimed amount here")
        (is (= :paid (:status (store/recovery s "recovery-1"))))
        (is (= 1 (count (store/recovery-history s))))
        (is (= 1 (store/recovery-sequence s "JPN")))
        (is (true? (store/recovery-already-paid? s "recovery-1")))
        (is (false? (store/recovery-already-paid? s "recovery-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/treaty s "nope")))
    (is (= [] (store/all-treaties s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/binding-history s)))
    (is (= [] (store/recovery-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (is (zero? (store/recovery-sequence s "JPN")))
    (store/with-treaties s {"x" {:id "x" :ceding-insurer "c" :jurisdiction "JPN"
                                :treaty-type :quota-share :quota-share-pct 0.5
                                :coverage-limit 1000000 :status :intake}})
    (is (= "c" (:ceding-insurer (store/treaty s "x"))))))
