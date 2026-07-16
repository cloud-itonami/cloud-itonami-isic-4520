# Security Policy

This project handles repair-order, shop-license, and vehicle safety-concern
coordination data. Treat vulnerabilities as potentially high impact even
when the demo data is synthetic — a safety concern that silently
auto-resolves, or a roadworthiness-clearance decision that slips past the
governor, has direct road-safety consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential or shop-license-key exposure
- AutoRepairGovernor bypass (rbac, registration-gate, effect-propose-only,
  closed-op-allowlist, roadworthiness-clearance-scope-exclusion)
- audit-ledger tampering
- `:flag-safety-concern` auto-committing at any phase
- any op finalizing a roadworthiness/safe-to-drive clearance decision
- logging/scheduling/coordinating against a repair-order or shop-license
  that is not independently registered and active

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

## Production Guidance

- Store secrets and shop-license keys outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Alert on any registration-gate or roadworthiness-clearance-scope-
  exclusion HOLD spike — it may indicate a compromised or malfunctioning
  upstream feed, or a compromised advisor.
