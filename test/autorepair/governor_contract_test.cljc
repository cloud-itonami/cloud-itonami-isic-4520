(ns autorepair.governor-contract-test
  "The governor contract as executable tests. The single invariant under
  test: AutoRepair-LLM never logs/schedules/flags/coordinates a record the
  AutoRepairGovernor would reject, and every decision (commit OR hold)
  leaves exactly one ledger fact.

  Also carries the dedicated regression test for the self-tripping
  scope-exclusion bug class this exact actor family has repeatedly hit:
  `default-advisor-proposals-never-self-trip-scope-exclusion`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [autorepair.store :as store]
            [autorepair.governor :as governor]
            [autorepair.llm :as llm]
            [autorepair.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def writer-p3  {:actor-id "sw-1" :actor-role :service-writer :phase 3})
(def tech-p3    {:actor-id "tc-1" :actor-role :technician :phase 3})
(def manager-p3 {:actor-id "mg-1" :actor-role :shop-manager :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-log-service-record-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-service-record :subject "ro-100" :order-id "ro-100"
                   :parts-used ["brake-pad-set"] :labor-hours 1.5 :technician "tc-1"}
                  writer-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/service-log db))))
    (is (= 1 (count (store/ledger db))))))

(deftest authorized-schedule-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t2"
                  {:op :schedule-service-operation :subject "ro-100" :order-id "ro-100"
                   :bay "bay-2" :technician "tc-1" :start "t0" :end "t1"}
                  writer-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/schedule-log db))))))

(deftest unauthorized-role-is-held
  (testing "technician has no permission for :coordinate-parts-order"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :coordinate-parts-order :subject "ro-100" :order-id "ro-100"
                     :parts ["oil-filter"] :cost 40.00M :vendor "Demo Parts Co"}
                    tech-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= [:rbac] (-> (store/ledger db) first :basis)))
      (is (empty? (store/parts-orders db))))))

(deftest unregistered-repair-order-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4"
                  {:op :log-service-record :subject "ro-999" :order-id "ro-999"
                   :parts-used [] :labor-hours 0.5 :technician "tc-1"}
                  writer-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:registration-gate} (-> (store/ledger db) first :basis)))
    (is (empty? (store/service-log db)))))

(deftest inactive-shop-license-is-held
  (testing "ro-200 belongs to shop-2, whose license has lapsed"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :log-service-record :subject "ro-200" :order-id "ro-200"
                     :parts-used ["oil-filter"] :labor-hours 0.5 :technician "tc-1"}
                    writer-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:registration-gate} (-> (store/ledger db) first :basis))))))

(deftest flag-safety-concern-always-escalates-even-at-highest-phase
  (testing "escalates every time, at phase 3, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :flag-safety-concern :subject "ro-100" :order-id "ro-100"
                     :concern "ブレーキラインの腐食を発見" :severity :high}
                    tech-p3)]
      (is (= :interrupted (:status res)))
      (is (= :safety-concern-flagged (-> res :state :audit last :reason)))
      (testing "approve -> commit, entry recorded"
        (let [r2 (g/run* actor {:approval {:status :approved :by "mg-1"}}
                         {:thread-id "t6" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/safety-flags db))))))))
  (testing "reject -> hold, nothing recorded"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7"
                  {:op :flag-safety-concern :subject "ro-100" :order-id "ro-100"
                   :concern "demo" :severity :low}
                  tech-p3)
          r2 (g/run* actor {:approval {:status :rejected :by "mg-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/safety-flags db))))))

(deftest parts-order-under-threshold-auto-commits-at-phase3
  (let [[db actor] (fresh)
        res (exec-op actor "t8"
                  {:op :coordinate-parts-order :subject "ro-100" :order-id "ro-100"
                   :parts ["brake-pad-set"] :cost 250.00M :vendor "Demo Parts Co"}
                  writer-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/parts-orders db))))))

(deftest parts-order-over-threshold-escalates
  (let [[db actor] (fresh)
        res (exec-op actor "t9"
                  {:op :coordinate-parts-order :subject "ro-300" :order-id "ro-300"
                   :parts ["engine-block"] :cost 4200.00M :vendor "Demo Parts Co"}
                  writer-p3)]
    (is (= :interrupted (:status res)))
    (is (= :parts-order-over-threshold (-> res :state :audit last :reason)))
    (let [r2 (g/run* actor {:approval {:status :approved :by "mg-1"}}
                     {:thread-id "t9" :resume? true})]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 1 (count (store/parts-orders db)))))))

(deftest low-confidence-escalates
  (let [[db actor] (fresh)
        res (exec-op actor "t10"
                  {:op :log-service-record :subject "ro-100" :order-id "ro-100"
                   :parts-used ["oil-filter"] :labor-hours 0.5 :technician "tc-1"
                   :low-confidence? true}
                  writer-p3)]
    (is (= :interrupted (:status res)))
    (is (= :low-confidence (-> res :state :audit last :reason)))
    (let [r2 (g/run* actor {:approval {:status :approved :by "mg-1"}}
                     {:thread-id "t10" :resume? true})]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 1 (count (store/service-log db)))))))

(deftest roadworthiness-finalize-attempt-is-hard-blocked-and-unoverridable
  (testing (str "attempting to finalize a roadworthiness clearance through ANY "
               "otherwise-legitimate op is a hard, permanent block -- it never "
               "reaches human approval, at any phase")
    (let [[db actor] (fresh)
          res (exec-op actor "t11"
                    {:op :log-service-record :subject "ro-100" :order-id "ro-100"
                     :parts-used ["brake-pad-set"] :labor-hours 1.5 :technician "tc-1"
                     :attempt-roadworthiness-finalize? true}
                    writer-p3)]
      (is (not= :interrupted (:status res)) "hard violation never reaches request-approval")
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:roadworthiness-clearance-scope-exclusion} (-> (store/ledger db) first :basis)))
      (is (empty? (store/service-log db)) "nothing committed"))))

(deftest roadworthiness-finalize-attempt-blocked-on-every-op
  (testing "the scope-exclusion gate applies uniformly to all four ops, not just log-service-record"
    (doseq [req [{:op :schedule-service-operation :subject "ro-100" :order-id "ro-100"
                  :bay "bay-1" :technician "tc-1" :start "t0" :end "t1"
                  :attempt-roadworthiness-finalize? true}
                 {:op :flag-safety-concern :subject "ro-100" :order-id "ro-100"
                  :concern "demo" :severity :low
                  :attempt-roadworthiness-finalize? true}
                 {:op :coordinate-parts-order :subject "ro-100" :order-id "ro-100"
                  :parts ["oil-filter"] :cost 40.00M :vendor "Demo Parts Co"
                  :attempt-roadworthiness-finalize? true}]]
      (let [[db actor] (fresh)
            res (exec-op actor (str "rt-" (:op req)) req writer-p3)]
        (is (= :hold (get-in res [:state :disposition])) (str (:op req) " must hard-hold"))
        (is (some #{:roadworthiness-clearance-scope-exclusion}
                  (-> (store/ledger db) first :basis))
            (str (:op req) " must cite scope-exclusion"))))))

(deftest default-advisor-proposals-never-self-trip-scope-exclusion
  (testing (str "regression guard: the mock advisor's own DEFAULT (non-red-team) "
               "proposals for all four ops -- including :flag-safety-concern, whose "
               "legitimate rationale necessarily mentions \"安全\"/\"safety\" as a "
               "bare noun -- must never trip :roadworthiness-clearance-scope-exclusion. "
               "A bare-noun-phrased exclusion term would self-block this actor's own "
               "happy path; see autorepair.governor's namespace docstring.")
    (let [db (store/seed-db)
          requests [{:op :log-service-record :subject "ro-100" :order-id "ro-100"
                     :parts-used ["brake-pad-set" "brake-fluid"] :labor-hours 1.5
                     :technician "tc-1"}
                    {:op :schedule-service-operation :subject "ro-100" :order-id "ro-100"
                     :bay "bay-2" :technician "tc-1" :start "2026-07-16T09:00"
                     :end "2026-07-16T11:00"}
                    ;; deliberately mentions "安全性"/"走行可否" as bare descriptive
                    ;; nouns, NOT as a finalize/certify/confirm action phrase --
                    ;; exactly the near-miss shape that would trip a bare-noun-
                    ;; phrased exclusion term.
                    {:op :flag-safety-concern :subject "ro-100" :order-id "ro-100"
                     :concern (str "ブレーキラインの腐食を発見。整備士は走行の安全性に"
                                  "懸念があると判断したが、走行可否の最終的な判断は行って"
                                  "いない。")
                     :severity :high}
                    {:op :coordinate-parts-order :subject "ro-100" :order-id "ro-100"
                     :parts ["brake-pad-set"] :cost 250.00M :vendor "Demo Parts Co"}]]
      (doseq [req requests]
        (let [proposal (llm/infer req)
              verdict (governor/check req manager-p3 proposal db)]
          (is (not (some #(= :roadworthiness-clearance-scope-exclusion (:rule %))
                        (:violations verdict)))
              (str "op " (:op req) " self-tripped the scope-exclusion gate: "
                   (pr-str (:violations verdict)))))))))

(deftest every-decision-leaves-one-ledger-fact
  (let [[db actor] (fresh)]
    (exec-op actor "a" {:op :log-service-record :subject "ro-100" :order-id "ro-100"
                        :parts-used ["oil-filter"] :labor-hours 0.5 :technician "tc-1"}
             writer-p3)
    (exec-op actor "b" {:op :log-service-record :subject "ro-999" :order-id "ro-999"
                        :parts-used [] :labor-hours 0.0 :technician "tc-1"}
             writer-p3)
    (is (= 2 (count (store/ledger db))))))
