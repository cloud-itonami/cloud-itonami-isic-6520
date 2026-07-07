(ns reinsurance.treatyllm
  "Treaty-LLM client -- the *contained intelligence node* for the
  reinsurance actor.

  It normalizes treaty intake, drafts a per-jurisdiction bordereaux/
  collateral-evidence checklist, drafts the treaty-binding action,
  normalizes claims-recovery filing, and drafts the recovery-payment
  action. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real treaty binding/recovery payout. Every
  output is censored downstream by `reinsurance.governor` before
  anything touches the SSoT, and `:treaty/bind`/`:recovery/pay`
  proposals NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/bind | :actuation/pay-recovery | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [reinsurance.facts :as facts]
            [reinsurance.registry :as registry]
            [reinsurance.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the ceding insurer, treaty type/terms or jurisdiction.
  High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "特約引受レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :treaty/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction bordereaux/collateral-evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `reinsurance.facts` -- the Reinsurance Governor must reject this
  (never invent a jurisdiction's law)."
  [db {:keys [subject no-spec?]}]
  (let [t (store/treaty db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction t))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "reinsurance.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-bind
  "Draft the actual treaty-binding action -- binding a real reinsurance
  treaty with a ceding insurer. ALWAYS `:stake :actuation/bind` -- this
  is a REAL-WORLD act (ceded-premium and claims-recovery obligations
  begin), never a draft the actor may auto-run. See README `Actuation`:
  no phase ever adds this op to a phase's `:auto` set (`reinsurance.
  phase`); the governor also always escalates on `:actuation/bind`. Two
  independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [t (store/treaty db subject)
        assessment (store/assessment-of db subject)
        evidence-ok? (and assessment (facts/required-evidence-satisfied?
                                      (:jurisdiction t)
                                      (:checklist assessment)))]
    {:summary    (str (:ceding-insurer t) " (" (:jurisdiction t)
                      ") の特約成立準備ができました" (when-not evidence-ok? " (書類未充足)"))
     :rationale  (if assessment
                   (str "spec-basis: " (:spec-basis assessment))
                   "assessment未実施")
     :cites      (if assessment [(:spec-basis assessment)] [])
     :effect     :treaty/mark-bound
     :value      {:treaty-id subject}
     :stake      :actuation/bind
     :confidence (if evidence-ok? 0.9 0.3)}))

(defn- propose-recovery-filing
  "Directory upsert for a new claims-recovery request -- the LLM only
  normalizes/validates the filed recovery's fields (treaty-id,
  underlying-loss-amount, claimed-recovery-amount); it does not invent
  them. High confidence, low stakes -- filing itself moves no capital,
  unlike paying it."
  [_db {:keys [subject treaty-id underlying-loss-amount claimed-recovery-amount]}]
  {:summary    (str subject " (treaty " treaty-id ") の再保険回収請求を受付")
   :rationale  "入力された回収請求事実の正規化のみ。新規事実の生成なし。"
   :cites      [:treaty-id :underlying-loss-amount]
   :effect     :recovery/filed
   :value      {:id subject :treaty-id treaty-id
               :underlying-loss-amount underlying-loss-amount
               :claimed-recovery-amount claimed-recovery-amount :status :filed}
   :stake      nil
   :confidence 0.95})

(defn- propose-recovery-payment
  "Draft the actual recovery-PAYMENT action -- paying out a real
  claims-recovery amount under a bound reinsurance treaty. ALWAYS
  `:stake :actuation/pay-recovery` -- this is a REAL-WORLD act (real
  money leaves the reinsurer), never a draft the actor may auto-run.
  See README `Actuation`: no phase ever adds this op to a phase's
  `:auto` set (`reinsurance.phase`); the governor also always escalates
  on `:actuation/pay-recovery`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [r (store/recovery db subject)
        t (when r (store/treaty db (:treaty-id r)))
        recomputed (when t (registry/compute-recovery t (:underlying-loss-amount r)))
        matches? (and r recomputed
                      (< (Math/abs (- (double recomputed) (double (:claimed-recovery-amount r)))) 0.01))]
    {:summary    (str subject " 向け回収金支払い提案"
                      (when r (str " (claimed=" (:claimed-recovery-amount r) ")")))
     :rationale  (if t
                   (str "treaty " (:id t) " treaty_type=" (:treaty-type t) " recomputed=" recomputed)
                   "recoveryまたはtreatyが見つかりません")
     :cites      (if r [(:treaty-id r)] [])
     :effect     :recovery/mark-paid
     :value      {:recovery-id subject}
     :stake      :actuation/pay-recovery
     :confidence (if matches? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :treaty/intake        (normalize-intake db request)
    :jurisdiction/assess   (assess-jurisdiction db request)
    :treaty/bind           (propose-bind db request)
    :recovery/file         (propose-recovery-filing db request)
    :recovery/pay          (propose-recovery-payment db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは再保険特約引受・回収金支払いエージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:treaty/upsert|:assessment/set|:treaty/mark-bound|"
       ":recovery/filed|:recovery/mark-paid) "
       ":stake(:actuation/bind か :actuation/pay-recovery か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess {:treaty (store/treaty st subject)}
    :treaty/bind         {:treaty (store/treaty st subject)
                          :assessment (store/assessment-of st subject)}
    :recovery/pay        {:recovery (store/recovery st subject)}
    {:treaty (store/treaty st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Reinsurance Governor
  escalates/holds -- an LLM hiccup can never auto-bind a treaty or
  auto-pay a recovery."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :treatyllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
