# cloud-itonami-isic-4520

Open Business Blueprint for **ISIC Rev.4 4520**: maintenance and repair of
motor vehicles — an auto-repair-shop **operations coordination** platform,
published as an OSS business that any qualified operator can fork, deploy,
run, improve and sell.

Distinct from [`cloud-itonami-isic-4510`](https://github.com/cloud-itonami/cloud-itonami-isic-4510)
(vehicle sales — title/lien/odometer) and the sibling 4530 (motor-vehicle
parts sale) / 4540 (motorcycle) actors: this actor coordinates the
day-to-day operations of a repair shop — service-record logging, bay/
technician scheduling, safety-concern surfacing, and parts-order
coordination. Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime.

> **Why an actor layer at all?** An AutoRepair-LLM is great at normalizing
> service-log entries and drafting scheduling/parts-order proposals — but it
> has **no authority whatsoever over roadworthiness**, no notion of which
> repair-order/shop-license records are actually registered, and no
> business surfacing a safety concern that should ever skip human review.
> This project seals the AutoRepair-LLM into a single node and wraps it
> with an independent **AutoRepairGovernor**, a human **review workflow**,
> and an immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor is **operations coordination only**. It is NOT direct
roadworthiness-clearance authority and it does NOT control repair-shop
equipment. There is no op anywhere in this schema that finalizes a
roadworthiness/safe-to-drive clearance decision — and even a compromised or
off-spec advisor attempting to smuggle one in through an otherwise-
legitimate op is a HARD, PERMANENT, un-overridable block (see
`src/autorepair/governor.cljk`'s `:roadworthiness-clearance-scope-exclusion`
gate and `docs/adr/0001-architecture.md`).

Every proposal this actor can ever commit carries `:effect :propose` — the
SSoT record that lands is always a *coordination log entry* (a record that
a proposal was logged), never an executed real-world repair action.

## The core contract

```
request + injected role/phase context
        │
        ▼
   ┌────────────────┐  proposal      ┌──────────────────────────┐
   │ AutoRepair-LLM  │ ─────────────▶│ AutoRepairGovernor        │
   │ (sealed)        │  :propose only│  rbac · registration ·    │
   └────────────────┘                │  scope-exclusion · human  │
                                      └──────────────────────────┘
                                              │
                                   commit / log only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: AutoRepair-LLM never logs, schedules, flags, or
coordinates a record the AutoRepairGovernor would reject.

## The four allowed ops (closed allowlist)

- `:log-service-record` — repair-order/parts-used/labor-hours data logging
- `:schedule-service-operation` — bay/technician scheduling proposal
- `:flag-safety-concern` — surface a defect/recall/unsafe-repair concern
  — **ALWAYS escalates to a human**, at every rollout phase, regardless of
  confidence
- `:coordinate-parts-order` — parts procurement proposal (escalates above a
  cost threshold)

## Run

```bash
kbb -M:dev:test
kbb -M:dev:run
kbb -M:lint
```

## Non-Negotiables

- Do not commit real VINs, real repair-order/customer data, or real
  shop-license records.
- Do not add an op that finalizes a roadworthiness-clearance decision or
  controls repair-shop equipment.
- Do not bypass the AutoRepairGovernor for production coordination
  proposals.
- Do not let `:flag-safety-concern` auto-commit at any phase — it always
  reaches a human.
- Do not log a service record, schedule a slot, or coordinate a parts
  order against a repair-order/shop-license that is not independently
  registered and active.

License: AGPL-3.0-or-later.
