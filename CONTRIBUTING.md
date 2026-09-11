# Contributing

`cloud-itonami-isic-4520` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
kbb -M:dev:test
kbb -M:lint
```

Keep changes small and include tests for governor, phase, store, or
audit-ledger behavior.

## Rules

- Do not commit real repair-order, customer, or shop-license data.
- Keep production coordination proposals behind AutoRepairGovernor.
- Never add an op that finalizes a roadworthiness/safe-to-drive clearance
  decision or controls repair-shop equipment — this actor is operations
  coordination only.
- Treat every new check as high-risk: add tests for rbac, registration-gate,
  effect-propose-only, closed-op-allowlist, roadworthiness-clearance-
  scope-exclusion, confidence floor, and audit logging.
- If you touch `autorepair.governor`'s scope-exclusion term list, phrase
  new terms as compound finalization-ACTION phrases (e.g. "finalize the
  roadworthiness clearance"), never as a bare noun like "safety" or
  "roadworthy" — a bare noun will match inside `:flag-safety-concern`'s own
  legitimate rationale and self-block the actor's happy path. Run
  `autorepair.governor-contract-test/default-advisor-proposals-never-self-trip-scope-exclusion`
  after any change here.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
