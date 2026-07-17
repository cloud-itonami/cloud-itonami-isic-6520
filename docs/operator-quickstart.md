# Operator Quickstart: Reinsurance

## Prerequisites

1. **Clojure CLI** — Install [Clojure](https://clojure.org/guides/install_clojure) (1.10.1 or later; `.clj`/`.cljc` portable syntax)
2. **This repository** — Clone or fork [`cloud-itonami-isic-6520`](https://github.com/com-junkawasaki/cloud-itonami-isic-6520)
3. **Monorepo (optional)** — If running inside the [workspace monorepo](https://github.com/com-junkawasaki/), dependencies resolve to local checkouts. Standalone forks should update `deps.edn` to use git coordinates for `langgraph-clj` and `langchain-clj`.

## Run Tests

Verify the Reinsurance Governor contract, phase invariants, and registry conformance:

```bash
clojure -M:dev:test
```

Tests cover:
- Governor's five HARD checks (spec-basis, evidence-incomplete, treaty-not-bound, recovery-missing, recovery-calculation-mismatch)
- Double-payment guard
- Phase state machine (0→3: read-only → intake → assess → supervised)
- Store parity (MemStore vs DatomicStore)
- Registry conformance for quota-share and excess-of-loss recovery math
- Per-jurisdiction bordereaux/collateral fact coverage

## Run Demo Simulation

Walk through two clean treaty-bind + recovery-payment lifecycles and five HARD-hold cases:

```bash
clojure -M:dev:run
```

The demo driver (`reinsurance.sim`) exercises the OperationActor with mock advisor proposals and logs governor verdicts and holds to stdout.

## Static Analysis

Lint with clj-kondo (errors fail CI):

```bash
clojure -M:lint
```

## Governor Location

The Reinsurance Governor is at **`src/reinsurance/governor.cljc`**.

Key entry points:
- **`:actuation/bind`** — gate for treaty binding (never auto; enforces human approval)
- **`:actuation/pay-recovery`** — gate for claims-recovery payment (never auto; enforces human approval)
- **5 HARD checks** — spec-basis, evidence-incomplete, treaty-not-bound, recovery-missing, recovery-calculation-mismatch
- **Double-payment guard** — checked against the actor's own recovery-payment history

The Governor is independent of the Treaty-LLM (in `src/reinsurance/treatyllm.cljc`). The LLM proposes; the Governor verifies. Any violation forces a `:hold` state, not an override.

## Actor Graph

The OperationActor is built with `langgraph-clj` StateGraph at **`src/reinsurance/operation.cljc`**:

```
read-only intake
    ↓
assisted intake (Treaty-LLM proposal)
    ↓
Governor: verify intake ← spec-basis, evidence checklist
    ↓
assisted assess (Treaty-LLM summary for binding)
    ↓
Governor: verify binding ← evidence completeness, jurisdiction requirements
    ↓
supervised bind (human sign-off OR hold)
    ↓
recovery file (claims filing against bound treaty)
    ↓
Governor: verify recovery ← treaty bound? calculation match? double-payment?
    ↓
supervised pay (human sign-off OR hold)
    ↓
immutable audit ledger
```

Phases 0–3 map to: read-only → intake-eligible → assess-eligible → supervised. Treaty binding and recovery payment are NEVER in any phase's `:auto` set.

## Phase State Machine

Defined in **`src/reinsurance/phase.cljc`**. Each phase gates which operations are auto-eligible vs require approval:

- **Phase 0 (read-only):** intake-ingest only
- **Phase 1 (assisted intake):** intake actions (`:treaty/intake`, `:jurisdiction/assess`)
- **Phase 2 (assisted assess):** assessment actions (governance review before binding proposal)
- **Phase 3 (supervised):** binding and payment (always human; uses Governor's hold/approve logic)

## Store Backend

Two implementations in **`src/reinsurance/store.cljc`**:

- **MemStore** — in-memory hashmap (testing, demos)
- **DatomicStore** — Datomic (append-only ledger, audit trail, schema enforcement)

Both implement the same `Store` protocol. Production deployments use DatomicStore.

## Jurisdiction Facts & Bordereaux

**`src/reinsurance/facts.cljc`** catalogs per-jurisdiction reinsurance requirements with official spec-basis citations:

- Currently seeded: **JPN**, **USA-NY**, **GBR**, **DEU** (4 out of ~194 jurisdictions worldwide)
- To add a jurisdiction: one map entry in the catalog, citing a real official source
- Never fabricate jurisdiction requirements to inflate coverage reports

## Recovery Math (Simplified)

**`src/reinsurance/registry.cljc`** implements quota-share and excess-of-loss recovery calculations.

**What it does model:** basic percentage-of-loss and layer-threshold logic for the two treaty types.

**What it does NOT model:** aggregate deductibles, reinstatement premiums, multi-layer towers. See the docstring in `compute-recovery` for the full honest list of simplifications.

The Governor's `:recovery-calculation-mismatch` check re-computes recovery independently and compares against the filed amount. A PoC WASM port is in `wasm/recovery_mismatch.kotoba` (see `wasm/README.md` for offset layout).

## Troubleshooting

**Tests fail with `ExceptionInfo`**
- Check that `langgraph-clj` and `langchain-clj` are available: either as local checkouts (monorepo mode, `:dev` alias) or via git coordinates (standalone fork).

**Demo doesn't produce output**
- Ensure stderr is visible: `clojure -M:dev:run 2>&1 | head -50`

**Governor holds a proposal I think should pass**
- Check `src/reinsurance/governor.cljc` for the reason: spec-basis citation missing, evidence incomplete, treaty not bound, recovery calc mismatch, or double-payment attempted. The hold is by design.

## Next Steps

1. **Read `docs/business-model.md`** for customer, offer, revenue, and trust controls.
2. **Read `docs/operator-guide.md`** for minimum production controls and certification steps.
3. **Review `docs/adr/0001-architecture.md`** for full architecture and design rationale.
4. **Customize `reinsurance.facts/catalog`** with your jurisdiction's real bordereaux requirements and citations.
5. **Deploy with a licensed reinsurer:** the blueprint supplies governance; you supply the license, actuarial expertise, and payment integrations.

## License

Code and templates are AGPL-3.0-or-later. See `LICENSE` in the repository root.
