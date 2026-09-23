# Review-2 Complete Research Paper

**Authors:** Sara Sharma (23BCE0967), Tanvi Singh (23BCE2155)

> Full DOCX: `Review2_Complete_Research_Paper.docx` — numbers loaded from `experiment_summary.csv` (n=30).

## Bad-window user-facing connect failures (mean±sd)

| Mode | User-Facing Failures / 100 req | Preemptive Failovers | Warm Hits | Probes ok/fail | Prewarm |
|---|---:|---:|---:|---:|---:|
| Reactive | 85.9±4.0 | 0.0±0.0 | 0.0±0.0 | — | — |
| Circuit breaker | 3.2±1.1 | 0.0±0.0 | 0.0±0.0 | — | — |
| Predictive | 0.8±0.7 | 99.2±0.8 | 99.2±0.8 | 0.6±0.7/3.4±0.7 | 98.5±0.7 |

## Additional scenarios

| Scenario | Metric | Value |
|---|---|---|
| window-start (predictive) | user-facing failures (14:00–14:05) | 0.0±0.0 |
| pattern-shift-h14 (predictive) | backup selections | 74.8±14.2 |
| pattern-shift-h15 (predictive) | user-facing failures | 0.7±0.8 |
| reuse-off (predictive) | physical connects | 999.9±0.3 |
| reuse-on (predictive) | physical connects | 1.0±0.0 |
| morning-healthy backup share | predictive vs CB | 80.5±14.0 vs 88.8±12.1 |

## Protocol
7-day live learning → day-8 measure; seeded Random 1..30; no answer-seeding; routing experiments reuseEnabled=false; recoveryProbeSeconds=30; identical backgroundTick cadence; simulated latency (no sleep).

## Stack notes
Swing (not JavaFX); Spring-compatible lifecycle class (not Spring); real IdleConnectionPool reuse; recovery probes; routing = score OR hot-hour (EWMA) OR live cluster OR reset prediction.

## Commands
```bash
cd pattern-aware-pool
export JAVA_HOME=...(JDK 17+)
./mvnw test
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.FullSystemDemo
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.ExperimentRunner
```
