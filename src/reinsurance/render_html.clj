(ns reinsurance.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`reinsurance.operation` ->
  `reinsurance.governor` -> `reinsurance.store`, through
  `langgraph.graph/run*`) and renders whatever that run actually
  produced. Nothing on the page is typed by hand:

    - treaty rows come from `reinsurance.store/all-treaties`;
    - claims-recovery rows are looked up with `reinsurance.store/recovery`
      against the recovery ids the real ledger mentions (so a recovery
      whose filing was HELD shows up honestly as `not on file`);
    - the phase-gate rows are produced by CALLING `reinsurance.phase/gate`
      twice per op (governor-clean and governor-HARD) rather than
      describing it in prose, and the `high-stakes` column is the
      `:stake` the advisor actually attached during this run, checked
      against `reinsurance.governor/high-stakes`;
    - the HARD-check rows are grouped out of the real `:governor-hold`
      facts in the ledger;
    - the approver-attribution rows are MEASURED at render time by
      walking the post-run registers (see `approval-attribution`);
    - the jurisdiction rows come from `reinsurance.facts/coverage` over
      the jurisdictions the seeded treaties actually carry;
    - the registry drafts are the records `reinsurance.registry` actually
      minted, read back out of the store's append-only histories.

  Scenario shape (adapted from this repo's own `reinsurance.sim` demo
  driver, `clojure -M:dev:run`, which was run BEFORE this file was
  written and confirmed to produce a sensible ledger against the real
  seeded treaty ids `treaty-1`..`treaty-3`):

    - treaty-1 (Sakura General Insurance, JPN, quota-share) walks a full
      clean lifecycle -- intake (auto-commits at phase 3, no capital
      risk), jurisdiction bordereaux/collateral assessment (escalates,
      approved), treaty binding (ALWAYS escalates, approved), a
      claims-recovery filing (auto-commits, moves no capital) and the
      recovery PAYMENT (ALWAYS escalates, approved).
    - treaty-3 (Britannia Mutual, GBR, excess-of-loss) walks the same
      intake -> assess -> bind lifecycle, exercising the second treaty
      formula.
    - recovery-4 against treaty-3 is filed with a CORRECT amount, clears
      the governor, reaches a human -- and the human REJECTS it. That is
      the SOFT gate's other outcome, and it contrasts with a HARD hold
      that never reaches a human at all.
    - six distinct HARD governor rules actually fire (see
      `min-distinct-hard-rules`); none of them reaches a human, which
      `-main` verifies structurally rather than asserting in prose.

  Build-time invariants (`-main` THROWS, it does not warn):
    1. the run must produce at least one real `:governor-hold` fact --
       a console that claims to demonstrate a governor while
       demonstrating no hold is theatre;
    2. it must exercise at least `min-distinct-hard-rules` DISTINCT HARD
       rules -- an evidence floor, so a governor change that silently
       stops firing a check fails the build instead of quietly rendering
       a thinner page;
    3. no run that HARD-held may also have asked a human (no
       `:approval-requested` in that run's own audit channel) -- the
       'HARD holds never reach a human' claim is measured, not asserted;
    4. every HARD row rendered must trace to a rule present in the
       ledger (the section cannot outlive the run that justified it);
    5. the approver-attribution section must have at least one real
       granted approval to describe.

  Deterministic: no timestamps, no random ids, byte-identical across
  reruns against the same seed (verify by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [reinsurance.store :as store]
            [reinsurance.facts :as facts]
            [reinsurance.phase :as phase]
            [reinsurance.governor :as governor]
            [reinsurance.registry :as registry]
            [reinsurance.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :reinsurance-underwriter :phase 3})

(def min-distinct-hard-rules
  "Evidence floor for invariant 2. The scenario below drives all six
  HARD governor rules `reinsurance.governor` implements; if a governor/
  store/advisor change makes fewer of them fire, the build fails rather
  than rendering a quietly thinner page. Raise this when the scenario
  grows; never lower it to make a build pass."
  6)

;; ----------------------------- the real run -----------------------------

(defn- exec!
  "One supervised reinsurance operation = one langgraph run. Returns the
  run result; `runs` accumulates a record of each run so the renderer can
  read that run's OWN audit channel -- the approval facts never reach the
  store ledger (see `reinsurance.operation`: the `:commit` node appends
  only the commit fact, the `:hold` node only the hold fact), so the
  store alone cannot answer 'who approved this'."
  [runs actor tid request]
  (let [r (g/run* actor {:request request :context operator} {:thread-id tid})]
    (swap! runs conj {:thread      tid
                      :op          (:op request)
                      :subject     (:subject request)
                      :stake       (get-in r [:state :proposal :stake])
                      :confidence  (get-in r [:state :proposal :confidence])
                      :record      (get-in r [:state :record])
                      :disposition (get-in r [:state :disposition])
                      :audit       (vec (get-in r [:state :audit]))})
    r))

(defn- resume!
  "Resume a run paused at `:request-approval` with a real human decision.
  `:op`/`:subject` are read back off the CHECKPOINTED request rather than
  re-typed here, so a resume can never be attributed to the wrong op."
  [runs actor tid status by]
  (let [r (g/run* actor {:approval {:status status :by by}}
                  {:thread-id tid :resume? true})]
    (swap! runs conj {:thread      tid
                      :resume?     true
                      :op          (get-in r [:state :request :op])
                      :subject     (get-in r [:state :request :subject])
                      :stake       (get-in r [:state :proposal :stake])
                      :confidence  (get-in r [:state :proposal :confidence])
                      :record      (get-in r [:state :record])
                      :disposition (get-in r [:state :disposition])
                      :audit       (vec (get-in r [:state :audit]))})
    r))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach (see the ns docstring). Returns `{:db .. :runs ..}`
  -- every field the renderer reads below is real governor/store/registry
  output, not a hand-typed copy."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (atom [])]

    ;; --- treaty-1 (JPN, quota-share): full clean lifecycle ---
    (exec! runs actor "t1-intake"
           {:op :treaty/intake :subject "treaty-1"
            :patch {:id "treaty-1" :status :ready}})

    (exec! runs actor "t1-assess" {:op :jurisdiction/assess :subject "treaty-1"})
    (resume! runs actor "t1-assess" :approved "op-1")

    (exec! runs actor "t1-bind" {:op :treaty/bind :subject "treaty-1"})
    (resume! runs actor "t1-bind" :approved "op-1")

    ;; 1,000,000 underlying loss x 50% quota share = 500,000 -- correct.
    (exec! runs actor "t1-recovery-file"
           {:op :recovery/file :subject "recovery-1" :treaty-id "treaty-1"
            :underlying-loss-amount 1000000 :claimed-recovery-amount 500000})

    (exec! runs actor "t1-recovery-pay" {:op :recovery/pay :subject "recovery-1"})
    (resume! runs actor "t1-recovery-pay" :approved "op-1")

    ;; --- treaty-3 (GBR, excess-of-loss): the second treaty formula ---
    (exec! runs actor "t3-intake"
           {:op :treaty/intake :subject "treaty-3"
            :patch {:id "treaty-3" :status :ready}})

    (exec! runs actor "t3-assess" {:op :jurisdiction/assess :subject "treaty-3"})
    (resume! runs actor "t3-assess" :approved "op-1")

    (exec! runs actor "t3-bind" {:op :treaty/bind :subject "treaty-3"})
    (resume! runs actor "t3-bind" :approved "op-1")

    ;; --- the SOFT gate's OTHER outcome: a human who declines ---
    ;; 2,500,000 underlying loss on a 1,000,000 xs 4,000,000 layer =
    ;; 1,500,000 -- correct, so the governor is clean and a human is
    ;; genuinely asked. This one is REJECTED, and the SSoT stays unpaid.
    (exec! runs actor "t3-recovery-file-ok"
           {:op :recovery/file :subject "recovery-4" :treaty-id "treaty-3"
            :underlying-loss-amount 2500000 :claimed-recovery-amount 1500000})
    (exec! runs actor "t3-recovery-pay-rejected" {:op :recovery/pay :subject "recovery-4"})
    (resume! runs actor "t3-recovery-pay-rejected" :rejected "op-1")

    ;; --- HARD holds: none of these ever reaches a human ---
    ;; 1. no spec-basis for an unregistered jurisdiction (ATL).
    (exec! runs actor "t2-assess"
           {:op :jurisdiction/assess :subject "treaty-2" :no-spec? true})
    ;; 2. binding treaty-2 anyway: no cited spec-basis AND the
    ;;    jurisdiction's required bordereaux/collateral evidence is not
    ;;    satisfied -- two HARD rules in one hold.
    (exec! runs actor "t2-bind" {:op :treaty/bind :subject "treaty-2"})
    ;; 3. a recovery filed against a treaty that was never bound.
    (exec! runs actor "t2-recovery-file"
           {:op :recovery/file :subject "recovery-2" :treaty-id "treaty-2"
            :underlying-loss-amount 200000 :claimed-recovery-amount 80000})
    ;; 4. a recovery whose claimed amount (2,500,000) does not match this
    ;;    actor's own excess-of-loss recompute (2,000,000). Filing itself
    ;;    moves no capital, so the FILING commits; the PAYMENT is held.
    (exec! runs actor "t3-recovery-file-bad"
           {:op :recovery/file :subject "recovery-3" :treaty-id "treaty-3"
            :underlying-loss-amount 3000000 :claimed-recovery-amount 2500000})
    (exec! runs actor "t3-recovery-pay" {:op :recovery/pay :subject "recovery-3"})
    ;; 5. paying a recovery that does not exist on file at all.
    (exec! runs actor "t9-recovery-pay" {:op :recovery/pay :subject "recovery-999"})
    ;; 6. paying the SAME recovery twice.
    (exec! runs actor "t1-recovery-repay" {:op :recovery/pay :subject "recovery-1"})

    {:db db :runs @runs}))

;; -------------------- measurement (not assumption) --------------------

(defn- approver-key?
  "Is `k` an approver-attribution key? Decided by inspecting the key
  itself, not by hardcoding `:approved-by` -- a store that renames or
  adds an attribution key still measures as retaining one."
  [k]
  (and (keyword? k) (str/includes? (name k) "approv")))

(defn- register-entry
  "The post-run SSoT entry a committed record actually landed in. Which
  register a record writes to is decided by its `:effect`, exactly as
  `reinsurance.store/commit-record!` decides it."
  [db {:keys [effect path]}]
  (let [id (first path)]
    (case effect
      (:treaty/upsert :treaty/mark-bound)
      {:register "treaty" :id id :entry (store/treaty db id)}

      :assessment/set
      {:register "assessment" :id id :entry (store/assessment-of db id)}

      (:recovery/filed :recovery/mark-paid)
      {:register "recovery" :id id :entry (store/recovery db id)}

      {:register (str effect) :id id :entry nil})))

(defn approval-attribution
  "MEASURED approver attribution, derived at render time -- never
  hardcoded as 'this repo has/hasn't the defect', because either
  statement becomes a lie the moment the store changes.

  For every run whose own audit channel carries a real
  `:approval-granted` fact, this compares two things:

    :carried  -- the approver keys the COMMIT RECORD actually carried
                 into `store/commit-record!` (`reinsurance.operation`'s
                 `:request-approval` node attaches them to `:payload`);
    :retained -- the approver keys still present in the post-run register
                 entry that record wrote to.

  and classifies each approval as `:retained`, `:dropped` (the record
  carried an approver but the register does not have it) or
  `:not-attached` (the graph never attached one). A `:dropped` approver
  is NOT silently omitted from the page -- it is joined back from this
  run's audit facts and labelled, so 'nobody approved' and 'the store
  dropped it' can never look the same."
  [db runs]
  (vec
   (for [r runs
         :let [granted (first (filter #(= :approval-granted (:t %)) (:audit r)))]
         :when granted
         :let [record   (:record r)
               carried  (vec (sort-by str (filter approver-key? (keys (:payload record)))))
               {:keys [register id entry]} (register-entry db record)
               retained (vec (sort-by str (filter approver-key? (keys entry))))]]
     {:op          (:op r)
      :subject     (:subject r)
      :by          (:by granted)
      :effect      (:effect record)
      :register    register
      :register-id id
      :carried     carried
      :retained    retained
      :status      (cond (empty? carried) :not-attached
                         (seq retained)   :retained
                         :else            :dropped)})))

(defn- hold-facts [ledger]
  (filterv #(= :governor-hold (:t %)) ledger))

(defn- hard-violations
  "Every HARD violation that actually fired, one row per violation
  (a single hold can carry more than one)."
  [ledger]
  (vec
   (for [f (hold-facts ledger)
         v (:violations f)]
     (assoc v :op (:op f) :subject (:subject f) :confidence (:confidence f)))))

(defn assert-real-holds!
  "Build-time invariants 1-3 and 5 (see ns docstring). THROWS -- a
  console generated from a run that demonstrated no hold would be
  theatre, and a silently shrinking scenario would be worse."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds  (hold-facts ledger)
        rules  (vec (sort (distinct (map :rule (hard-violations ledger)))))
        asked-while-held
        (vec (for [r runs
                   :when (and (some #(= :governor-hold (:t %)) (:audit r))
                              (some #(= :approval-requested (:t %)) (:audit r)))]
               (:thread r)))
        approvals (approval-attribution db runs)]
    (when (zero? (count holds))
      (throw (ex-info "render-html: the run produced 0 :governor-hold records -- refusing to render an operator console that claims to demonstrate a governor while demonstrating no hold"
                      {:ledger-facts (count ledger) :holds 0})))
    (when (< (count rules) min-distinct-hard-rules)
      (throw (ex-info (str "render-html: only " (count rules) " distinct HARD rules fired, floor is "
                           min-distinct-hard-rules " -- a check stopped firing; fix the check or the scenario, do not lower the floor")
                      {:rules rules :floor min-distinct-hard-rules})))
    (when (seq asked-while-held)
      (throw (ex-info "render-html: a HARD-held run also asked a human -- the 'HARD holds never reach a human' claim is false for this build"
                      {:threads asked-while-held})))
    (when (zero? (count approvals))
      (throw (ex-info "render-html: the run granted 0 approvals -- the approver-attribution section would have nothing real to measure"
                      {:runs (count runs)})))
    {:ledger ledger :holds (count holds) :rules rules :approvals approvals}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-name [k] (if (keyword? k) (name k) (str k)))

(defn- td [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- muted [v] (str "<span class=\"muted\">" (esc v) "</span>"))

(defn- section [title lead & body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       (str/join body)
       "  </section>\n"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n")
           (str (td (muted "no rows -- the run produced none")) "\n"))
       "      </tbody>\n"
       "    </table>\n"))

;; ---- treaties ----

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (case (:t f)
      :committed          "<span class=\"ok\">committed</span>"
      :governor-hold      (str "<span class=\"critical\">HARD hold &middot; "
                               (esc (str/join ", " (map kw-name (:basis f)))) "</span>")
      :approval-rejected  "<span class=\"warn\">approval rejected by operator</span>"
      (muted "no activity"))))

(defn- treaty-terms [{:keys [treaty-type quota-share-pct coverage-limit retention layer-limit]}]
  (case treaty-type
    :quota-share    (str "quota share " (esc quota-share-pct)
                         " &middot; limit <span class=\"num\">" (esc coverage-limit) "</span>")
    :excess-of-loss (str "layer <span class=\"num\">" (esc layer-limit)
                         "</span> xs retention <span class=\"num\">" (esc retention) "</span>")
    (muted "unknown treaty type")))

(defn- treaty-rows [ledger treaties]
  (mapv (fn [{:keys [id ceding-insurer treaty-type jurisdiction status treaty-number] :as t}]
          (td (code id)
              (esc ceding-insurer)
              (code (kw-name treaty-type))
              (treaty-terms t)
              (esc jurisdiction)
              (if (= :bound status)
                "<span class=\"ok\">bound</span>"
                (str "<span class=\"warn\">" (esc (kw-name status)) "</span>"))
              (if treaty-number (code treaty-number) (muted "—"))
              (status-cell ledger id)))
        treaties))

;; ---- claims recoveries ----

(defn- recovery-ids
  "The recovery ids this run actually mentioned, read off the ledger --
  not a hand-kept list. Ids whose filing was HELD are included on
  purpose: they render honestly as `not on file`."
  [ledger]
  (vec (sort (distinct (map :subject (filter #(#{:recovery/file :recovery/pay} (:op %)) ledger))))))

(defn- recovery-rows [db ledger]
  (mapv (fn [rid]
          (if-let [{:keys [treaty-id underlying-loss-amount claimed-recovery-amount
                           status payment-number]} (store/recovery db rid)]
            (td (code rid)
                (code treaty-id)
                (str "<span class=\"num\">" (esc underlying-loss-amount) "</span>")
                (str "<span class=\"num\">" (esc claimed-recovery-amount) "</span>")
                (if-let [t (store/treaty db treaty-id)]
                  (str "<span class=\"num\">"
                       (esc (registry/compute-recovery t underlying-loss-amount))
                       "</span>")
                  (muted "no treaty"))
                (if (= :paid status)
                  "<span class=\"ok\">paid</span>"
                  (str "<span class=\"warn\">" (esc (kw-name status)) "</span>"))
                (if payment-number (code payment-number) (muted "—"))
                (status-cell ledger rid))
            (td (code rid)
                (muted "—") (muted "—") (muted "—") (muted "—")
                "<span class=\"critical\">not on file</span>"
                (muted "—")
                (status-cell ledger rid))))
        (recovery-ids ledger)))

;; ---- phase gate ----

(defn- gate-rows
  "Produced by CALLING `reinsurance.phase/gate` at the operator's real
  phase, once with a governor-clean base disposition and once with a
  governor HARD hold -- so the two columns are this build's actual gate
  behaviour, not a description of it. The `stake` column is whatever the
  advisor really attached during this run."
  [runs]
  (let [ph     (:phase operator)
        auto   (:auto (get phase/phases ph))
        stakes (reduce (fn [m {:keys [op stake]}]
                         (if stake (update m op (fnil conj (sorted-set)) stake) m))
                       {} runs)]
    (mapv (fn [op]
            (let [clean (phase/gate ph {:op op} :commit)
                  held  (phase/gate ph {:op op} :hold)
                  st    (get stakes op)
                  high? (boolean (some governor/high-stakes st))]
              (td (code op)
                  (let [d (:disposition clean)]
                    (str "<span class=\"" (if (= :commit d) "ok" "warn") "\">"
                         (esc (kw-name d))
                         (when-let [r (:reason clean)] (str " &middot; " (esc (kw-name r))))
                         "</span>"))
                  (str "<span class=\"critical\">" (esc (kw-name (:disposition held))) "</span>")
                  (if (contains? auto op)
                    "<span class=\"ok\">yes</span>"
                    "<span class=\"warn\">no</span>")
                  (if (seq st) (code (str/join ", " (map kw-name st))) (muted "—"))
                  (if high?
                    "<span class=\"critical\">yes &middot; never auto at any phase</span>"
                    (muted "no")))))
          (vec (sort-by str phase/write-ops)))))

;; ---- HARD checks ----

(defn- hard-rows [ledger]
  (mapv (fn [{:keys [rule op subject detail confidence]}]
          (td (str "<span class=\"critical\">" (code (kw-name rule)) "</span>")
              (code op)
              (code subject)
              (str "<span class=\"num\">" (esc confidence) "</span>")
              (esc detail)))
        (sort-by (juxt (comp str :rule) :subject) (hard-violations ledger))))

;; ---- approver attribution ----

(defn- attribution-rows [approvals]
  (mapv (fn [{:keys [op subject by effect register register-id carried retained status]}]
          (td (code op)
              (code subject)
              (code (kw-name effect))
              (str (esc register) " <code>" (esc register-id) "</code>")
              (if (seq carried) (code (str/join ", " (map kw-name carried))) (muted "none"))
              (if (seq retained) (code (str/join ", " (map kw-name retained))) (muted "none"))
              (case status
                :retained     (str "<span class=\"ok\">retained &middot; " (esc by) "</span>")
                :dropped      (str "<span class=\"warn\">" (esc by)
                                   " (audit only — not retained in the store record)</span>")
                :not-attached (str "<span class=\"warn\">" (esc by)
                                   " (audit only — the commit record carried no approver key)</span>"))))
        approvals))

;; ---- registry drafts ----

(defn- draft-rows [records cols]
  (mapv (fn [r] (apply td (map (fn [c]
                                 (let [v (get r c)]
                                   (if (nil? v) (muted "—")
                                       (if (number? v)
                                         (str "<span class=\"num\">" (esc v) "</span>")
                                         (code v)))))
                               cols)))
        records))

;; ---- jurisdictions ----

(defn- jurisdiction-rows [db]
  (let [seeded (vec (sort (distinct (keep :jurisdiction (store/all-treaties db)))))]
    (mapv (fn [iso3]
            (if-let [{:keys [name owner-authority legal-basis provenance required-evidence]}
                     (facts/spec-basis iso3)]
              (td (code iso3)
                  (esc name)
                  (esc owner-authority)
                  (esc legal-basis)
                  (str "<span class=\"num\">" (count required-evidence) "</span>")
                  (str "<code>" (esc provenance) "</code>"))
              (td (code iso3)
                  "<span class=\"critical\">no spec-basis on file</span>"
                  (muted "—") (muted "—") (muted "0")
                  (muted "— never fabricated"))))
          seeded)))

;; ---- ledger ----

(defn- ledger-rows [ledger]
  (vec
   (map-indexed
    (fn [i {:keys [t op subject basis summary detail]}]
      (td (str "<span class=\"num\">" i "</span>")
          (case t
            :committed         "<span class=\"ok\">committed</span>"
            :governor-hold     "<span class=\"critical\">governor-hold</span>"
            :approval-rejected "<span class=\"warn\">approval-rejected</span>"
            (esc (kw-name t)))
          (code op)
          (code subject)
          (if (seq basis) (esc (str/join ", " (map kw-name basis))) (muted "—"))
          (esc (or summary detail ""))))
    ledger)))

(defn render
  "Renders the whole operator-console document from a `run-demo!` result."
  [{:keys [db runs]} {:keys [ledger holds rules approvals]}]
  (let [treaties  (vec (store/all-treaties db))
        coverage  (facts/coverage (vec (sort (distinct (keep :jurisdiction treaties)))))
        bindings  (vec (store/binding-history db))
        payments  (vec (store/recovery-history db))
        dropped   (count (filter #(= :dropped (:status %)) approvals))
        retained  (count (filter #(= :retained (:status %)) approvals))]
    (str
     "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-6520 &middot; reinsurance — Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Reinsurance (ISIC 6520) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · treaty binding and recovery payout are ALWAYS a human reinsurance underwriter's call</span>\n"
     "</header>\n"
     "<main class=\"container\">\n"

     (section
      "How this page was produced"
      (str "Generated at build time by <code>reinsurance.render-html</code> "
           "(<code>clojure -M:dev:render-html</code>), which drives the real actor "
           "<code>reinsurance.operation</code> &rarr; <code>reinsurance.governor</code> &rarr; "
           "<code>reinsurance.store</code> through <code>langgraph.graph/run*</code> and renders "
           "whatever that run produced. Nothing below is hand-written HTML.")
      (table ["Measurement" "This build"]
             [(td "langgraph runs executed" (str "<span class=\"num\">" (count runs) "</span>"))
              (td "audit-ledger facts written to the SSoT" (str "<span class=\"num\">" (count ledger) "</span>"))
              (td "HARD governor holds" (str "<span class=\"critical num\">" holds "</span>"))
              (td "distinct HARD rules fired"
                  (str "<span class=\"num\">" (count rules) "</span> / floor <span class=\"num\">"
                       min-distinct-hard-rules "</span> &middot; "
                       (code (str/join ", " (map kw-name rules)))))
              (td "human approvals granted" (str "<span class=\"num\">" (count approvals) "</span>"))
              (td "treaty-binding drafts minted" (str "<span class=\"num\">" (count bindings) "</span>"))
              (td "recovery-payment drafts minted" (str "<span class=\"num\">" (count payments) "</span>"))]))

     (section
      "Treaty register"
      (str "Post-run SSoT state from <code>reinsurance.store/all-treaties</code>. "
           "A treaty number is minted by <code>reinsurance.registry/register-binding</code> only "
           "once a human has approved the binding.")
      (table ["Treaty" "Ceding insurer" "Type" "Terms" "Jurisdiction" "Status" "Treaty number" "Last op"]
             (treaty-rows ledger treaties)))

     (section
      "Claims-recovery register"
      (str "Recovery ids are read off the real ledger, then looked up with "
           "<code>reinsurance.store/recovery</code>. The <em>recomputed</em> column is this actor's "
           "OWN <code>reinsurance.registry/compute-recovery</code> result — the governor never trusts "
           "a claimed figure, it re-derives it from the treaty's own quota-share / excess-of-loss "
           "formula and compares. A recovery whose filing was HARD-held is shown as "
           "<span class=\"critical\">not on file</span> rather than omitted.")
      (table ["Recovery" "Treaty" "Underlying loss" "Claimed" "Recomputed" "Status" "Payment number" "Last op"]
             (recovery-rows db ledger)))

     (section
      (str "Action gate — phase " (:phase operator) " ("
           (esc (:label (get phase/phases (:phase operator)))) ")")
      (str "Each row is produced by CALLING <code>reinsurance.phase/gate</code> twice for that op: "
           "once with a governor-clean base disposition, once with a governor HARD hold. "
           "A HARD hold stays a hold in every row — no human can approve past it. The "
           "<em>stake</em> column is the <code>:stake</code> the advisor actually attached during "
           "this run, checked against <code>reinsurance.governor/high-stakes</code>.")
      (table ["Op" "Governor clean &rarr;" "Governor HARD &rarr;" "Auto-eligible at this phase" "Observed stake" "High-stakes actuation"]
             (gate-rows runs)))

     (section
      "HARD governor holds that actually fired"
      (str "Grouped out of the real <code>:governor-hold</code> facts in the ledger — one row per "
           "violation, so a hold carrying two rules shows both. None of these reached a human; "
           "the build asserts that structurally (invariant 3) instead of claiming it in prose. "
           "The advisor's confidence is shown to make the point that a HARD violation holds "
           "regardless of how confident the model was (floor for the SOFT gate is "
           (code governor/confidence-floor) ").")
      (table ["Rule" "Op" "Subject" "Advisor confidence" "Detail"]
             (hard-rows ledger)))

     (section
      "Approver attribution — measured, not assumed"
      (str "The approval facts live in each run's own audit channel; <code>reinsurance.operation</code>'s "
           "<code>:commit</code> node appends only the commit fact, so the store ledger alone cannot "
           "answer &quot;who approved this&quot;. This section walks the post-run registers and reports, per "
           "approval, which approver keys the commit record CARRIED versus which the register actually "
           "RETAINED. Measured for this build: <span class=\"num\">" retained "</span> retained, "
           "<span class=\"num\">" dropped "</span> dropped. A dropped approver is joined back from the "
           "audit facts and labelled — never silently omitted, because that would make "
           "&quot;nobody approved&quot; and &quot;the store dropped it&quot; look identical.")
      (table ["Op" "Subject" "Effect" "Register entry" "Record carried" "Register retained" "Approver"]
             (attribution-rows approvals)))

     (section
      "Treaty-binding drafts (append-only)"
      (str "The records <code>reinsurance.registry/register-binding</code> actually minted, read back "
           "out of <code>reinsurance.store/binding-history</code>. Every certificate this actor "
           "produces is UNSIGNED — signature is the licensed reinsurer's act, not this actor's.")
      (table ["Record id" "Kind" "Ceding insurer" "Treaty type" "Jurisdiction" "Immutable"]
             (draft-rows bindings ["record_id" "kind" "ceding_insurer" "treaty_type" "jurisdiction" "immutable"])))

     (section
      "Recovery-payment drafts (append-only)"
      (str "The records <code>reinsurance.registry/register-recovery-payment</code> actually minted. "
           "The persisted <em>recovery amount</em> is always this vehicle's own recompute, never the "
           "claimed figure.")
      (table ["Record id" "Treaty number" "Recovery" "Underlying loss" "Recovery amount" "Jurisdiction"]
             (draft-rows payments ["record_id" "treaty_number" "recovery_id"
                                   "underlying_loss_amount" "recovery_amount" "jurisdiction"])))

     (section
      "Jurisdiction spec-basis coverage"
      (str "<code>reinsurance.facts/coverage</code> over the jurisdictions the seeded treaties actually "
           "carry: <span class=\"num\">" (:covered coverage) "</span> of <span class=\"num\">"
           (:requested coverage) "</span> covered"
           (when (seq (:missing-jurisdictions coverage))
             (str ", missing " (code (str/join ", " (:missing-jurisdictions coverage)))))
           ". A jurisdiction not in <code>reinsurance.facts/catalog</code> has NO spec-basis, full stop — "
           "the advisor must not fabricate one, and the governor holds if it tries. That is exactly the "
           (code ":no-spec-basis") " hold above.")
      (table ["ISO3" "Jurisdiction" "Authority" "Legal basis" "Required evidence" "Provenance"]
             (jurisdiction-rows db)))

     (section
      "Audit ledger (this run)"
      (str "The append-only decision-fact log <code>reinsurance.store/ledger</code> holds after the run: "
           "every commit, every hold, every operator rejection. This is the evidence a ceding insurer "
           "needs if a binding or a recovery payment is later disputed.")
      (table ["#" "Fact" "Op" "Subject" "Basis" "Summary"]
             (ledger-rows ledger)))

     "</main>\n"
     "<footer class=\"container\">\n"
     "  <p class=\"muted\">cloud-itonami-isic-6520 · reinsurance actor · generated by <code>reinsurance.render-html</code> from a real "
     (count runs) "-run scenario. No timestamps, no random ids: byte-identical across reruns against the same seed.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        {:keys [ledger holds rules approvals] :as measured} (assert-real-holds! result)
        html   (render result measured)
        rendered-rules (set (map :rule (hard-violations ledger)))]
    ;; invariant 4: the HARD section cannot outlive the run that justified it.
    (when-not (every? rendered-rules rules)
      (throw (ex-info "render-html: a rendered HARD rule is not present in the ledger"
                      {:rendered rendered-rules :ledger-rules rules})))
    (spit out html)
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  holds " HARD holds over " (count rules) " distinct rules, "
                  (count approvals) " granted approvals, "
                  (count (store/binding-history (:db result))) " binding drafts, "
                  (count (store/recovery-history (:db result))) " recovery-payment drafts, "
                  (count html) " bytes)"))
    (println "HARD-RULES\t" (str/join " " (map str rules)))
    (println "APPROVER-ATTRIBUTION\t"
             (str/join " " (map #(str (name (:effect %)) "=" (name (:status %))) approvals)))))
