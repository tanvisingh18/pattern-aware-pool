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
| Experiments seeded day-8 answers; no CB baseline; sleep latency | Honest `ExperimentRunner`: 7-day learn, day-8 measure, REACTIVE / CIRCUIT_BREAKER / PREDICTIVE, seeded `Random`, simulated latency | manual run n=30 + `mvn test` | `50b0762` |
| Demos recomputed routing decisions | Print `PapConnection.routingDecision()` | demo smoke | `c2a7563` |
| Paper hardcoded 83→3; claimed JavaFX/Spring | `generate_review2_paper.py` reads `experiment_summary.csv`; Swing + Spring-compatible lifecycle wording; threats section | paper regen | `75fa057` |
| Machine-specific README | Portable `JAVA_HOME` + `mvn` docs | — | `c2a7563` |
| JDBC pool unsafe (open tx, closed proxy, blocking borrow, validate under lock) | Release rollback + restore defaults; closed proxy; `borrowTimeoutMillis`/`validationIdleMillis`; validate outside lock; credential `getConnection` unsupported | `PoolSafetyTest` (6) | `f926b76` |
| Hourly learning depended on ring buffer; reuse recorded as attempts | Remove `ensureCountersFromHistory`; hourly stats only via `observe()`; inject clock for `computedAt`; skip reuse in `acquireAndRecord` | `PatternAnalyzerTest.hourlyLearningSurvivesRingBufferEviction` | *(item 2)* |
| Hot-hour gating used lifetime plain rate | Gate on EWMA after ≥ hotHourMinSamples; plain rate display-only | `HotHourEwmaCooldownTest` | *(item 3)* |

## Before vs after (bad-window connect failures / 100 req)

| Source | Reactive | Circuit breaker | Predictive |
|---|---:|---:|---:|
| **Before (audit / seeded-history targets)** — `before_fixes.csv` | 83 (curated claim) | *(not measured)* | 3 (curated claim) |
| **After (honest n=30)** — `experiment_summary.csv` | **85.9±4.0** | **3.2±1.1** | **0.0±0.0** |

Notes:
- Before numbers are audit-reproduced targets from the old seeded-history demo runner, not an honest learn-then-measure protocol.
- After numbers are measured mean±sd over Random seeds 1..30.

### Other after metrics (bad-window, predictive)

- Preemptive failovers: **100.0±0.0**
- Warm hits: **100.0±0.0**
- Success rate: **100.0%±0.0**

Outside the bad window, circuit breaker can show fewer connect failures than predictive (morning ~5.4 vs ~11.9) when predictive briefly avoids after live clusters — reported honestly in the paper.

## `mvn test` summary (after item 3)

```
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(JDK 17+ / Temurin 25; Maven 3.9.9)

## Remaining limitations

1. Evaluation uses `FlakyEndpointConnector`, not production WAN traces.
2. Predictive over-avoidance after bursts is mitigated by recovery probes but not eliminated.
3. Scoring still uses EWMA time-of-day; hot-hour gating uses EWMA (plain rate display-only).
4. Review-2 cites fewer papers than Review-1’s 21 — intentional scope; Review-1 unchanged.
5. Multi-region / multi-driver production matrices are future work.

## How to reproduce experiments

```bash
cd pattern-aware-pool
export JAVA_HOME=...(JDK 17+)
mvn test
mvn -q exec:java -Ddemo.mainClass=com.college.pap.demo.ExperimentRunner
# optional: .venv/bin/python docs/generate_review2_paper.py
```
