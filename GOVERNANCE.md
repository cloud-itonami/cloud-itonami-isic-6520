# Governance

`cloud-itonami-6520` is an OSS open-business blueprint for treaty and facultative reinsurance -- risk-transfer contracting between a ceding insurer and a reinsurer, claims-recovery processing and retrocession.
Governance covers both the capability layer and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the Reinsurance Governor remains independent of the advisor.
- hard policy violations (fabricated spec-basis, sanctions hit, incomplete
  records) cannot be overridden by human approval.
- binding a treaty or paying a claims-recovery amount always escalates to a human -- never automated.
- every hold, approval and disbursement path is auditable.
- personal and customer data stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:

- bypassing the Reinsurance Governor's policy checks
- mishandling customer data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
