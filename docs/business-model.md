# Open Business Blueprint: cloud-itonami-isic-4520

Operations-coordination platform for independent and franchise auto-repair
shops.

## Classification

- ISIC Rev.4 4520: Maintenance and repair of motor vehicles
- Distinct from `cloud-itonami-isic-4510` (vehicle sales — title/lien/
  odometer) and sibling 4530 (parts sale) / 4540 (motorcycle repair):
  this actor coordinates repair-shop *operations*, not sales or parts
  inventory.

## Customer

- independent/franchise repair-shop service writers
- technicians logging work and surfacing safety concerns
- shop managers approving escalations

## Offer

- governed service-record logging (parts used, labor hours)
- bay/technician scheduling coordination
- structural safety-concern surfacing that always reaches a human
- governed, cost-threshold-aware parts-order coordination
- immutable audit ledger

## Revenue

- per-shop subscription fee
- wholesale API access to other cloud-itonami blueprint operators
- parts-vendor integration package

## Non-Negotiables

- Do not commit real repair-order, customer, or shop-license data.
- Do not add an op that finalizes a roadworthiness/safe-to-drive clearance
  decision or controls repair-shop equipment.
- Do not bypass AutoRepairGovernor.
- Do not let a safety-concern flag auto-resolve at any phase.
- Do not log/schedule/coordinate against an unregistered or inactively
  licensed repair-order/shop.
