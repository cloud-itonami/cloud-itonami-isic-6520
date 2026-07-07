(ns reinsurance.phase
  "Phase 0->3 staged rollout -- the reinsurance analog of
  `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- treaty intake allowed, every write needs
                                 human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:treaty/intake`/`:recovery/file` (no
                                 capital risk yet) may auto-commit.
                                 `:treaty/bind`/`:recovery/pay` NEVER
                                 auto-commit, at any phase.

  `:treaty/bind`/`:recovery/pay` are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural fact,
  not a rollout milestone still to come. Binding a real reinsurance
  treaty and paying out a real claims-recovery amount are the two
  real-world legal/financial acts this actor performs; both are always
  a human reinsurance underwriter's call. `reinsurance.governor`'s
  `:actuation/bind`/`:actuation/pay-recovery` high-stakes gate enforces
  the same invariant independently -- two layers, not one, agree on
  this. `:treaty/intake`/`:recovery/file` move no capital yet (still
  HARD-gated in `reinsurance.governor`, but never `high-stakes`), so
  both ARE auto-eligible at phase 3, the same multi-auto-op posture
  `cloud-itonami-isic-6512`'s `casualty.phase` already establishes.")

(def read-ops  #{})
(def write-ops #{:treaty/intake :jurisdiction/assess
                 :treaty/bind :recovery/file :recovery/pay})

;; NOTE the invariant: `:treaty/bind`/`:recovery/pay` are members of
;; `write-ops` (governor-gated like any write) but are NEVER members of
;; any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                              :auto #{}}
   1 {:label "assisted-intake" :writes #{:treaty/intake}                                :auto #{}}
   2 {:label "assisted-assess" :writes #{:treaty/intake :jurisdiction/assess}            :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:treaty/intake :recovery/file}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:treaty/bind`/`:recovery/pay` are never auto-eligible at any phase,
    so they always escalate once the governor clears them (or hold if
    the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Reinsurance Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
