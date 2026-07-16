# Governance

`cloud-itonami-isic-4520` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- AutoRepair-LLM cannot directly log a service record, schedule an
  operation, flag a safety concern, or coordinate a parts order — every
  action is a censored proposal.
- AutoRepairGovernor remains independent of the advisor.
- hard governor violations (rbac, registration-gate, effect-propose-only,
  closed-op-allowlist, roadworthiness-clearance-scope-exclusion) cannot be
  overridden by human approval.
- no op anywhere in the closed allowlist finalizes a roadworthiness/
  safe-to-drive clearance decision, and any attempt to smuggle one through
  an otherwise-legitimate op is permanently blocked, structurally (never
  routed to human approval).
- `:flag-safety-concern` never auto-commits, at any rollout phase, at any
  confidence.
- a repair-order/shop-license record must be independently registered and
  active before any of the four ops may proceed against it.
- every commit and hold decision is auditable via the append-only ledger.
- real repair-order, customer, or shop-license data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.
