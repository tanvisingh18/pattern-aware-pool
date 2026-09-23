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
| Window-start undercounted (exceptions only) + clock rewind; reuse latency charged physical MS; uneven window lengths | UF failures = `metrics.userFacingConnectFailures()` delta in [14:00,14:05); day-8 chronological + monotonic-clock assert; reuse hits = 0 ms; morning/evening/pattern-shift 30 min @ 1/18s (100); paper headline = window-start vs CB | `ReuseLatencyTest` + ExperimentRunner n=30 | *(this change)* |

## Before vs after (bad-window user-facing connect failures / 100 req)

| Source | Reactive | Circuit breaker | Predictive |
|---|---:|---:|---:|
| **Before (seeded-history targets)** — see note | 83 (old claim) | *(not measured)* | 3 (old claim) |
| **After (measured n=30)** — `experiment_summary.csv` | **85.6±3.7** | **3.8±1.5** | **0.3±0.7** |

Notes:
- Before numbers are old seeded-history demo targets, not a learn-then-measure protocol. `before_fixes.csv` is now a pointer to this file.
- After numbers are measured mean±sd over Random seeds 1..30 (do not hand-edit).

### Headline: window-start user-facing failures (14:00–14:05, n=30)

| Mode | User-facing failures |
|---|---:|
| Reactive | **51.1±2.7** (~0.85 × 60 ≈ 51 expected) |
| Circuit breaker | **8.0±2.2** |
| Predictive | **0.8±1.1** |

### Other after metrics (bad-window, predictive, n=30)

- Preemptive failovers: **99.7±0.8**
- Warm hits: **99.7±0.8**
- Recovery probes ok/fail: **0.5±0.8 / 3.5±0.8**
- Background pre-warm connects: **98.7±0.9**
- Primary connect attempts: **0.0±0.2**
- Success rate: **100.0%±0.0**

### Additional measured scenarios (predictive, n=30)

| Scenario | User-facing failures / notes |
|---|---|
| pattern-shift-h14 backup selections | **33.5±13.5** (learned hour 14; day 8 hour 14 healthier) |
| pattern-shift-h15 failures | **3.5±2.0** (day 8 bad hour moved to 15) |
| reuse-off physical connects / mean latency | **999.9±0.3** / **20.00±0.01** ms |
| reuse-on physical connects / mean latency | **1.0±0.0** / **0.02±0.00** ms (idle-pool reuse = 0 ms) |
| morning-healthy backup share | **29.3±9.7** (pred) vs **35.6±10.9** (CB) |
| morning-healthy UF failures | **9.7±2.3** (pred) vs **16.1±4.2** (CB) |

### Window lengths (stated in summary + paper)

- morning-healthy, evening-stable, pattern-shift-h14/h15: **30 min @ 1 req / 18 s** (100 requests)
- bad-window: **100 requests @ 1 req / 1 s** (unchanged)
- window-start: **13:55–14:10 @ 1 req / 5 s**; UF failures = metrics delta in **14:00–14:05**
- reuse-on/off: **1000 requests @ 1 req / 1 s** in a healthy hour

## `./mvnw test` summary (after experiment fixes)

```
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(JDK 17+ / Temurin 25; Maven Wrapper 3.9.9; includes `ReuseLatencyTest`)

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
