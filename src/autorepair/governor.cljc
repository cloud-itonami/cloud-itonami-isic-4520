(ns autorepair.governor
  "AutoRepairGovernor — the independent compliance layer that earns the
  AutoRepair-LLM the right to log a service record, propose a scheduling
  slot, flag a safety concern, or coordinate a parts order. The LLM has no
  notion of which repair orders/shop licenses are actually registered, and
  no authority whatsoever over roadworthiness — so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor is OPERATIONS COORDINATION ONLY — it never controls repair-shop
  equipment and never finalizes a roadworthiness-clearance decision (e.g.
  certifying a repaired vehicle as safe to drive). Two structural gates
  enforce that boundary independent of any human judgement call:

    - `:effect-propose-only` — every proposal this actor can ever commit
      carries `:effect :propose` (never a real-world action verb). What
      lands in the SSoT via `autorepair.store/commit-record!` is always a
      *coordination log entry* (a record that a proposal was made/logged),
      dispatched on the request's `:op`, never on `:effect` — `:effect`
      itself never varies, by design, so it cannot be repurposed as a
      side-channel execution signal.
    - `:roadworthiness-clearance-scope-exclusion` — ANY proposal (on ANY
      of the four allowed ops) that attempts to finalize a roadworthiness/
      safe-to-drive clearance is a HARD, PERMANENT block. Hard violations
      route straight to `:hold` in `autorepair.operation` and NEVER reach
      the `:request-approval` human-review node — there is no override
      path, by construction, not by policy convention.

  Five checks, in priority order. All five are HARD violations: a human
  approver CANNOT override any of them (there is no SOFT-hard mix here —
  see `autorepair.phase` for the separate escalate-vs-auto axis).

    1. rbac                        — does actor-role have permission for op?
    2. registration-gate            — does the referenced repair-order exist,
                                      AND does its shop carry an ACTIVE
                                      shop-license? Neither may be invented.
    3. effect-propose-only          — is the proposal's :effect literally
                                      :propose?
    4. closed-op-allowlist          — is op one of the four allowed ops?
    5. roadworthiness-clearance-scope-exclusion — does the proposal (by
                                      structured :value key or by an
                                      explicit finalize/certify/clear ACTION
                                      phrase in its summary/rationale)
                                      attempt to finalize a roadworthiness
                                      clearance?

  Independently of hard violations, three SOFT conditions always route to
  human escalation (never auto-commit, at any phase, any confidence) — see
  `autorepair.phase` for how these compose with rollout phase:

    - `:flag-safety-concern` — ALWAYS escalates. Surfacing a defect/recall/
      unsafe-repair concern is exactly the kind of judgement call that must
      reach a human, every time, regardless of the advisor's confidence.
    - a `:coordinate-parts-order` above `parts-order-cost-threshold`.
    - LLM confidence below `confidence-floor`.

  IMPORTANT — a known self-tripping bug in this actor family: check #5
  MUST be phrased as compound finalization-ACTION phrases (\"finalize the
  roadworthiness clearance\", \"certify as safe to drive\"), never as a
  bare noun like \"safety\" or \"roadworthy\". `:flag-safety-concern`'s own
  legitimate default rationale necessarily contains the bare word
  \"safety\"/\"安全\" — a bare-noun scope-exclusion term would self-trip on
  the actor's own happy path. See `autorepair.governor-contract-test`'s
  `default-advisor-proposals-never-self-trip-scope-exclusion` test, which
  exists specifically to catch a regression here."
  (:require [clojure.string :as str]
            [autorepair.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def parts-order-cost-threshold
  "Above this amount (in the demo's fictitious currency unit),
  `:coordinate-parts-order` always escalates to a human, regardless of
  confidence."
  1500.00M)

(def allowed-ops
  "Closed allowlist — the ONLY four operations this actor may ever propose.
  Deliberately excludes anything that would directly finalize a
  roadworthiness-clearance decision or control repair-shop equipment."
  #{:log-service-record :schedule-service-operation
    :flag-safety-concern :coordinate-parts-order})

(def permissions
  "actor-role → set of operations it may propose."
  {:service-writer #{:log-service-record :schedule-service-operation :coordinate-parts-order}
   :technician     #{:log-service-record :flag-safety-concern}
   :shop-manager   allowed-ops})

;; A curated set of compound finalization-ACTION phrases (never a bare
;; noun) — see the namespace docstring for why bare nouns self-trip.
(def finalization-action-phrases
  #{"finalize the roadworthiness clearance"
    "finalize roadworthiness clearance"
    "certify the vehicle as roadworthy"
    "certify as safe to drive"
    "certify the vehicle as safe to drive"
    "confirm roadworthiness clearance"
    "clear the vehicle for road use"
    "clear the vehicle as roadworthy"
    "sign off on roadworthiness"
    "roadworthiness clearance を確定する"
    "roadworthiness clearance を最終化する"
    "整備完了として走行可否を証明する"
    "車両を走行可能と認定する"
    "車両の安全走行を最終認定する"})

;; Structured :value keys that would ALSO indicate a finalize-roadworthiness
;; attempt, independent of any free-text phrase — defense in depth so a
;; future advisor that stops writing prose rationale can't slip past #5.
(def forbidden-clearance-value-keys
  #{:roadworthiness-status :roadworthiness-clearance :clearance-decision
    :certified-safe-to-drive? :roadworthy? :inspection-clearance})

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- registration-gate-violations
  "Neither the repair-order nor its shop-license may be invented or
  assumed active — both must already be independently registered in the
  store before ANY of the four ops may proceed."
  [{:keys [subject]} proposal st]
  (let [order-id (or (get-in proposal [:value :order-id]) subject)
        ro (store/repair-order st order-id)]
    (cond
      (nil? ro)
      [{:rule :registration-gate
        :detail (str "未登録の repair-order: order-id=" order-id)}]

      :else
      (let [lic (store/shop-license st (:shop-id ro))]
        (cond
          (nil? lic)
          [{:rule :registration-gate
            :detail (str "shop-license が見つからない: shop-id=" (:shop-id ro))}]

          (not (:active? lic))
          [{:rule :registration-gate
            :detail (str "shop-license が無効: shop-id=" (:shop-id ro))}]

          :else nil)))))

(defn- effect-propose-only-violations
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-propose-only
      :detail (str "この actor の :effect は :propose のみ許可: 実際="
                   (pr-str (:effect proposal)))}]))

(defn- closed-allowlist-violations
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :closed-op-allowlist :detail (str "許可されていない op: " op)}]))

(defn- scope-exclusion-violations
  "Hard, permanent block — no human approval path exists for this
  violation (see namespace docstring)."
  [proposal]
  (let [value (:value proposal)
        key-hit (some forbidden-clearance-value-keys (keys (or value {})))
        text (str/lower-case (str (:summary proposal) " " (:rationale proposal)))
        phrase-hit (some #(str/includes? text (str/lower-case %))
                         finalization-action-phrases)]
    (when (or key-hit phrase-hit)
      [{:rule :roadworthiness-clearance-scope-exclusion
        :detail (str "roadworthiness clearance の確定を試みる提案は恒久的に拒否 "
                     "(この actor は operations coordination のみで roadworthiness "
                     "authority を持たない): key-hit=" key-hit
                     " phrase-hit=" (boolean phrase-hit))}])))

(defn check
  "Censors an AutoRepair-LLM proposal against the governor tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :hard? bool
    :flag? bool :over-threshold? bool}."
  [request context proposal st]
  (let [hard (into []
                   (concat (rbac-violations request context)
                           (registration-gate-violations request proposal st)
                           (effect-propose-only-violations proposal)
                           (closed-allowlist-violations request)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        flag? (= :flag-safety-concern (:op request))
        cost (get-in proposal [:value :cost])
        over-threshold? (and (= :coordinate-parts-order (:op request))
                             (number? cost) (> cost parts-order-cost-threshold))
        hard? (boolean (seq hard))]
    {:ok?             (and (not hard?) (not low?) (not flag?) (not over-threshold?))
     :violations      hard
     :confidence      conf
     :hard?           hard?
     :escalate?       (and (not hard?) (or low? flag? over-threshold?))
     :flag?           flag?
     :over-threshold? over-threshold?}))

(defn hold-fact
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
