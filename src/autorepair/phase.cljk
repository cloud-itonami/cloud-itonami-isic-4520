(ns autorepair.phase
  "Phase 0→3 staged rollout — start narrow, widen as trust grows. Where the
  AutoRepairGovernor answers 'is this allowed?', the phase answers 'how
  much autonomy does the actor have *yet*?'. It can only ever make the
  actor MORE conservative than the governor, never less.

    Phase 0  disabled          — no op may proceed (there is no read-only
                                 query op in this domain — every one of the
                                 four ops proposes a write to the
                                 coordination log).
    Phase 1  logging-only      — `:log-service-record` allowed, every entry
                                 still needs human approval.
    Phase 2  full-coordination — adds `:schedule-service-operation` and
                                 `:coordinate-parts-order` (still
                                 approval-only) and `:flag-safety-concern`.
    Phase 3  supervised-auto   — governor-clean, high-confidence
                                 `:log-service-record`/
                                 `:schedule-service-operation`/
                                 `:coordinate-parts-order` may auto-commit.

  `:flag-safety-concern` is deliberately NEVER a member of ANY phase's
  `:auto` set, at any phase — surfacing a safety concern always reaches a
  human, full stop. (`autorepair.governor/check`'s `:flag?` already forces
  `:escalate?` independent of phase; this is a second, structural line of
  defense against the same requirement, matching the same
  never-auto-commit posture the governor takes for
  `:roadworthiness-clearance-scope-exclusion` and for every hard
  violation.)")

(def all-ops
  #{:log-service-record :schedule-service-operation
    :flag-safety-concern :coordinate-parts-order})

(def phases
  {0 {:label "disabled"           :writes #{}
                                    :auto #{}}
   1 {:label "logging-only"       :writes #{:log-service-record}
                                    :auto #{}}
   2 {:label "full-coordination"  :writes all-ops
                                    :auto #{}}
   3 {:label "supervised-auto"    :writes all-ops
                                    :auto #{:log-service-record
                                            :schedule-service-operation
                                            :coordinate-parts-order}}})

(def default-phase
  "The phase used when `context` carries no :phase at all, AND the fallback
  `gate` itself uses for an unrecognized phase number. This is directly
  reachable by any ordinary caller that simply omits :phase, so it must be
  the MOST CONSERVATIVE phase, never the most permissive (the same
  fail-open bug already found and fixed across `cloud-itonami-isic-6311`/
  `-7820`/`-4510` and the shared `talent.phase` template — never shipped
  here in the first place)."
  1)

(defn gate
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
