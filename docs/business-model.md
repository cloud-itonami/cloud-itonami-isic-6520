# Business Model: Reinsurance

## Classification

- Repository: `cloud-itonami-isic-6520`
- ISIC Rev.5: `6520`
- Activity: treaty and facultative reinsurance -- risk-transfer contracting between a ceding insurer and a reinsurer, claims-recovery processing and retrocession
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- regional and mutual reinsurers
- ceding insurers seeking a transparent treaty-administration platform instead of a closed broker-run system
- reinsurance pools

## Offer

- treaty and facultative contract intake and proposal
- ceded-premium and claims-recovery bordereaux processing
- retrocession tracking
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per treaty
- support: monthly retainer with SLA
- migration: import from an incumbent treaty-administration system
- bordereaux-processing fee

## Trust Controls

- no treaty is bound and no recovery is paid without human sign-off
- a fabricated jurisdiction bordereaux/collateral citation, unsupported
  binding evidence, a recovery filed against an unbound treaty, or a
  claimed recovery amount that does not match this vehicle's own
  independent quota-share/excess-of-loss recompute -- each forces a
  hold, not an override
- a recovery cannot be paid twice: a double-payment attempt is held off
  this actor's own recovery-payment history alone, with no upstream
  comparison needed
- every intake, assessment, binding, filing and payment path is
  auditable
- emergency manual override paths remain outside LLM control
