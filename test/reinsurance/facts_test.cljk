(ns reinsurance.facts-test
  (:require [clojure.test :refer [deftest is]]
            [reinsurance.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))

(deftest che-has-a-spec-basis
  (is (some? (facts/spec-basis "CHE")))
  (is (string? (:provenance (facts/spec-basis "CHE"))))
  (is (= "Switzerland" (:name (facts/spec-basis "CHE"))))
  (is (= "Eidgenössische Finanzmarktaufsicht FINMA (Swiss Financial Market Supervisory Authority)"
         (:owner-authority (facts/spec-basis "CHE")))))

(deftest che-required-evidence-omits-a-fabricated-tied-assets-item
  ;; FINMA confirms Swiss law imposes no tied-assets/collateral requirement on
  ;; reinsurers specifically (an explicit exception to "treated like primary
  ;; insurers") -- the checklist must not invent one just to look uniform
  ;; with DEU/GBR/JPN's collateral/trust-fund confirmation item.
  (let [items (facts/evidence-checklist "CHE")]
    (is (= 4 (count items)))
    (is (not (some #(re-find #"(?i)tied.assets|collateral|trust.fund" %) items)))))

(deftest coverage-includes-che-alongside-the-other-three
  (let [report (facts/coverage ["CHE" "DEU" "GBR" "JPN"])]
    (is (= 4 (:covered report)))
    (is (= ["CHE" "DEU" "GBR" "JPN"] (:covered-jurisdictions report)))
    (is (empty? (:missing-jurisdictions report)))))

(deftest irl-has-a-spec-basis
  (is (some? (facts/spec-basis "IRL")))
  (is (string? (:provenance (facts/spec-basis "IRL"))))
  (is (= "Central Bank of Ireland" (:owner-authority (facts/spec-basis "IRL"))))
  (is (re-find #"S\.I\. No\. 485 of 2015" (:legal-basis (facts/spec-basis "IRL")))))

(deftest coverage-includes-irl-alongside-all-others
  (let [report (facts/coverage ["CHE" "DEU" "GBR" "IRL" "JPN"])]
    (is (= 5 (:covered report)))
    (is (= ["CHE" "DEU" "GBR" "IRL" "JPN"] (:covered-jurisdictions report)))
    (is (empty? (:missing-jurisdictions report)))))
