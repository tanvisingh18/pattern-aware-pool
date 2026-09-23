# FIXES.md — Audit → Fix → Test → Commit

Post-audit remediation log for Pattern-Aware Pool (`com.college.pap`).
Review-1 artefacts (including the 21-paper survey) are **left untouched**.

## Fix table

| Audit finding | Fix | Test | Commit |
|---|---|---|---|
| RMI retune did not drive live routing/scoring | Volatile `PoolConfig`; PredictionEngine/RoutingDecider/PreWarmer read live suppliers | `LiveConfigTest` | `182868b` (phases 1–4) |
| Hot-hour never fired from TOD alone | `HotHourRules` + plain empirical rate for hot-hour gating | `HotHourFailoverTest` | `182868b`, refined in `50b0762` |
| No recovery after primary avoided | `BackupPreWarmer` recovery probes while avoided / cluster-sticky | `RecoveryProbeTest` | `182868b`, `50b0762` |
| Pool was not a real pool (no reuse) | `IdleConnectionPool`; `PapConnection.close()` returns handles | `IdleConnectionPoolTest` | `182868b` |
| Reset pattern claimed but unimplemented | `ResetPatternDetector` + `Trigger.RESET_PREDICTED` (one-shot) | `ResetPatternDetectorTest` | `9d23def` |
| Experiments seeded day-8 answers; no CB baseline; sleep latency | `ExperimentRunner`: 7-day learn, day-8 measure, REACTIVE / CIRCUIT_BREAKER / PREDICTIVE, seeded `Random`, simulated latency | manual run n=30 + `mvn test` | `50b0762` |
| Demos recomputed routing decisions | Print `PapConnection.routingDecision()` | demo smoke | `c2a7563` |
| Paper hardcoded 83→3; claimed JavaFX/Spring | `generate_review2_paper.py` reads `experiment_summary.csv`; Swing + Spring-compatible lifecycle wording; threats section | paper regen | `75fa057` |
| Machine-specific README | Portable `JAVA_HOME` + Maven docs | — | `c2a7563` |
| JDBC pool unsafe (open tx, closed proxy, blocking borrow, validate under lock) | Release rollback + restore defaults; closed proxy; `borrowTimeoutMillis`/`validationIdleMillis`; validate outside lock; credential `getConnection` unsupported | `PoolSafetyTest` (6) | `f926b76` |
| Hourly learning depended on ring buffer; reuse recorded as attempts | Remove `ensureCountersFromHistory`; hourly stats only via `observe()`; inject clock for `computedAt`; skip reuse in `acquireAndRecord` | `PatternAnalyzerTest.hourlyLearningSurvivesRingBufferEviction` | `8816262` |
| Hot-hour gating used lifetime plain rate | Gate on EWMA after ≥ hotHourMinSamples; plain rate display-only | `HotHourEwmaCooldownTest` | `843e9a3` |
| Experiments: recovery=5s; ticks predictive-only; curated before_fixes; missing scenarios | recoveryProbeSeconds=30; backgroundTick all modes; CSV columns for probes/prewarm/physical/user-facing; window-start, pattern-shift, reuse; before_fixes→FIXES.md | ExperimentRunner n=30 | `1757e40` |
| Paper used audit/honest/curated wording; inconsistent sections; missing scenarios | Strip forbidden wording; Roman I–VIII; user-facing failures + probe/prewarm columns; window-start / pattern-shift / reuse; healthy-hour backup share | paper regen from CSV | `1fba801` |
| No Maven wrapper; README pointed at `../.tools`; demo lacked recovery/PooledDataSource; RMI incomplete | `./mvnw`; README uses wrapper; RealJdbcDeployDemo recovery + SESSION_ID reuse; RMI hotHourThreshold/clusterAvoidRun/recoveryProbeSeconds; real RMI test + unexport | `PoolManagementServiceTest` + `./mvnw test` | `193b7bf` |

## Before vs after (bad-window user-facing connect failures / 100 req)

| Source | Reactive | Circuit breaker | Predictive |
|---|---:|---:|---:|
| **Before (seeded-history targets)** — see note | 83 (old claim) | *(not measured)* | 3 (old claim) |
| **After (measured n=30)** — `experiment_summary.csv` | **85.9±4.0** | **3.2±1.1** | **0.8±0.7** |

Notes:
- Before numbers are old seeded-history demo targets, not a learn-then-measure protocol. `before_fixes.csv` is now a pointer to this file.
- After numbers are measured mean±sd over Random seeds 1..30 (do not hand-edit).

### Other after metrics (bad-window, predictive, n=30)

- Preemptive failovers: **99.2±0.8**
- Warm hits: **99.2±0.8**
- Recovery probes ok/fail: **0.6±0.7 / 3.4±0.7**
- Background pre-warm connects: **98.5±0.7**
- Primary connect attempts: **0.0±0.2**
- Success rate: **100.0%±0.0**

### Additional measured scenarios (predictive, n=30)

| Scenario | User-facing failures | Notes |
|---|---:|---|
| window-start (14:00–14:05 slice) | **0.0±0.0** | 13:55–14:10 @ 1 req/5s |
| pattern-shift-h14 backup share | **74.8±14.2** selections | learned hour 14; day 8 hour 14 healthy |
| pattern-shift-h15 failures | **0.7±0.8** | day 8 bad hour moved to 15 |
| reuse-off physical connects | **999.9±0.3** | 1000 healthy-hour requests |
| reuse-on physical connects | **1.0±0.0** | same load with reuse |
| morning-healthy backup share | **80.5±14.0** (pred) vs **88.8±12.1** (CB) | CB stays open longer after bursts |

## `./mvnw test` summary (after items 5–6)

```
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(JDK 17+ / Temurin 25; Maven Wrapper 3.9.9)

## Remaining limitations

1. Evaluation uses `FlakyEndpointConnector`, not production WAN traces.
2. Predictive over-avoidance after bursts is mitigated by recovery probes but not eliminated.
3. Scoring still uses EWMA time-of-day; hot-hour gating uses EWMA (plain rate display-only).
4. Review-2 cites fewer papers than Review-1’s 21 — intentional scope; Review-1 unchanged.
5. Multi-region / multi-driver production matrices are future work.

## How to reproduce

```bash
cd pattern-aware-pool
export JAVA_HOME=...(JDK 17+)
./mvnw test
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.ExperimentRunner
# optional: ../.venv/bin/python docs/generate_review2_paper.py
```
