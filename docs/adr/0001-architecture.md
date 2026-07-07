# ADR-0001: cloud-itonami-isic-6520 -- Treaty-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629` ADR-0001s
  (the pattern this ADR ports; `6512`'s and `6622`'s ADRs establish the
  "write the lesson down, don't just fix it" discipline this build
  benefits from without needing to repeat), ADR-2607032000 (`cloud-
  itonami` insurance (ISIC 65/66) + real-estate (ISIC 68) coverage push
  -- the blueprint scaffold this ADR deepens), langgraph-clj ADR-0001
  (Pregel superstep + interrupt + Datomic checkpoint)
- Context: `cloud-itonami-isic-6520` published a business/operator-model
  blueprint (ADR-2607032000's insurance coverage push) but stopped at
  `:blueprint` maturity -- no governed actor implementation. This ADR
  deepens it to `:implemented`, the sixth insurance-adjacent actor in
  the fleet, continuing the SAME "pick a new ISIC blueprint vertical"
  direction that produced `6512`/`6621`/`6622`/`6629`.

## Problem

Reinsurance treaty administration bundles two real-world acts under one
governed workflow:

1. **Jurisdiction bordereaux/collateral disclosure correctness** -- is
   the required evidence for binding a treaty based on an official
   regulator, or invented?
2. **Treaty binding** -- committing a ceding insurer and a reinsurer to
   real risk-transfer terms, a real legal/financial act.
3. **Claims-recovery arithmetic correctness** -- is a claimed recovery
   amount actually the correct output of the treaty's OWN quota-share
   percentage-and-cap formula, or its OWN excess-of-loss layer formula?
   This is not a party-screening or best-interest-duty question (as in
   `6621`/`6622`) -- it is a pure MATH-verification question, the same
   shape `cloud-itonami-isic-6629`'s `apportionment-mismatch-violations`
   established for general-average apportionment, applied here to a
   DIFFERENT domain-specific formula (treaty math, not marine-loss
   apportionment).
4. **Real actuation, twice** -- binding a real treaty and paying out a
   real claims-recovery amount are both irreversible acts a ceding
   insurer will rely on.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run reinsurance treaty administration with an
LLM" but "seal the LLM inside a trust boundary and layer bordereaux-
evidence-sufficiency, treaty-lifecycle correctness, recovery-arithmetic
correctness, audit and human-approval on top of it, while structurally
fixing both real actuation events as human-only."

## Decision

### 1. Treaty-LLM is sealed into the bottom node; it never binds or pays directly

`reinsurance.treatyllm` returns exactly five kinds of proposal: intake
normalization, jurisdiction bordereaux/collateral checklist, treaty-
binding draft, recovery-filing normalization, and recovery-payment
draft. No proposal writes the SSoT or commits a real treaty binding /
recovery payout directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 reinsurance operation

`reinsurance.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. Reinsurance Governor is a separate system from Treaty-LLM, with a claims-recovery lifecycle mirroring `6512`'s claim lifecycle

`reinsurance.governor` mirrors `casualty.governor`'s policy/claim shape
almost exactly: `:treaty/bind` is this actor's `:policy/bind` analog,
and the `:recovery/file` -> `:recovery/pay` pair is this actor's
`:claim/file` -> `:claim/settle` analog (file moves no capital and is
auto-eligible once the treaty is bound; pay is always human-gated
actuation). Unlike `6512`, there is no party-screening check at all --
see Decision 5.

### 4. `recovery-calculation-mismatch-violations` reuses the CROSS-CHECK independent-recompute pattern established within `6629`, applied to a NEW formula

`reinsurance.registry/compute-recovery` independently recomputes the
recovery owed via the treaty's OWN quota-share (percentage-of-loss,
capped at an aggregate limit) or excess-of-loss (layer between
retention and the top of the treaty) formula, and the governor compares
this recompute against the recovery request's OWN claimed amount --
structurally the SAME "never trust a claimed number, independently
re-derive it" discipline `cloud-itonami-isic-6629`'s `apportionment-
mismatch-violations` established for general-average apportionment,
applied here to a genuinely different well-known formula (reinsurance
treaty math, not marine-loss pro-rata sharing). No sibling actor in the
insurance-adjacent family other than `6629` has an arithmetic-
verification check at all; `6511`/`6512`/`6621`/`6622`'s checks are all
party-screening (sanctions/conflict-of-interest).

### 5. This actor has NO party/conflict-of-interest check, like `6629` and unlike three of its five siblings

There is no party analogous to a policyholder, claimant, adjuster or
broker whose identity needs screening in a treaty-administration
workflow -- the ceding insurer is a named contractual counterparty, not
a screened individual. `reinsurance.store` deliberately has no `party`/
`conflict-of` protocol methods at all (see its own docstring) -- adding
an unused KYC-shaped collection just to match the majority-sibling
pattern would be exactly the kind of premature abstraction this
workspace's engineering discipline rejects, the SAME judgment `6629`'s
own ADR already made for its own core failure mode.

### 6. `treaty-not-bound-violations` checks `:status :bound` directly, safely -- unlike `6622`'s lesson, this ISIC's status lifecycle has no further transition to trip over

`cloud-itonami-isic-6622`'s `placement-not-bound-violations` bug arose
because a placement's status legitimately advanced PAST `:bound` (to
`:commission-booked`) after a later successful action, so re-checking
`:status :bound` spuriously refired on an unrelated later attempt. A
treaty's status never advances past `:bound` -- recovery filing and
payment only ever change the RECOVERY's own status, never the treaty's
-- so `reinsurance.governor/treaty-not-bound-violations` safely checks
`:status :bound` directly, the same simpler pattern `casualty.governor/
policy-not-bound-violations` already uses safely (a policy's status
likewise never advances past `:bound`). This reasoning is written
directly into the check's own docstring so a future edit does not
reintroduce `6622`'s bug by analogy without re-verifying the lifecycle
still holds.

### 7. Real actuation is structurally always human-only (enforced by two independent layers)

`reinsurance.governor`'s `high-stakes` set has two members
(`:actuation/bind` and `:actuation/pay-recovery`, matching `6512`'s/
`6622`'s dual-actuation shape, not `6511`'s/`6621`'s/`6629`'s single-
actuation one -- this ISIC class's two real-world acts, binding and
paying, are genuinely distinct events), and `reinsurance.phase`'s phase
table never puts `:treaty/bind`/`:recovery/pay` in any phase's `:auto`
set.

### 8. No fabricated international treaty/recovery-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a treaty-binding or recovery-
payment reference number. `reinsurance.registry` therefore does not
invent one; it validates required fields and assigns a jurisdiction-
scoped sequence number only.

### 9. Relationship to `kotoba-lang/insurance`

Same self-contained-sibling relationship every prior insurance actor in
this fleet has to the shared capability lib -- no code dependency.

## Consequences

- (+) Reinsurance gets the same governed, auditable-actor treatment as
  the five other insurance-adjacent actors, without centralizing
  liability in one vendor -- any licensed reinsurer can fork and run
  their own instance.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/reinsurance/phase_test.clj`'s
  `treaty-bind-never-auto-at-any-phase` / `recovery-pay-never-auto-at-
  any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/reinsurance/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern every sibling actor uses, including a dedicated
  assertion that the PERSISTED recovery-payment record carries this
  actor's own recomputed amount, not a claimed one.
- (+) The recovery-calculation-mismatch check is a genuine, DIFFERENT-
  formula contribution to this actor family -- an arithmetic-
  verification check applying `6629`'s established pattern to
  reinsurance treaty math -- proven by a dedicated demo scenario using
  an intentionally-wrong claimed recovery amount and a passing test
  suite on the FIRST run (no equivalent bug to `6512`'s sanctions-hold
  scoping bug or `6622`'s status-lifecycle bug, since this build's
  status-lifecycle risk was reasoned through up front -- see Decision 6
  -- rather than discovered by a failing demo).
- (-) This R0 seeds only 4 jurisdictions (JPN, USA-NY, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `reinsurance.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `compute-recovery` models only the CORE quota-share/excess-of-loss
  formulas, not a full treaty's real-world terms (aggregate
  deductibles, reinstatement premiums, multi-layer towers are out of
  scope -- see that fn's own docstring); retrocession tracking, real
  banking-payment integration, and tax/regulatory reporting are all out
  of scope for this OSS actor -- each operator's responsibility (see
  README's coverage table).
- 36 tests / 171 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6520` at `:blueprint` only | ❌ | Leaves reinsurance without an `:implemented` reference actor, unlike five of its ISIC siblings |
| Add a party/conflict-of-interest check for consistency with `6511`/`6512`/`6621`/`6622` | ❌ | No party analogous to a policyholder/claimant/broker exists in a treaty-administration workflow; forcing an unused party/conflict concept in just to match the majority-sibling shape would be premature abstraction, not honest domain modeling -- the same judgment `6629`'s own ADR made |
| Check `treaty-not-bound-violations` off a persisted `:treaty-number` field instead of `:status :bound`, defensively matching `6622`'s fix even though not strictly needed here | ❌ | `6622`'s fix was needed because placement status legitimately advances PAST `:bound`; a treaty's status never does, so checking `:status` directly is simpler AND correct here -- copying a fix for a bug that cannot occur would be needless defensive complexity, not rigor |
| Model a full treaty's real-world terms (aggregate deductibles, reinstatement premiums, multi-layer towers) for conformance-test rigor | ❌ | Genuinely more complex real-world reinsurance structuring that this R0 does not claim to model correctly -- honestly scoped to the core quota-share/excess-of-loss formulas instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Require `kotoba.insurance` (the capability lib) directly from `reinsurance.*` | ❌ | No sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
