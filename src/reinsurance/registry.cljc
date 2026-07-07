(ns reinsurance.registry
  "Pure-function treaty-binding and claims-recovery-payment record
  construction -- an append-only reinsurance book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a treaty-binding or recovery-payment
  reference number -- every reinsurer/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `reinsurance.facts` uses.

  `compute-recovery` is a REAL, well-known pair of formulas (quota-share
  and excess-of-loss treaty math), not an invented placeholder default
  -- see its own docstring for the honest simplification it makes vs. a
  real treaty's full terms (aggregate deductibles, reinstatement
  premiums, multi-layer towers). This is the SAME 'reimplement the
  well-known math independently, so a downstream governor can
  cross-check a claimed figure against it' pattern `cloud-itonami-isic-
  6629`'s `auxiliary.registry/apportion-general-average` establishes --
  applied here to a DIFFERENT domain-specific formula, within one repo
  (a recovery request's claimed amount vs. this repo's own independent
  recompute), not across a repo boundary.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any reinsurer's core-administration or claims-payment system.
  It builds the RECORD a reinsurer would keep, not the act of binding
  the treaty or paying the recovery itself (those are `reinsurance.
  operation`'s `:treaty/bind` and `:recovery/pay`, always human-gated --
  see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed reinsurer's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn compute-recovery
  "Pure computation of the reinsurance recovery owed on an underlying
  loss, dispatched by treaty type -- a REAL, simplified pair of
  formulas (see ns docstring for what a full treaty's terms
  additionally model that this does not):

    :quota-share      -- recovery = min(underlying-loss * quota-share-
                         pct, coverage-limit). The reinsurer takes a
                         FIXED PERCENTAGE of every loss, capped at the
                         treaty's aggregate limit.
    :excess-of-loss    -- recovery = max(0, min(underlying-loss,
                         retention + layer-limit) - retention). The
                         reinsurer covers the LAYER between the ceding
                         insurer's retention and the top of the treaty
                         layer; losses below retention or above the
                         layer are NOT this treaty's responsibility.

  `treaty` -- a map with `:treaty-type` plus the type-specific fields
  above. `underlying-loss-amount` -- the real-world loss the ceding
  insurer actually incurred, before any reinsurance recovery."
  [treaty underlying-loss-amount]
  (when (neg? underlying-loss-amount)
    (throw (ex-info "compute-recovery: underlying-loss-amount must be >= 0" {})))
  (case (:treaty-type treaty)
    :quota-share
    (let [{:keys [quota-share-pct coverage-limit]} treaty]
      (when-not (<= 0 quota-share-pct 1)
        (throw (ex-info "compute-recovery: quota-share-pct must be in [0,1]" {})))
      (min (* (double underlying-loss-amount) (double quota-share-pct))
           (double coverage-limit)))

    :excess-of-loss
    (let [{:keys [retention layer-limit]} treaty]
      (when (neg? retention) (throw (ex-info "compute-recovery: retention must be >= 0" {})))
      (when (neg? layer-limit) (throw (ex-info "compute-recovery: layer-limit must be >= 0" {})))
      (max 0.0 (- (min (double underlying-loss-amount) (+ (double retention) (double layer-limit)))
                  (double retention))))

    (throw (ex-info (str "compute-recovery: unknown treaty-type " (:treaty-type treaty)) {}))))

(defn register-binding
  "Validate + construct the TREATY-BINDING registration DRAFT -- the
  reinsurer's own act of binding a real reinsurance treaty with a
  ceding insurer. Pure function -- does not touch any real reinsurance-
  administration system."
  [ceding-insurer treaty-type jurisdiction sequence]
  (when-not (and ceding-insurer (not= ceding-insurer ""))
    (throw (ex-info "binding: ceding-insurer required" {})))
  (when-not (contains? #{:quota-share :excess-of-loss} treaty-type)
    (throw (ex-info "binding: treaty-type must be :quota-share or :excess-of-loss" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "binding: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "binding: sequence must be >= 0" {})))
  (let [treaty-number (str (str/upper-case jurisdiction) "-" (zero-pad sequence 8))
        record {"record_id" treaty-number
                "kind" "binding-draft"
                "ceding_insurer" ceding-insurer
                "treaty_type" (name treaty-type)
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "treaty_number" treaty-number
     "certificate" (unsigned-certificate "TreatyBindingCertificate" treaty-number treaty-number)}))

(defn register-recovery-payment
  "Validate + construct the CLAIMS-RECOVERY-PAYMENT DRAFT -- the
  reinsurer's own legal act of paying a claims-recovery amount owed
  under a bound treaty. Pure function -- does not touch any real
  banking/claims-payment system; it builds the RECORD a reinsurer would
  keep. `reinsurance.governor` independently re-verifies the recovery
  amount against `compute-recovery`, and blocks a double-payment of the
  same recovery request, before this is ever allowed to commit."
  [treaty-number recovery-id underlying-loss-amount recovery-amount jurisdiction sequence]
  (when-not (and treaty-number (not= treaty-number ""))
    (throw (ex-info "recovery-payment: treaty_number required" {})))
  (when-not (and recovery-id (not= recovery-id ""))
    (throw (ex-info "recovery-payment: recovery_id required" {})))
  (when (neg? underlying-loss-amount)
    (throw (ex-info "recovery-payment: underlying-loss-amount must be >= 0" {})))
  (when (neg? recovery-amount)
    (throw (ex-info "recovery-payment: recovery-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "recovery-payment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "recovery-payment: sequence must be >= 0" {})))
  (let [payment-number (str (str/upper-case jurisdiction) "-RECOV-" (zero-pad sequence 6))
        record {"record_id" payment-number
                "kind" "recovery-payment-draft"
                "treaty_number" treaty-number
                "recovery_id" recovery-id
                "underlying_loss_amount" underlying-loss-amount
                "recovery_amount" recovery-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "payment_number" payment-number
     "certificate" (unsigned-certificate "RecoveryPaymentCertificate" payment-number payment-number)}))

(defn append
  "Append a binding/recovery-payment record, returning a NEW list (never
  mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
