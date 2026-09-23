# Review-2 Complete Research Paper

**Authors:** Sara Sharma (23BCE0967), Tanvi Singh (23BCE2155)

> Full DOCX: `Review2_Complete_Research_Paper.docx` — numbers loaded from `experiment_summary.csv` (n=30).

## Bad-window user-facing connect failures (mean±sd)

| Mode | User-Facing Failures / 100 req | Preemptive Failovers | Warm Hits | Probes ok/fail | Prewarm |
|---|---:|---:|---:|---:|---:|
| Reactive | 85.6±3.7 | 0.0±0.0 | 0.0±0.0 | — | — |
| Circuit breaker | 3.8±1.5 | 0.0±0.0 | 0.0±0.0 | — | — |
| Predictive | 0.3±0.7 | 99.7±0.8 | 99.7±0.8 | 0.5±0.8/3.5±0.8 | 98.7±0.9 |

## Headline: window-start user-facing failures (14:00–14:05)

| Mode | User-Facing Failures |
|---|---:|
| Reactive | 51.1±2.7 |
| Circuit breaker | 8.0±2.2 |
| Predictive | 0.8±1.1 |

## Additional scenarios

| Scenario | Metric | Value |
|---|---|---|
| pattern-shift-h14 (predictive) | backup selections | 33.5±13.5 |
| pattern-shift-h15 (predictive) | user-facing failures | 3.5±2.0 |
| reuse-off (predictive) | physical connects | 999.9±0.3 |
| reuse-on (predictive) | physical connects / mean latency | 1.0±0.0 / 0.02±0.00 ms |
| morning-healthy backup share | predictive vs CB | 29.3±9.7 vs 35.6±10.9 |

## Protocol
7-day live learning → day-8 chronological measure (morning → window-start → bad-window → evening) with monotonic-clock assertion; seeded Random 1..30; no answer-seeding; routing experiments reuseEnabled=false; recoveryProbeSeconds=30; identical backgroundTick cadence; simulated latency (no sleep); idle-pool reuse hits = 0 ms. Windows: morning/evening/pattern-shift **30 min @ 1 req/18 s (100)**; bad-window **100 @ 1 req/s**; window-start **13:55–14:10 @ 1 req/5 s** (UF failures = metrics delta in 14:00–14:05).

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
