(ns wasm.recovery-mismatch-test
  "Hosts wasm/recovery_mismatch.wasm (compiled from
  wasm/recovery_mismatch.kotoba, see wasm/README.md) via kototama.tender
  -- proves reinsurance.registry/compute-recovery's quota-share/excess-
  of-loss recompute, cross-checked with reinsurance.governor's `close?`
  tolerance (the independent recompute reinsurance.governor's
  :recovery-calculation-mismatch HARD check runs), executes as a real
  WASM guest, not just as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the seven real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/recovery_mismatch.kotoba's header comment for the offset layout
  and the milli-cents tolerance-scaling rationale."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/recovery_mismatch.wasm"))))

(defn- run-recovery-matches?
  [treaty-type-flag underlying-loss-cents quota-share-pct-bps coverage-limit-cents
   retention-cents layer-limit-cents claimed-recovery-cents]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 treaty-type-flag)
    (.writeI32 memory 4 underlying-loss-cents)
    (.writeI32 memory 8 quota-share-pct-bps)
    (.writeI32 memory 12 coverage-limit-cents)
    (.writeI32 memory 16 retention-cents)
    (.writeI32 memory 20 layer-limit-cents)
    (.writeI32 memory 24 claimed-recovery-cents)
    (tender/call-main instance)))

;; ----------------------------- quota-share -----------------------------

(deftest quota-share-exact-match-approves
  (testing "loss=100000c, pct-bps=5000 (50.00%) -> recovery=50000c exactly, claim matches -> approves"
    ;; hand-verified: 100000 * 0.5 = 50000c, no fractional remainder
    ;; (5000 bps divides evenly); fixed-point: quot(100000*5000, 10) =
    ;; quot(500000000, 10) = 50000000 millicents = 50000.000c
    (is (= 1 (run-recovery-matches? 0 100000 5000 200000 0 0 50000)))))

(deftest quota-share-within-tolerance-mismatch-still-passes
  (testing "loss=100001c, pct-bps=3333 (33.33%) -> true recompute is a
            fractional-cent amount (33330.333c); a claim rounded DOWN to
            33330c is off by only 0.333c (< 1c tolerance) -> still approves"
    ;; hand-verified: quot(100001*3333, 10) = quot(333303333, 10) =
    ;; 33330333 millicents = 33330.333c; claimed 33330c = 33330000
    ;; millicents; |33330333 - 33330000| = 333 < 1000 -> within tolerance
    (is (= 1 (run-recovery-matches? 0 100001 3333 200000 0 0 33330)))))

(deftest quota-share-beyond-tolerance-mismatch-rejects
  (testing "same fractional recompute (33330.333c), but claim is 33329c --
            1.333c off, at or beyond the 1-cent tolerance band -> rejects"
    ;; |33330333 - 33329000| = 1333 millicents >= 1000 -> HARD violation
    (is (= 0 (run-recovery-matches? 0 100001 3333 200000 0 0 33329)))))

(deftest quota-share-boundary-at-full-cent-rejects
  (testing "an exact recompute (50000.000c, no fractional remainder) vs a
            claim exactly 1 full cent short (49999c) -> the original
            `close?` is a strict `<`, so a full-cent gap is NEVER close
            -> rejects even at the boundary"
    ;; |50000000 - 49999000| = 1000 millicents; 1000 < 1000 is false
    (is (= 0 (run-recovery-matches? 0 100000 5000 200000 0 0 49999)))))

(deftest quota-share-boundary-just-under-tolerance-passes
  (testing "loss=90003c, pct-bps=3333 -> true recompute 29997.999c; a
            claim rounded down to 29997c is off by 0.999c -- just barely
            inside the < 1c tolerance band -> approves"
    ;; hand-verified: quot(90003*3333, 10) = quot(300003999, 10) =
    ;; 29997999 (integer truncation of .3999 -> 29997999, i.e. 29997.999c);
    ;; claimed 29997c = 29997000 millicents; |29997999-29997000| = 999 < 1000
    (is (= 1 (run-recovery-matches? 0 90003 3333 200000 0 0 29997)))))

(deftest quota-share-coverage-limit-caps-recovery
  (testing "loss=100000c, pct-bps=10000 (100%) would recompute 100000c,
            but coverage-limit=50000c caps it -- a claim of the CAPPED
            50000c matches -> approves"
    ;; hand-verified: raw = quot(100000*10000,10) = 100000000 millicents
    ;; (100000.000c); cap = 50000*1000 = 50000000 millicents (50000.000c);
    ;; min(raw, cap) = 50000000 = claimed 50000c*1000 -> exact match
    (is (= 1 (run-recovery-matches? 0 100000 10000 50000 0 0 50000)))))

;; ----------------------------- excess-of-loss -----------------------------

(deftest excess-of-loss-within-layer-exact-match-approves
  (testing "loss=300000c, retention=100000c, layer-limit=400000c -> layer
            top=500000c, capped-loss=300000c, recovery=300000-100000=
            200000c exactly (no percentage division on this branch, so
            no fractional-cent artifact is even possible) -- claim
            matches -> approves"
    (is (= 1 (run-recovery-matches? 1 300000 0 0 100000 400000 200000)))))

(deftest excess-of-loss-below-retention-zero-recovery-matches-zero-claim
  (testing "loss=50000c is below retention=100000c -> recovery is
            max(0, ...) = 0c; a claim of 0c matches -> approves"
    (is (= 1 (run-recovery-matches? 1 50000 0 0 100000 400000 0)))))

(deftest excess-of-loss-below-retention-nonzero-claim-rejects
  (testing "same below-retention case (true recovery = 0c), but the
            claim is a nonzero 100c -- a fabricated recovery amount for a
            loss that owes nothing under this layer -> rejects"
    (is (= 0 (run-recovery-matches? 1 50000 0 0 100000 400000 100)))))
