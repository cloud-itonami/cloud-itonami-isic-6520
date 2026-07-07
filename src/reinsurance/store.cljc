(ns reinsurance.store
  "SSoT for the reinsurance actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam `cloud-itonami-isic-
  6511`'s `underwriting.store` / `cloud-itonami-isic-6512`'s
  `casualty.store` / `cloud-itonami-isic-6621`'s `adjustment.store` /
  `cloud-itonami-isic-6622`'s `intermediation.store` / `cloud-itonami-
  isic-6629`'s `auxiliary.store` use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/reinsurance/store_contract_test.clj), which is the whole point:
  the actor, the Reinsurance Governor and the audit ledger never know
  which SSoT they run on.

  Like `auxiliary.store` (`cloud-itonami-isic-6629`), this Store has NO
  separate `party`/`conflict-of` concept -- this actor's distinctive
  HARD check (`recovery-calculation-mismatch-violations`, see
  `reinsurance.governor`) is an independent-recompute check on a
  recovery request's OWN claimed amount against `reinsurance.registry/
  compute-recovery`, not a party-screening check, so there is genuinely
  nothing for a party directory to do here.

  The ledger stays append-only on every backend: 'which treaty was
  bound with which ceding insurer on what jurisdictional basis, which
  claims-recovery was filed and paid against which treaty, approved by
  whom' is always a query over an immutable log -- the audit trail a
  ceding insurer trusting a reinsurer with a treaty needs, and the
  evidence an operator needs if a binding or a recovery payment is
  later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [reinsurance.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (treaty [s id])
  (all-treaties [s])
  (recovery [s id])
  (assessment-of [s treaty-id] "committed jurisdiction bordereaux-evidence assessment, or nil")
  (ledger [s])
  (binding-history [s] "the append-only treaty-binding history (reinsurance.registry drafts)")
  (recovery-history [s] "the append-only recovery-payment history (reinsurance.registry drafts)")
  (next-sequence [s jurisdiction] "next treaty-number sequence for a jurisdiction")
  (recovery-sequence [s jurisdiction] "next recovery-payment-number sequence for a jurisdiction")
  (recovery-already-paid? [s recovery-id] "has this recovery already been paid?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-treaties [s treaties] "replace/seed the treaty directory (map id->treaty)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained treaty set (both treaty types) so the actor +
  tests run offline."
  []
  {:treaties
   {"treaty-1" {:id "treaty-1" :ceding-insurer "Sakura General Insurance"
                :treaty-type :quota-share :quota-share-pct 0.5 :coverage-limit 5000000
                :jurisdiction "JPN" :status :intake}
    "treaty-2" {:id "treaty-2" :ceding-insurer "Atlantis Assurance"
                :treaty-type :quota-share :quota-share-pct 0.4 :coverage-limit 2000000
                :jurisdiction "ATL" :status :intake}
    "treaty-3" {:id "treaty-3" :ceding-insurer "Britannia Mutual"
                :treaty-type :excess-of-loss :retention 1000000 :layer-limit 4000000
                :jurisdiction "GBR" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- bind!
  "Backend-agnostic `:treaty/mark-bound` -- looks up the treaty via the
  protocol, drafts the treaty-binding record, and returns
  {:result .. :treaty-patch ..} for the caller to persist. Pure w.r.t.
  any particular backend's transaction mechanics."
  [s treaty-id]
  (let [t (treaty s treaty-id)
        seq-n (next-sequence s (:jurisdiction t))
        result (registry/register-binding
                (:ceding-insurer t) (:treaty-type t) (:jurisdiction t) seq-n)]
    {:result result
     :treaty-patch {:status :bound
                   :treaty-number (get result "treaty_number")}}))

(defn- pay-recovery!
  "Backend-agnostic `:recovery/mark-paid` -- looks up the recovery + its
  treaty via the protocol, INDEPENDENTLY recomputes the recovery amount
  via `registry/compute-recovery` (never trusts the recovery's own
  claimed amount -- the governor has already verified they match within
  tolerance, but the authoritative record persisted is always this
  vehicle's own math, the same discipline `auxiliary.store/finalize!`
  uses for apportionment), drafts the recovery-payment record, and
  returns {:result .. :recovery-patch ..} for the caller to persist."
  [s recovery-id]
  (let [r (recovery s recovery-id)
        t (treaty s (:treaty-id r))
        recomputed (registry/compute-recovery t (:underlying-loss-amount r))
        seq-n (recovery-sequence s (:jurisdiction t))
        result (registry/register-recovery-payment
                (:treaty-number t) recovery-id (:underlying-loss-amount r)
                recomputed (:jurisdiction t) seq-n)]
    {:result result
     :recovery-patch {:status :paid
                      :payment-number (get result "payment_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (treaty [_ id] (get-in @a [:treaties id]))
  (all-treaties [_] (sort-by :id (vals (:treaties @a))))
  (recovery [_ id] (get-in @a [:recoveries id]))
  (assessment-of [_ treaty-id] (get-in @a [:assessments treaty-id]))
  (ledger [_] (:ledger @a))
  (binding-history [_] (:bindings @a))
  (recovery-history [_] (:payments @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (recovery-sequence [_ jurisdiction] (get-in @a [:recovery-sequences jurisdiction] 0))
  (recovery-already-paid? [_ recovery-id] (= :paid (get-in @a [:recoveries recovery-id :status])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :treaty/upsert
      (swap! a update-in [:treaties (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :treaty/mark-bound
      (let [treaty-id (first path)
            {:keys [result treaty-patch]} (bind! s treaty-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:jurisdiction (get-in state [:treaties treaty-id]))] (fnil inc 0))
                       (update-in [:treaties treaty-id] merge treaty-patch)
                       (update :bindings registry/append result))))
        result)

      :recovery/filed
      (swap! a assoc-in [:recoveries (:id payload)] payload)

      :recovery/mark-paid
      (let [recovery-id (first path)
            {:keys [result recovery-patch]} (pay-recovery! s recovery-id)
            jurisdiction (:jurisdiction (treaty s (:treaty-id (recovery s recovery-id))))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:recovery-sequences jurisdiction] (fnil inc 0))
                       (update-in [:recoveries recovery-id] merge recovery-patch)
                       (update :payments registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-treaties [s treaties] (when (seq treaties) (swap! a assoc :treaties treaties)) s))

(defn seed-db
  "A MemStore seeded with the demo treaty set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :ledger [] :sequences {}
                           :bindings [] :recoveries {} :recovery-sequences {} :payments []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts, binding/
  payment records) are stored as EDN strings so `langchain.db` doesn't
  expand them into sub-entities -- the same convention every sibling
  actor's store uses."
  {:treaty/id                  {:db/unique :db.unique/identity}
   :recovery/id                {:db/unique :db.unique/identity}
   :assessment/treaty-id       {:db/unique :db.unique/identity}
   :ledger/seq                 {:db/unique :db.unique/identity}
   :binding/seq                {:db/unique :db.unique/identity}
   :payment/seq                {:db/unique :db.unique/identity}
   :sequence/jurisdiction      {:db/unique :db.unique/identity}
   :recovery-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- treaty->tx [{:keys [id ceding-insurer treaty-type quota-share-pct coverage-limit
                          retention layer-limit jurisdiction status treaty-number]}]
  (cond-> {:treaty/id id}
    ceding-insurer     (assoc :treaty/ceding-insurer ceding-insurer)
    treaty-type        (assoc :treaty/treaty-type treaty-type)
    quota-share-pct    (assoc :treaty/quota-share-pct quota-share-pct)
    coverage-limit     (assoc :treaty/coverage-limit coverage-limit)
    retention          (assoc :treaty/retention retention)
    layer-limit        (assoc :treaty/layer-limit layer-limit)
    jurisdiction       (assoc :treaty/jurisdiction jurisdiction)
    status             (assoc :treaty/status status)
    treaty-number      (assoc :treaty/treaty-number treaty-number)))

(def ^:private treaty-pull
  [:treaty/id :treaty/ceding-insurer :treaty/treaty-type :treaty/quota-share-pct
   :treaty/coverage-limit :treaty/retention :treaty/layer-limit
   :treaty/jurisdiction :treaty/status :treaty/treaty-number])

(defn- pull->treaty [m]
  (when (:treaty/id m)
    {:id (:treaty/id m) :ceding-insurer (:treaty/ceding-insurer m)
     :treaty-type (:treaty/treaty-type m) :quota-share-pct (:treaty/quota-share-pct m)
     :coverage-limit (:treaty/coverage-limit m) :retention (:treaty/retention m)
     :layer-limit (:treaty/layer-limit m)
     :jurisdiction (:treaty/jurisdiction m) :status (:treaty/status m)
     :treaty-number (:treaty/treaty-number m)}))

(defn- recovery->tx [{:keys [id treaty-id underlying-loss-amount claimed-recovery-amount status payment-number]}]
  (cond-> {:recovery/id id}
    treaty-id                (assoc :recovery/treaty-id treaty-id)
    underlying-loss-amount   (assoc :recovery/underlying-loss-amount underlying-loss-amount)
    claimed-recovery-amount  (assoc :recovery/claimed-recovery-amount claimed-recovery-amount)
    status                   (assoc :recovery/status status)
    payment-number           (assoc :recovery/payment-number payment-number)))

(def ^:private recovery-pull
  [:recovery/id :recovery/treaty-id :recovery/underlying-loss-amount
   :recovery/claimed-recovery-amount :recovery/status :recovery/payment-number])

(defn- pull->recovery [m]
  (when (:recovery/id m)
    {:id (:recovery/id m) :treaty-id (:recovery/treaty-id m)
     :underlying-loss-amount (:recovery/underlying-loss-amount m)
     :claimed-recovery-amount (:recovery/claimed-recovery-amount m)
     :status (:recovery/status m) :payment-number (:recovery/payment-number m)}))

(defrecord DatomicStore [conn]
  Store
  (treaty [_ id]
    (pull->treaty (d/pull (d/db conn) treaty-pull [:treaty/id id])))
  (all-treaties [_]
    (->> (d/q '[:find [?id ...] :where [?e :treaty/id ?id]] (d/db conn))
         (map #(pull->treaty (d/pull (d/db conn) treaty-pull [:treaty/id %])))
         (sort-by :id)))
  (recovery [_ id]
    (pull->recovery (d/pull (d/db conn) recovery-pull [:recovery/id id])))
  (assessment-of [_ treaty-id]
    (dec* (d/q '[:find ?p . :in $ ?tid
                :where [?a :assessment/treaty-id ?tid] [?a :assessment/payload ?p]]
              (d/db conn) treaty-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (binding-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :binding/seq ?s] [?e :binding/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (recovery-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :payment/seq ?s] [?e :payment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (recovery-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :recovery-sequence/jurisdiction ?j] [?e :recovery-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (recovery-already-paid? [s recovery-id]
    (= :paid (:status (recovery s recovery-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :treaty/upsert
      (d/transact! conn [(treaty->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/treaty-id (first path) :assessment/payload (enc payload)}])

      :treaty/mark-bound
      (let [treaty-id (first path)
            {:keys [result treaty-patch]} (bind! s treaty-id)
            jurisdiction (:jurisdiction (treaty s treaty-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(treaty->tx (assoc treaty-patch :id treaty-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:binding/seq (count (binding-history s)) :binding/record (enc (get result "record"))}])
        result)

      :recovery/filed
      (d/transact! conn [(recovery->tx payload)])

      :recovery/mark-paid
      (let [recovery-id (first path)
            {:keys [result recovery-patch]} (pay-recovery! s recovery-id)
            jurisdiction (:jurisdiction (treaty s (:treaty-id (recovery s recovery-id))))
            next-n (inc (recovery-sequence s jurisdiction))]
        (d/transact! conn
                     [(recovery->tx (assoc recovery-patch :id recovery-id))
                      {:recovery-sequence/jurisdiction jurisdiction :recovery-sequence/next next-n}
                      {:payment/seq (count (recovery-history s)) :payment/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-treaties [s treaties]
    (when (seq treaties) (d/transact! conn (mapv treaty->tx (vals treaties)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:treaties
  ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [treaties]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-treaties s treaties))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo treaty set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
