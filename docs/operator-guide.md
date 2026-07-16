# Operator Guide

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-4520
cd cloud-itonami-isic-4520
clojure -M:dev:test
clojure -M:dev:run
```

## 2. Production Checklist

- replace demo repair-orders/shop-licenses with real, independently
  verified/registered data (never invent a repair-order or shop-license —
  `autorepair.governor`'s registration-gate rejects anything not on file)
- configure Datomic Local or an equivalent durable SSoT
- define service-writer/technician/shop-manager RBAC rules for your shop
- set `autorepair.governor/parts-order-cost-threshold` to your shop's real
  approval threshold
- run `clojure -M:dev:test` / `clojure -M:lint`
- verify audit-ledger export
- get written legal/insurance review before connecting this actor to any
  real roadworthiness-adjacent workflow — this actor structurally never
  finalizes such a decision, and your operating procedures must not either

## 3. Operator Responsibilities

- lawful basis for handling customer/vehicle repair-order data
- secure infrastructure and shop isolation
- honest repair-order/shop-license registration (no fabricated records)
- human review workflow for every safety-concern flag and over-threshold
  parts order
- data-retention policy
