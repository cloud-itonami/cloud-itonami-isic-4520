# ADR-0001: cloud-itonami-isic-4520 — AutoRepair-LLM を封じ込めた自動車整備工場オペレーション調整アクター設計

- Status: Accepted (2026-07-16)
- 関連: `cloud-itonami-isic-4510`(同ドメインファミリー — 車両販売の
  VehicleSaleGovernor パターンの直接の手本)、sibling 4530(自動車部品販売)/
  4540(二輪車)actors(同バッチで別 agent が実装予定、対象外)

## 課題

`kotoba-lang/industry` registry の未着手 `:spec` スロットから ISIC Rev.4
4520「Maintenance and repair of motor vehicles」を選定した。自動車整備工場の
業務は直接的な道路安全(road-safety)側面を持つため、この actor は
**オペレーション調整(operations coordination)専用**とし、整備完了の是非や
走行可否(roadworthiness clearance)を**一切確定させない**設計とする。

## 決定

### 1. AutoRepair-LLM は最下層の1ノードに封じ込め、直接確定させない

> **AutoRepair-LLM は、AutoRepairGovernor が拒否するサービス記録ログ・
> スケジュール提案・安全懸念フラグ・部品発注提案を決して直接確定しない。**

### 2. 閉じた op allowlist(4種、すべて `:effect :propose`)

- `:log-service-record` — 修理オーダー/使用部品/工数の記録提案
- `:schedule-service-operation` — ベイ/整備士のスケジューリング提案
- `:flag-safety-concern` — 欠陥/リコール/不安全な修理の懸念を提起。
  **常にエスカレート**(どの phase でも `:auto` に含まれない)
- `:coordinate-parts-order` — 部品調達の調整提案(閾値超過でエスカレート)

**この4種のみ**が許可される。走行可否・整備完了を直接確定する op は
**設計上、存在しない**。

### 3. AutoRepairGovernor は5つの HARD チェック(SOFT/エスカレートは別軸)

1. `rbac` — actor-role が op の権限を持つか
2. `registration-gate` — 参照する repair-order が登録済みか、かつその
   shop-license が有効か(いずれも捏造不可)
3. `effect-propose-only` — 提案の `:effect` が文字通り `:propose` か
4. `closed-op-allowlist` — op が4種のいずれかか
5. `roadworthiness-clearance-scope-exclusion` — **どの op であっても**、
   走行可否/整備完了の最終判断(roadworthiness clearance)を確定しようと
   する提案は、恒久的かつ人間承認でも上書き不能な HARD ブロック。
   `:hold` に直行し `:request-approval` ノードには到達しない(グラフ構造上、
   オーバーライド経路が存在しない)。

SOFT(常にエスカレート、`:auto` には決して含まれない):
`:flag-safety-concern` は毎回・どの phase でも・どの confidence でも
エスカレートする。`:coordinate-parts-order` は閾値超過でエスカレート。
confidence が floor 未満でもエスカレート。

### 4. 自己トリップ回避 — scope-exclusion 用語は ACTION フレーズで書く

このファミリーの複数 sibling agent が独立に発見・修正した既知のバグ
クラス: governor 自身の scope-exclusion 用語リストが裸の名詞
(例: "safety")で書かれていると、`:flag-safety-concern` 自身の正当な
デフォルト rationale(必然的に「安全」/「safety」という語を含む)の
内部にマッチしてしまい、actor が自分の happy path を自己ブロックする。
この actor では `finalization-action-phrases` を "finalize the
roadworthiness clearance" のような複合 ACTION フレーズのみで構成し、
`autorepair.governor-contract-test/default-advisor-proposals-never-self-trip-scope-exclusion`
を専用の regression テストとして追加した。

### 5. Robotics premise: false

この actor は調整・記録・エスカレーションのみのデジタルサービスであり、
実車の修理作業・整備機器の制御・走行可否判定は actor の境界外。

## Consequences

- (+) `kotoba-lang/industry` registry の 4520 スロットが実装へ昇格。
- (+) `clojure -M:dev:test`/`clojure -M:lint` で検証済み。
- (-) この actor は roadworthiness authority を一切持たない — 実際の
  整備完了判断・走行可否判定は常に人間(整備士/検査員)が別途行う。

## References

- `90-docs/adr/2607141000-cloud-itonami-isic-4510-motor-vehicle-sale-actor.md`
  (存在すれば; 無ければ `cloud-itonami-isic-4510` リポジトリ自身の
  `docs/adr/0001-architecture.md`)
