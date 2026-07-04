# cloud-itonami-isic-6520

Open Business Blueprint for **ISIC Rev.5 6520**: Reinsurance.

This repository designs a forkable OSS business for treaty and facultative reinsurance -- risk-transfer contracting between a ceding insurer and a reinsurer, claims-recovery processing and retrocession -- run by a qualified, licensed operator so a community or
independent professional never surrenders customer data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a secure document-custody robot manages physical treaty-archive vaulting and courier between ceding insurer and reinsurer,
under an actor that proposes actions and an independent **Reinsurance Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + case/policy records
        |
        v
Treaty-LLM -> Reinsurance Governor -> hold, proceed, or human approval
        |
        v
case/policy ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: binding a treaty or paying a claims-recovery amount.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6520`). Required capabilities are implemented by:

- [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance)
  -- policy, premium, claim and underwriting-decision contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`Treaty-LLM` + `Reinsurance Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
