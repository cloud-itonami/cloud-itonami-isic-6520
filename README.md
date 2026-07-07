# cloud-itonami-isic-6520

Open Business Blueprint for **ISIC Rev.5 6520**: Reinsurance. This
repository publishes a treaty and facultative reinsurance actor --
risk-transfer contracting between a ceding insurer and a reinsurer,
claims-recovery processing -- as an OSS business that any qualified,
licensed reinsurer can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511)
(life insurance), [`cloud-itonami-isic-6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512)
(non-life insurance), [`cloud-itonami-isic-6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621)
(independent loss adjustment), [`cloud-itonami-isic-6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622)
(insurance intermediation) and [`cloud-itonami-isic-6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629)
(insurance auxiliary services). Here it is **Treaty-LLM ⊣ Reinsurance
Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a treaty
> summary, normalizing intake, and running the quota-share/excess-of-
> loss recovery math -- but it has **no notion of which jurisdiction's
> bordereaux/collateral requirements are official, no license to bind a
> treaty or pay a real claims-recovery amount, and no way to know on its
> own whether a claimed recovery figure is arithmetically correct**.
> Letting it bind a treaty or pay a recovery directly invites fabricated
> jurisdiction citations, silently-wrong recovery math that a ceding
> insurer would actually rely on, and liability for whoever runs it.
> This project seals the Treaty-LLM into a single node and wraps it with
> an independent **Reinsurance Governor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers treaty intake through binding, and claims-recovery
filing through payment, for two treaty types: quota-share and excess-
of-loss. It does **not**, by itself, hold a license to write
reinsurance in any jurisdiction, and it does not claim to. It also does
**not** model a full treaty's real-world terms -- no aggregate
deductibles, no reinstatement premiums, no multi-layer towers (see
`reinsurance.registry/compute-recovery`'s own docstring for the honest
simplification this makes). Whoever deploys and operates a live
instance (a licensed reinsurer) supplies the jurisdiction-specific
license, the real actuarial/treaty-wording expertise and the real
banking-payment integrations, and bears that jurisdiction's liability
-- the software supplies the governed, spec-cited, audited execution
scaffold so that operator does not have to build the compliance layer
from scratch for every new market.

### Actuation

**Binding a real reinsurance treaty and paying out a real claims-
recovery amount are never autonomous, at any phase, by construction.**
Two independent layers enforce this (`reinsurance.governor`'s
`:actuation/bind`/`:actuation/pay-recovery` high-stakes gate and
`reinsurance.phase`'s phase table, which never puts `:treaty/bind`/
`:recovery/pay` in any phase's `:auto` set) -- see `reinsurance.phase`'s
docstring and `test/reinsurance/phase_test.clj`'s `treaty-bind-never-
auto-at-any-phase`/`recovery-pay-never-auto-at-any-phase`. The actor may
draft, check and recommend; a human reinsurance underwriter is always
the one who actually binds a treaty or pays out a recovery.

## The core contract

```
treaty intake + jurisdiction facts (reinsurance.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Treaty-LLM   │ ─────────────▶ │ Reinsurance Governor     │  (independent system)
   │  (sealed)    │  + citations    │ spec-basis · evidence-   │
   └──────────────┘                 │ incomplete · treaty-not- │
                             commit ◀────┼──────────▶ hold │ bound · recovery-missing ·
                                 │             │           │ recovery-calculation-mismatch
                           record + ledger  escalate ─▶ human   (independent recompute,
                                             (ALWAYS for         un-overridable) · double-payment
                                              :treaty/bind /
                                              :recovery/pay)
```

**The Treaty-LLM never binds a treaty or pays a recovery the
Reinsurance Governor would reject, and never does so without a human
sign-off.** Hard violations (fabricated jurisdiction requirements;
unsupported bordereaux/collateral evidence; a recovery filed against an
unbound treaty; a recovery amount that does not match this vehicle's
own independent quota-share/excess-of-loss recompute; a double payment)
force **hold** and *cannot* be approved past; a clean binding/payment
proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean treaty-bind + recovery-payment lifecycles + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a secure document-custody
robot manages physical treaty-archive vaulting and courier between
ceding insurer and reinsurer, under the actor, gated by the independent
**Reinsurance Governor**. The governor never dispatches hardware
itself; `:high`/`:safety-critical` actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Reinsurance Governor, treaty-binding + recovery-payment draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6520`). Related capability contracts (policy/premium/claim shapes) are
published as [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance);
this actor's `reinsurance.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship its insurance siblings have
toward the same lib.

## Layout

| File | Role |
|---|---|
| `src/reinsurance/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + treaty-binding/recovery-payment history. No separate party/conflict concept -- this actor's distinctive check is an independent-recompute check, not a party screen |
| `src/reinsurance/registry.cljc` | Treaty-binding + recovery-payment draft records, plus `compute-recovery` (REAL, simplified quota-share/excess-of-loss formulas -- see docstring for what they do not model) |
| `src/reinsurance/facts.cljc` | Per-jurisdiction reinsurance bordereaux/collateral-disclosure catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/reinsurance/treatyllm.cljc` | **Treaty-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/binding/recovery-filing/recovery-payment proposals |
| `src/reinsurance/governor.cljc` | **Reinsurance Governor** -- 5 HARD checks (spec-basis · evidence-incomplete · treaty-not-bound · recovery-missing · recovery-calculation-mismatch, independent recompute) + double-payment guard + 1 soft (confidence/actuation gate) |
| `src/reinsurance/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (binding/payment always human; treaty intake + recovery filing auto-eligible, no capital risk) |
| `src/reinsurance/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/reinsurance/sim.cljc` | demo driver |
| `test/reinsurance/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers treaty intake through binding, and claims-recovery
filing through payment -- the core governed lifecycle this blueprint's
own `docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Treaty intake + per-jurisdiction bordereaux/collateral checklisting, HARD-gated on an official spec-basis citation (`:treaty/intake`/`:jurisdiction/assess`) | Aggregate deductibles, reinstatement premiums, multi-layer towers (see `compute-recovery`'s docstring) |
| Treaty binding, independently checked against the jurisdiction's own required-evidence checklist (`:treaty/bind`) | Real transfer-agent/banking-payment integration, tax/regulatory reporting |
| Claims-recovery filing against a bound treaty (`:recovery/file`, HARD-gated on the treaty actually being bound) | Retrocession tracking, multi-treaty consolidated recoveries |
| Claims-recovery payment, independently re-verified against this vehicle's OWN quota-share/excess-of-loss recompute, with a double-payment guard (`:recovery/pay`) | |
| Immutable audit ledger for every intake/assessment/binding/filing/payment decision | |

Extending coverage is additive: add the next gate (e.g. retrocession
tracking) as its own governed op with its own HARD checks and tests,
following the SAME "an independent governor re-verifies against the
actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`reinsurance.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `reinsurance.facts/catalog` --
currently 4 seeded (JPN, USA-NY, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `reinsurance.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Treaty-LLM` + `Reinsurance Governor` run as real,
tested code (see `Run` above), promoted from the originally-published
`:blueprint`-tier scaffold, modeled closely on the sibling
`cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`'s architecture.
See `docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
