(ns reinsurance.registry-test
  (:require [clojure.test :refer [deftest is]]
            [reinsurance.registry :as r]))

;; ----------------------------- compute-recovery -----------------------------

(deftest quota-share-recovery-is-a-fixed-percentage-capped-at-the-limit
  (is (= 500000.0 (r/compute-recovery {:treaty-type :quota-share :quota-share-pct 0.5 :coverage-limit 5000000} 1000000)))
  (is (= 5000000.0 (r/compute-recovery {:treaty-type :quota-share :quota-share-pct 0.5 :coverage-limit 5000000} 100000000))
      "capped at the treaty's aggregate limit"))

(deftest excess-of-loss-recovery-is-the-layer-between-retention-and-the-top
  (is (= 0.0 (r/compute-recovery {:treaty-type :excess-of-loss :retention 1000000 :layer-limit 4000000} 500000))
      "below retention -> zero recovery")
  (is (= 2000000.0 (r/compute-recovery {:treaty-type :excess-of-loss :retention 1000000 :layer-limit 4000000} 3000000)))
  (is (= 4000000.0 (r/compute-recovery {:treaty-type :excess-of-loss :retention 1000000 :layer-limit 4000000} 10000000))
      "capped at the top of the layer"))

(deftest compute-recovery-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-recovery {:treaty-type :quota-share :quota-share-pct 1.5 :coverage-limit 100} 100)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-recovery {:treaty-type :excess-of-loss :retention -1 :layer-limit 100} 100)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-recovery {:treaty-type :unknown} 100)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-recovery {:treaty-type :quota-share :quota-share-pct 0.5 :coverage-limit 100} -1))))

;; ----------------------------- register-binding -----------------------------

(deftest binding-is-a-draft-not-a-real-binding
  (let [result (r/register-binding "Sakura General Insurance" :quota-share "JPN" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest binding-assigns-treaty-number
  (let [result (r/register-binding "Sakura General Insurance" :quota-share "JPN" 7)]
    (is (= (get result "treaty_number") "JPN-00000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "binding-draft"))
    (is (= (get-in result ["record" "treaty_type"]) "quota-share"))))

(deftest binding-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "" :quota-share "JPN" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "Sakura" :unknown-type "JPN" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "Sakura" :quota-share "" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-binding "Sakura" :quota-share "JPN" -1))))

;; ----------------------------- register-recovery-payment -----------------------------

(deftest recovery-payment-is-a-draft-not-a-real-payment
  (let [result (r/register-recovery-payment "JPN-00000000" "recovery-1" 1000000 500000 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest recovery-payment-assigns-payment-number
  (let [result (r/register-recovery-payment "JPN-00000000" "recovery-1" 1000000 500000 "JPN" 7)]
    (is (= (get result "payment_number") "JPN-RECOV-000007"))
    (is (= (get-in result ["record" "treaty_number"]) "JPN-00000000"))
    (is (= (get-in result ["record" "recovery_id"]) "recovery-1"))
    (is (= (get-in result ["record" "recovery_amount"]) 500000))
    (is (= (get-in result ["record" "kind"]) "recovery-payment-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest recovery-payment-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recovery-payment "" "recovery-1" 1000000 500000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recovery-payment "JPN-00000000" "" 1000000 500000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recovery-payment "JPN-00000000" "recovery-1" -1 500000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recovery-payment "JPN-00000000" "recovery-1" 1000000 -1 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recovery-payment "JPN-00000000" "recovery-1" 1000000 500000 "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-recovery-payment "JPN-00000000" "recovery-1" 1000000 500000 "JPN" -1))))

(deftest recovery-history-is-append-only
  (let [p1 (r/register-recovery-payment "JPN-00000000" "recovery-1" 1000000 500000 "JPN" 0)
        hist (r/append [] p1)
        p2 (r/register-recovery-payment "JPN-00000000" "recovery-2" 200000 100000 "JPN" 1)
        hist2 (r/append hist p2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-RECOV-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-RECOV-000001" (get-in hist2 [1 "record_id"])))))
