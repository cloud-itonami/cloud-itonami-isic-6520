(ns reinsurance.governor
  "Reinsurance Governor -- the independent compliance layer that earns
  the Treaty-LLM the right to commit. The LLM has no notion of
  jurisdictional bordereaux/collateral disclosure law, whether a treaty
  is actually bound before a recovery is filed against it, whether a
  claimed recovery amount actually matches the treaty's own quota-share/
  excess-of-loss formula, or when an act stops being a draft and becomes
  a real-world treaty binding or recovery payout, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD --
  the reinsurance analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Six checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them (you don't get to approve your
  way past a fabricated jurisdiction spec-basis, incomplete bordereaux
  evidence, a recovery filed against an unbound treaty, a nonexistent
  recovery, or a recovery amount that doesn't match this actor's own
  independent recompute). The last is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `reinsurance.phase`: for `:stake :actuation/bind`/`:actuation/pay-
  recovery` (a real treaty binding or a real recovery payout) NO phase
  ever allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`reinsurance.
                                       facts`), or invent one?
    2. Evidence incomplete         -- for `:treaty/bind`, are the
                                       jurisdiction's required bordereaux/
                                       collateral docs actually satisfied?
    3. Treaty not bound            -- for `:recovery/file`, has the
                                       referenced treaty actually been
                                       bound? A recovery cannot be filed
                                       against a treaty that was never
                                       bound.
    4. Recovery missing            -- for `:recovery/pay`, does the
                                       referenced recovery actually exist
                                       on file?
    5. Recovery-calculation
       mismatch                     -- for `:recovery/pay`, does the
                                       recovery's OWN claimed amount
                                       actually match `reinsurance.
                                       registry/compute-recovery`'s
                                       independent recompute of the
                                       treaty's own quota-share/excess-
                                       of-loss formula? Never trusts a
                                       claimed recovery figure as-is --
                                       the SAME 'independently re-derive,
                                       never trust a claimed number'
                                       discipline `cloud-itonami-isic-
                                       6629`'s `apportionment-mismatch-
                                       violations` applies to general-
                                       average apportionment, applied
                                       here to reinsurance treaty math.
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:treaty/bind`/
                                       `:recovery/pay` (REAL legal/
                                       financial acts) -> escalate.

  One more guard, double-payment prevention, is enforced but NOT listed
  as a numbered HARD check above because it needs no upstream/treaty
  comparison at all -- `double-payment-violations` refuses to pay the
  SAME recovery twice, off this actor's own recovery-payment history."
  (:require [reinsurance.facts :as facts]
            [reinsurance.registry :as registry]
            [reinsurance.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Binding a real reinsurance treaty and paying out a real claims-
  recovery amount are the two real-world actuation events this actor
  performs."
  #{:actuation/bind :actuation/pay-recovery})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:treaty/bind`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's reinsurance-regulatory requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :treaty/bind} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:treaty/bind`, the jurisdiction's required bordereaux/collateral
  evidence must actually be satisfied -- do not trust the advisor's
  self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :treaty/bind)
    (let [t (store/treaty st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction t) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(再保険契約書案/分再保険料明細書等)が充足していない状態での成立提案"}]))))

(defn- treaty-not-bound-violations
  "For `:recovery/file`, the referenced treaty must actually have been
  bound (`:status :bound`) -- a claims-recovery cannot be filed against
  a treaty that was never bound. Unlike `cloud-itonami-isic-6622`'s
  placement, a treaty's status never advances PAST `:bound` (recovery
  filing/payment only ever changes the recovery's own status), so
  checking `:status :bound` directly here carries none of that ISIC's
  status-lifecycle risk."
  [{:keys [op treaty-id]} st]
  (when (= op :recovery/file)
    (when-not (= :bound (:status (store/treaty st treaty-id)))
      [{:rule :treaty-not-bound
        :detail (str treaty-id " は成立(bound)していないため、この再保険特約への回収請求は受理できない")}])))

(defn- recovery-missing-violations
  "For `:recovery/pay`, the referenced recovery must actually exist on
  file -- refuses to pay out a fabricated/nonexistent recovery id."
  [{:keys [op subject]} st]
  (when (= op :recovery/pay)
    (when-not (store/recovery st subject)
      [{:rule :recovery-missing
        :detail (str subject " という回収請求は登録されていない")}])))

(defn- close? [a b]
  (< (Math/abs (- (double a) (double b))) 0.01))

(defn- recovery-calculation-mismatch-violations
  "For `:recovery/pay`, INDEPENDENTLY recompute the recovery amount via
  `reinsurance.registry/compute-recovery` (the treaty's own quota-share/
  excess-of-loss formula) and compare against the recovery's OWN claimed
  amount -- never trusts a claimed figure as-is."
  [{:keys [op subject]} st]
  (when (= op :recovery/pay)
    (when-let [r (store/recovery st subject)]
      (let [t (store/treaty st (:treaty-id r))
            recomputed (registry/compute-recovery t (:underlying-loss-amount r))]
        (when-not (close? recomputed (:claimed-recovery-amount r))
          [{:rule :recovery-calculation-mismatch
            :detail (str subject " の請求回収額(" (:claimed-recovery-amount r)
                        ")が独自再計算値(" recomputed ")と一致しない")}])))))

(defn- double-payment-violations
  "For `:recovery/pay`, refuses to pay the SAME recovery twice, off this
  actor's own recovery-payment history -- needs no upstream/treaty
  comparison at all."
  [{:keys [op subject]} st]
  (when (= op :recovery/pay)
    (when (store/recovery-already-paid? st subject)
      [{:rule :double-payment
        :detail (str subject " は既に回収金支払い済み")}])))

(defn check
  "Censors a Treaty-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (treaty-not-bound-violations request st)
                           (recovery-missing-violations request st)
                           (recovery-calculation-mismatch-violations request st)
                           (double-payment-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
