(ns autorepair.llm
  "AutoRepair-LLM client — the *contained intelligence node*.

  It normalizes incoming repair-shop coordination data and drafts
  proposals for the four allowed ops: logging a service record (parts
  used / labor hours), proposing a bay/technician scheduling slot,
  flagging a safety concern, and proposing a parts-procurement order.
  CRITICAL: it is a smart-but-untrusted advisor — it returns a
  *proposal*, never a committed record, and it has NO authority over
  roadworthiness. Every output is censored downstream by
  `autorepair.governor` (the AutoRepairGovernor) before anything touches
  the SSoT.

  Every proposal this advisor emits carries `:effect :propose` — never a
  real-world action verb — by construction (see `autorepair.governor`'s
  `:effect-propose-only` gate).

  Deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end. In production this calls a real LLM
  (kotoba-llm) with the same proposal shape.

  Request flags (all optional, all default false):
    :low-confidence?            — forces a below-floor confidence, to
                                  exercise the confidence-floor escalate
                                  path.
    :attempt-roadworthiness-finalize? — RED-TEAM ONLY. Injects a
                                  finalization-action phrase into the
                                  rationale AND a forbidden :value key,
                                  simulating a compromised/off-spec
                                  advisor trying to slip a roadworthiness-
                                  clearance decision past the governor
                                  through an otherwise-legitimate op. Real
                                  production traffic should never set
                                  this; it exists purely so
                                  `autorepair.governor-contract-test` can
                                  prove the scope-exclusion gate holds
                                  end-to-end through the whole graph, not
                                  just at the governor-unit level.

  Proposal shape (all kinds):
    {:summary str :rationale str :cites [kw ..] :source nil
     :effect :propose :value map :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.model :as model]))

(defn- with-red-team-finalize [proposal attempt?]
  (if-not attempt?
    proposal
    (-> proposal
        (update :rationale str " (red-team probe: this proposal also attempts to "
                "finalize the roadworthiness clearance for this vehicle.)")
        (update :value assoc :roadworthiness-status :cleared))))

(defn- confidence-for [op {:keys [low-confidence?]}]
  (if low-confidence? 0.4 (case op
                            :log-service-record 0.9
                            :schedule-service-operation 0.9
                            :flag-safety-concern 0.7
                            :coordinate-parts-order 0.85
                            0.0)))

(defn- propose-log-service-record
  [{:keys [order-id parts-used labor-hours technician
           attempt-roadworthiness-finalize?] :as req}]
  (with-red-team-finalize
    {:summary   (str "service log: " order-id)
     :rationale "整備士が申告した部品使用/工数の記録提案。実施内容の是非判断は行わない。"
     :cites     [:order-id :parts-used :labor-hours]
     :source    nil
     :effect    :propose
     :value     {:order-id order-id :parts-used parts-used
                 :labor-hours labor-hours :technician technician}
     :confidence (confidence-for :log-service-record req)}
    attempt-roadworthiness-finalize?))

(defn- propose-schedule-service-operation
  [{:keys [order-id bay technician start end
           attempt-roadworthiness-finalize?] :as req}]
  (with-red-team-finalize
    {:summary   (str "schedule proposal: " order-id)
     :rationale "ベイ/整備士の空き状況に基づく作業枠の提案。確定は governor と人間の承認による。"
     :cites     [:order-id :bay :technician]
     :source    nil
     :effect    :propose
     :value     {:order-id order-id :bay bay :technician technician
                 :start start :end end}
     :confidence (confidence-for :schedule-service-operation req)}
    attempt-roadworthiness-finalize?))

(defn- propose-flag-safety-concern
  [{:keys [order-id concern severity
           attempt-roadworthiness-finalize?] :as req}]
  (with-red-team-finalize
    {:summary   (str "safety concern flagged: " order-id)
     :rationale (str "技術者が検出した安全上の懸念を人間レビューへ提起する。修理の実施や"
                     "走行可否・整備完了の判断はこの actor の責務ではなく、常に人間が行う。")
     :cites     [:order-id :concern]
     :source    nil
     :effect    :propose
     :value     {:order-id order-id :concern concern :severity severity}
     :confidence (confidence-for :flag-safety-concern req)}
    attempt-roadworthiness-finalize?))

(defn- propose-coordinate-parts-order
  [{:keys [order-id parts cost vendor
           attempt-roadworthiness-finalize?] :as req}]
  (with-red-team-finalize
    {:summary   (str "parts order proposal: " order-id)
     :rationale "サービスログに基づく部品調達の提案。発注確定は governor/人間承認による。"
     :cites     [:order-id :parts :cost]
     :source    nil
     :effect    :propose
     :value     {:order-id order-id :parts parts :cost cost :vendor vendor}
     :confidence (confidence-for :coordinate-parts-order req)}
    attempt-roadworthiness-finalize?))

(defn infer
  [{:keys [op] :as request}]
  (case op
    :log-service-record         (propose-log-service-record request)
    :schedule-service-operation (propose-schedule-service-operation request)
    :flag-safety-concern        (propose-flag-safety-concern request)
    :coordinate-parts-order     (propose-coordinate-parts-order request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :value nil :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  [] (reify Advisor (-advise [_ _st req] (infer req))))

(def ^:private system-prompt
  (str "あなたは自動車整備工場のオペレーション調整アドバイザーです。与えられた"
       "事実のみに基づき、提案を1つだけ EDN マップで返します。説明や前置きは"
       "一切書かず、EDN だけを出力します。\n"
       "キー: :summary :rationale :cites :source(nilも可) :effect(常に "
       ":propose) :value :confidence(0..1)。\n"
       "重要: あなたは repair-order/shop-license の登録状態を検証する権限を"
       "持ちません(governor が判定します)。あなたは車両の走行可否や整備完了"
       "の最終判断(roadworthiness clearance)を絶対に確定してはいけません — "
       "それは常に governor が構造的にブロックし、人間にも委譲されません。"
       "安全上の懸念に気づいた場合は :flag-safety-concern を提案するだけに"
       "留め、その懸念の是非判断は述べないでください。"))

(defn- parse-proposal
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :value nil :confidence 0.0})))

(defn llm-advisor
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ _st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n入力: " (pr-str (dissoc req :op :subject)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace [request proposal]
  {:t          :autorepairllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
