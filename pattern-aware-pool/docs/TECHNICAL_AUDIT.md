# TECHNICAL_AUDIT.md — Pattern-Aware Pool (post Phase 1–9)

Accurate checklist against the audit that motivated the fix series. Status as of commits through paper regeneration.

| # | Claim / finding | Status | Evidence |
|---|---|---|---|
| 1 | Live RMI config must affect RoutingDecider / PredictionEngine immediately | **DONE** | `PoolConfig` volatiles; engines read suppliers each call (`LiveConfigTest`) |
| 2 | Hot-hour failover must work from time-of-day alone (not only composite score) | **DONE** | `HotHourRules` + plain empirical rate gating (`HotHourFailoverTest`) |
| 3 | Live cluster avoidance + recovery so primary can return | **DONE** | `clusterAvoidRun` trigger; `BackupPreWarmer` probes while avoided/cluster-sticky (`RecoveryProbeTest`) |
| 4 | Real connection pooling (reuse on close) | **DONE** | `IdleConnectionPool` + `PapConnection.close()` release (`IdleConnectionPoolTest`) |
| 5 | Reset / threshold pattern | **DONE** | `ResetPatternDetector` + `Trigger.RESET_PREDICTED` (one-shot consume) (`ResetPatternDetectorTest`) |
| 6 | Experiments must not seed day-8 answers; report vs circuit breaker | **DONE** | `ExperimentRunner`: 7-day live learn, day-8 measure, modes reactive / circuit_breaker / predictive; n=30 seeds |
| 7 | Demos must print attached `PapConnection.routingDecision` | **DONE** | `FullSystemDemo`, `RealJdbcDeployDemo` |
| 8 | Paper must not hardcode 83→3; read CSV; honest framing | **DONE** | `generate_review2_paper.py` reads `experiment_summary.csv` |
| 9 | Swing not JavaFX; Spring-compatible lifecycle not Spring | **DONE** | `MonitoringDashboard` (Swing); `PoolLifecycle` |
| 10 | Machine-specific README paths | **DONE** | Portable `JAVA_HOME` + `mvn` commands |

## Measured bad-window connect failures (n=30, honest protocol)

| Mode | mean±sd |
|---|---|
| reactive | 85.9±4.0 |
| circuit_breaker | 3.2±1.1 |
| predictive | 0.0±0.0 |

Source: `docs/results/experiment_summary.csv`.

## Remaining limitations

- Simulator-only evaluation (not production WAN traces).
- Predictive can briefly over-avoid after live clusters outside the bad window (CB may show fewer connect failures in morning/evening).
- Hot-hour uses plain rate; scoring still uses EWMA tod component.
- Review-1 cites 21 papers; Review-2 cites a smaller working set — Review-1 left untouched.
