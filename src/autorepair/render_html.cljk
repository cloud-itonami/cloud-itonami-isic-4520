(ns autorepair.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was no demo page and no generator at all.

  EVERY id, number, status, escalation reason and hold rule on the
  generated page is REAL output of this repo's own actor stack, produced
  at build time by actually executing it:

    `autorepair.store/seed-db`   -> the SSoT and its seeded subjects
    `autorepair.operation/build` -> the compiled langgraph StateGraph
    `langgraph.graph/run*`       -> one real graph run per operation
    `autorepair.governor/check`  -> the verdicts (rules, violations, text)
    `autorepair.phase/gate`      -> the rollout-phase dispositions

  Nothing on the page is hand-typed prose about behaviour: the op/role/
  phase gate table and the phase ladder are DERIVED from the live values
  of `autorepair.governor/permissions`, `autorepair.governor/allowed-ops`
  and `autorepair.phase/phases`, so the page cannot drift away from the
  code. The subject ids are read back through the `Store` protocol
  (`store/repair-order` / `store/shop-license`) against the ids that
  `store/demo-data` actually seeds -- confirmed against
  `clojure -M:dev:run` BEFORE this file was written, so no id in the page
  is one the store lacks.

  DETERMINISTIC: the advisor is the deterministic mock, no timestamps or
  randomness reach the page, and every unordered collection read out of
  the code (sets, maps) is explicitly sorted before rendering. Two
  consecutive runs are byte-identical -- verify with
  `clojure -M:dev:render-html && cp docs/samples/operator-console.html /tmp/a
   && clojure -M:dev:render-html && diff /tmp/a docs/samples/operator-console.html`.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [autorepair.governor :as governor]
            [autorepair.operation :as op]
            [autorepair.phase :as phase]
            [autorepair.store :as store]))

;; ───────────────────────── real actor execution ─────────────────────────

(def ^:private writer-p3
  {:actor-id "sw-1" :actor-role :service-writer :phase 3})

(def ^:private writer-p1
  {:actor-id "sw-1" :actor-role :service-writer :phase 1})

(def ^:private tech-p3
  {:actor-id "tc-1" :actor-role :technician :phase 3})

(defn- exec!
  "Runs ONE operation through the REAL compiled actor graph and records
  the real result into `log`. `approve` is `:approved`, `:rejected` or
  `nil`; it is only ever used when the graph actually interrupted, so a
  HARD governor violation can never be `approve`d here -- it never
  reaches the `:request-approval` node in the first place."
  [actor log tid label request context approve]
  (let [r1 (g/run* actor {:request request :context context} {:thread-id tid})
        reached-human? (= :interrupted (:status r1))
        r2 (when reached-human?
             (g/run* actor {:approval {:status approve :by "manager-1"}}
                     {:thread-id tid :resume? true}))
        final (or r2 r1)
        state (:state final)]
    (swap! log conj
           {:id tid
            :label label
            :op (:op request)
            :subject (:subject request)
            :role (:actor-role context)
            :phase (:phase context)
            :reached-human? reached-human?
            :approval (when reached-human? approve)
            :escalation-reason (when reached-human?
                                 (->> (:audit (:state r1))
                                      (filter #(= :approval-requested (:t %)))
                                      last
                                      :reason))
            :disposition (:disposition state)
            :confidence (get-in state [:verdict :confidence])
            :hard? (boolean (get-in state [:verdict :hard?]))
            :violations (vec (get-in state [:verdict :violations]))
            :phase-reason (->> (:audit state) (keep :phase-reason) last)})
    final))

(defn run-demo!
  "Seeds a fresh store, builds the REAL OperationActor and drives eleven
  operations through it. Returns `{:db store :timeline [..]}` where the
  timeline entries are the real `langgraph.graph/run*` results (status,
  disposition, verdict, audit), not a description of them.

  The scenario covers every disposition this actor can reach:

    ONE FULL CLEAN LIFECYCLE -- `ro-100` (shop-1, ACTIVE licence) passes
    all four allowed ops and lands all four in the SSoT: a service record
    and a scheduling slot auto-commit at phase 3, a below-threshold parts
    order auto-commits, and a safety-concern flag escalates to a human
    (it is never auto at ANY phase) and commits on approval.

    HUMAN-REVIEW PATHS -- a 4200.00 parts order on `ro-300` is over
    `governor/parts-order-cost-threshold` so it escalates and is
    APPROVED; a deliberately low-confidence service record on `ro-300`
    escalates under `governor/confidence-floor` and is REJECTED, landing
    an `:approval-rejected` fact in the ledger.

    HARD GOVERNOR HOLDS -- four proposals are rejected outright and NONE
    of them ever reaches the human-review node:
      * `:roadworthiness-clearance-scope-exclusion` -- a red-team probe
        (`:attempt-roadworthiness-finalize?`) on `ro-300` that tries to
        smuggle a roadworthiness clearance through an otherwise
        legitimate `:log-service-record`. This is the actor's signature
        permanent block: there is no override path, by construction.
      * `:registration-gate` x2 -- `ro-999` is not registered at all, and
        `ro-200` sits at `shop-2` whose licence has lapsed.
      * `:rbac` -- a `:technician` proposing a parts order.

    PHASE GATE -- the same scheduling op that commits at phase 3 is held
    with `:phase-disabled` when the caller is still at phase 1."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        log (atom [])]

    ;; ro-100 — full clean lifecycle across all four allowed ops.
    (exec! actor log "lc-1" "サービス記録ログ (phase 3, 正常)"
           {:op :log-service-record :subject "ro-100" :order-id "ro-100"
            :parts-used ["brake-pad-set"] :labor-hours 1.5 :technician "tc-1"}
           writer-p3 nil)

    (exec! actor log "lc-2" "作業枠スケジューリング (phase 3, 正常)"
           {:op :schedule-service-operation :subject "ro-100" :order-id "ro-100"
            :bay "bay-2" :technician "tc-1"
            :start "2026-07-16T09:00" :end "2026-07-16T11:00"}
           writer-p3 nil)

    ;; …the identical op one phase earlier: the phase axis, not the governor.
    (exec! actor log "ph-1" "同じスケジューリング提案を phase 1 で"
           {:op :schedule-service-operation :subject "ro-100" :order-id "ro-100"
            :bay "bay-2" :technician "tc-1"
            :start "2026-07-16T09:00" :end "2026-07-16T11:00"}
           writer-p1 nil)

    (exec! actor log "lc-3" "閾値以下の部品発注"
           {:op :coordinate-parts-order :subject "ro-100" :order-id "ro-100"
            :parts ["brake-pad-set"] :cost 250.00M
            :vendor "Demo Parts Co (fictitious)"}
           writer-p3 nil)

    (exec! actor log "lc-4" "安全上の懸念のフラグ (常に人間レビュー)"
           {:op :flag-safety-concern :subject "ro-100" :order-id "ro-100"
            :concern "ブレーキラインの腐食を発見" :severity :high}
           tech-p3 :approved)

    ;; ro-300 — the two human-review paths.
    (exec! actor log "esc-1" "閾値超過の部品発注 → 承認"
           {:op :coordinate-parts-order :subject "ro-300" :order-id "ro-300"
            :parts ["engine-block"] :cost 4200.00M
            :vendor "Demo Parts Co (fictitious)"}
           writer-p3 :approved)

    (exec! actor log "esc-2" "confidence floor 未満のログ → 却下"
           {:op :log-service-record :subject "ro-300" :order-id "ro-300"
            :parts-used ["oil-filter"] :labor-hours 0.5 :technician "tc-1"
            :low-confidence? true}
           writer-p3 :rejected)

    ;; HARD holds — none of these reaches :request-approval.
    (exec! actor log "hard-1" "roadworthiness clearance 確定の red-team 試行"
           {:op :log-service-record :subject "ro-300" :order-id "ro-300"
            :parts-used ["brake-pad-set"] :labor-hours 2.0 :technician "tc-1"
            :attempt-roadworthiness-finalize? true}
           writer-p3 nil)

    (exec! actor log "hard-2" "未登録の repair-order へのログ試行"
           {:op :log-service-record :subject "ro-999" :order-id "ro-999"
            :parts-used [] :labor-hours 0.5 :technician "tc-1"}
           writer-p3 nil)

    (exec! actor log "hard-3" "licence が失効した shop の repair-order"
           {:op :log-service-record :subject "ro-200" :order-id "ro-200"
            :parts-used ["oil-filter"] :labor-hours 0.5 :technician "tc-1"}
           writer-p3 nil)

    (exec! actor log "hard-4" "権限の無い role からの部品発注"
           {:op :coordinate-parts-order :subject "ro-100" :order-id "ro-100"
            :parts ["oil-filter"] :cost 40.00M
            :vendor "Demo Parts Co (fictitious)"}
           tech-p3 nil)

    {:db db :timeline @log}))

;; ───────────────────────── derivations from live code ────────────────────
;; Sorted explicitly: `allowed-ops` and the permission value sets are
;; hash sets, so their natural order is not a rendering order.

(def ^:private ops-sorted (vec (sort-by name governor/allowed-ops)))

(def ^:private phase-nums (vec (sort (keys phase/phases))))

(defn- roles-for [op]
  (->> governor/permissions
       (keep (fn [[role ops]] (when (contains? ops op) role)))
       (sort-by name)
       vec))

(defn- write-phases-for [op]
  (vec (filter #(contains? (:writes (get phase/phases %)) op) phase-nums)))

(defn- auto-phases-for [op]
  (vec (filter #(contains? (:auto (get phase/phases %)) op) phase-nums)))

;; ───────────────────────────── rendering ─────────────────────────────────

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-list [xs]
  (if (seq xs) (str/join ", " (map name xs)) "—"))

(defn- num-list [xs]
  (if (seq xs) (str/join ", " xs) "—"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- facts-for [ledger subject]
  (filterv #(= subject (:subject %)) ledger))

(defn- hold-label
  "The rule (or phase reason) a hold fact actually carries -- read off the
  real governor/phase output, never guessed."
  [{:keys [violations phase-reason]}]
  (cond
    (seq violations) (str/join ", " (map (comp name :rule) violations))
    phase-reason (name phase-reason)
    :else "—"))

;; ---- section: registered repair orders (read through the Store protocol)

(defn- order-row [db ledger order-id]
  (let [{:keys [vin shop-id customer]} (store/repair-order db order-id)
        {:keys [provider active?]} (store/shop-license db shop-id)
        facts (facts-for ledger order-id)
        committed (filter #(= :committed (:t %)) facts)
        hard (filter #(and (= :governor-hold (:t %)) (seq (:violations %))) facts)
        other-holds (remove #(or (= :committed (:t %))
                                 (and (= :governor-hold (:t %)) (seq (:violations %))))
                            facts)]
    (str "        <tr><td>" (code order-id) "</td>"
         "<td>" (code vin) "</td>"
         "<td>" (esc customer) "</td>"
         "<td>" (code shop-id) "</td>"
         "<td>" (if active?
                  (str "<span class=\"ok\">active</span> · " (esc provider))
                  (str "<span class=\"critical\">lapsed</span> · " (esc provider)))
         "</td>"
         "<td><span class=\"ok\">" (count committed) "</span>"
         (if (seq committed)
           (str " <span class=\"muted\">(" (esc (kw-list (map :op committed))) ")</span>")
           "")
         "</td>"
         "<td>" (if (seq hard)
                  (str "<span class=\"critical\">" (count hard) "</span>"
                       " <span class=\"muted\">("
                       (esc (str/join ", " (distinct (mapcat #(map (comp name :rule) (:violations %)) hard))))
                       ")</span>")
                  "<span class=\"muted\">0</span>")
         "</td>"
         "<td>" (if (seq other-holds)
                  (str "<span class=\"warn\">" (count other-holds) "</span>"
                       " <span class=\"muted\">("
                       (esc (str/join ", " (distinct (map hold-label other-holds))))
                       ")</span>")
                  "<span class=\"muted\">0</span>")
         "</td></tr>")))

;; ---- section: operation timeline (real run* results)

(defn- disposition-cell [{:keys [disposition hard? approval]}]
  (cond
    hard? "<span class=\"critical\">HARD hold</span>"
    (= :commit disposition) (if (= :approved approval)
                              "<span class=\"ok\">approved &amp; committed</span>"
                              "<span class=\"ok\">auto-committed</span>")
    (= :hold disposition) (if (= :rejected approval)
                            "<span class=\"warn\">rejected by approver → hold</span>"
                            "<span class=\"warn\">hold</span>")
    :else (str "<span class=\"muted\">" (esc (name (or disposition :unknown))) "</span>")))

(defn- reason-cell [{:keys [hard? violations escalation-reason phase-reason]}]
  (cond
    hard? (str "<span class=\"critical\">"
               (esc (str/join ", " (map (comp name :rule) violations)))
               "</span><br><span class=\"muted\">"
               (esc (str/join " / " (map :detail violations)))
               "</span>")
    phase-reason (str "<span class=\"warn\">" (esc (name phase-reason)) "</span>")
    escalation-reason (str "<span class=\"warn\">" (esc (name escalation-reason)) "</span>")
    :else "<span class=\"muted\">—</span>"))

(defn- timeline-row [{:keys [id label op subject role phase reached-human? confidence] :as e}]
  (str "        <tr><td>" (code id) "</td>"
       "<td>" (esc label) "</td>"
       "<td>" (code op) "</td>"
       "<td>" (code subject) "</td>"
       "<td>" (code role) " <span class=\"muted\">/ phase " (esc phase) "</span></td>"
       "<td class=\"num\">" (esc confidence) "</td>"
       "<td>" (if reached-human?
                "<span class=\"warn\">yes</span>"
                "<span class=\"muted\">no</span>") "</td>"
       "<td>" (disposition-cell e) "</td>"
       "<td>" (reason-cell e) "</td></tr>"))

;; ---- section: op / role / phase gate, derived from the live tables

(defn- gate-row [op]
  (let [autos (auto-phases-for op)]
    (str "        <tr><td>" (code op) "</td>"
         "<td>" (esc (kw-list (roles-for op))) "</td>"
         "<td class=\"num\">" (esc (num-list (write-phases-for op))) "</td>"
         "<td>" (if (seq autos)
                  (str "<span class=\"ok\">phase " (esc (num-list autos)) "</span>")
                  "<span class=\"warn\">never · always human</span>")
         "</td></tr>")))

(defn- phase-row [n]
  (let [{:keys [label writes auto]} (get phase/phases n)]
    (str "        <tr><td class=\"num\">" (esc n) "</td>"
         "<td>" (code label) "</td>"
         "<td>" (esc (kw-list (sort-by name writes))) "</td>"
         "<td>" (esc (kw-list (sort-by name auto))) "</td></tr>")))

;; ---- section: committed coordination logs (the SSoT writes)

(defn- log-rows [db]
  (str/join
   "\n"
   (for [[label entries] [["service-log" (store/service-log db)]
                          ["schedule-log" (store/schedule-log db)]
                          ["safety-flags" (store/safety-flags db)]
                          ["parts-orders" (store/parts-orders db)]]]
     (str "        <tr><td>" (code label) "</td>"
          "<td class=\"num\">" (count entries) "</td>"
          "<td>" (if (seq entries)
                   (str/join "<br>" (map #(code (pr-str %)) entries))
                   "<span class=\"muted\">—</span>")
          "</td></tr>"))))

;; ---- section: audit ledger

(defn- ledger-row [{:keys [t op subject disposition violations] :as f}]
  ;; Only a `:governor-hold` that actually carries governor violations is a
  ;; HARD block. A phase hold (empty :violations) and an approver rejection
  ;; (`:approval-rejected`) are held for other reasons and must not be
  ;; painted as if they were un-overridable governor verdicts.
  (let [hard? (and (= :governor-hold t) (seq violations))]
    (str "        <tr><td>" (code t) "</td>"
         "<td>" (code (or op :n-a)) "</td>"
         "<td>" (code subject) "</td>"
         "<td>" (esc (name (or disposition :n-a))) "</td>"
         "<td>" (cond
                  (= :committed t) "<span class=\"muted\">—</span>"
                  hard? (str "<span class=\"critical\">" (esc (hold-label f)) "</span>")
                  :else (str "<span class=\"warn\">" (esc (hold-label f)) "</span>"))
         "</td></tr>")))

(defn render
  "Renders the whole operator-console document from the result of
  `run-demo!` (`{:db store :timeline [..]}`). Both halves are real actor
  output: `:db` is the SSoT the graph actually wrote to, `:timeline` is
  the real `langgraph.graph/run*` results. Every interpolated value is
  HTML-escaped."
  [{:keys [db timeline]}]
  (let [ledger (vec (store/ledger db))
        order-ids (vec (sort (keys (:repair-orders (store/demo-data)))))
        hard-holds (filterv :hard? timeline)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-4520 · motor-vehicle repair · Operator Console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n"
     "<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Maintenance and repair of motor vehicles (ISIC 4520) — Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\"><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">operations coordination only · no roadworthiness authority</span></p>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>How this page was made</h2>\n"
     "    <p>Every id, number, disposition and hold rule below is produced at build time by actually running this repository's actor stack — "
     (code "autorepair.store/seed-db") " → " (code "autorepair.operation/build")
     " → " (code "langgraph.graph/run*") " → " (code "autorepair.governor/check")
     " → " (code "autorepair.phase/gate") ". Regenerate with "
     (code "clojure -M:dev:render-html") ". The gate tables are derived from the live values of "
     (code "autorepair.governor/permissions") ", " (code "autorepair.governor/allowed-ops")
     " and " (code "autorepair.phase/phases") ", so this page cannot drift away from the code. "
     "The dataset is entirely fictitious — no real shop, VIN or licence is asserted here.</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Registered repair orders (SSoT)</h2>\n"
     "    <p class=\"muted\">Read back through the <code>Store</code> protocol. The registration gate never invents either a repair order or an active shop licence — <code>ro-200</code>'s shop licence has lapsed, and <code>ro-999</code> (exercised below) is not registered at all, so it has no row here.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Repair order</th><th>VIN</th><th>Customer</th><th>Shop</th><th>Shop licence</th><th>Committed ops</th><th>HARD holds</th><th>Other holds</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map #(order-row db ledger %) order-ids)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Operation timeline (this run)</h2>\n"
     "    <p class=\"muted\">One row = one real graph run. <strong>Reached human?</strong> is the actual "
     (code ":interrupted") " status of the run — note that all " (esc (count hard-holds))
     " HARD holds answer <em>no</em>: a hard governor violation routes straight to "
     (code ":hold") " and never reaches the " (code ":request-approval") " node, so there is no override path for it, by construction.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Operation</th><th>Op</th><th>Subject</th><th>Role / phase</th><th>Confidence</th><th>Reached human?</th><th>Disposition</th><th>Reason</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map timeline-row timeline)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (AutoRepairGovernor × rollout phase)</h2>\n"
     "    <p class=\"muted\">Derived from " (code "governor/allowed-ops") ", "
     (code "governor/permissions") " and " (code "phase/phases") " — not hand-written. "
     "Confidence floor " "<span class=\"num\">" (esc governor/confidence-floor) "</span>; "
     "parts-order escalation threshold <span class=\"num\">"
     (esc governor/parts-order-cost-threshold) "</span>; "
     (esc (count governor/finalization-action-phrases)) " finalization-action phrases and "
     (esc (count governor/forbidden-clearance-value-keys))
     " forbidden clearance value keys guard the scope exclusion.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Roles permitted</th><th>Phases that may write</th><th>Auto-commit</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map gate-row ops-sorted)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Rollout phase ladder</h2>\n"
     "    <p class=\"muted\">The phase can only ever make the actor more conservative than the governor, never less. Default phase when a caller omits <code>:phase</code> is <span class=\"num\">"
     (esc phase/default-phase) "</span> — deliberately not the most permissive one.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>Label</th><th>May write</th><th>May auto-commit</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map phase-row phase-nums)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Committed coordination records (append-only)</h2>\n"
     "    <p class=\"muted\">What actually landed in the SSoT. Every record carries <code>:effect :propose</code> — a log entry that a proposal was made, never an executed repair action.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Stream</th><th>Entries</th><th>Contents</th></tr></thead>\n"
     "      <tbody>\n"
     (log-rows db) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — " (esc (count ledger))
     " facts: every commit, every hold and every approver rejection this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Disposition</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>Generated by <code>autorepair.render-html</code> from a real actor run — no hand-written page data. "
     "Fictitious demo dataset only.</footer>\n"
     "</body>\n</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db timeline] :as result} (run-demo!)
        html (render result)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out
             (str "(" (count timeline) " operations, "
                  (count (store/ledger db)) " ledger facts, "
                  (count (filter :hard? timeline)) " HARD holds, "
                  (count (filter :reached-human? timeline)) " reached human review)"))))
