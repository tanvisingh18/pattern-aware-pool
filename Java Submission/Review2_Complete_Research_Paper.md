# Review-2 Complete Research Paper

**Authors:** Sara Sharma (23BCE0967), Tanvi Singh (23BCE2155)

> Full DOCX: `Review2_Complete_Research_Paper.docx` — numbers loaded from `experiment_summary.csv` (n=30).

## Bad-window connect failures (mean±sd)

| Mode | Connect Failures / 100 req | Preemptive Failovers | Warm Hits |
|---|---:|---:|---:|
| Reactive | 85.9±4.0 | 0.0±0.0 | 0.0±0.0 |
| Circuit breaker | 3.2±1.1 | 0.0±0.0 | 0.0±0.0 |
| Predictive | 0.0±0.0 | 100.0±0.0 | 100.0±0.0 |

## Honest protocol
7-day live learning → day-8 measure; seeded Random 1..30; no answer-seeding; reuseEnabled=false; simulated latency (no sleep).

## Stack notes
Swing (not JavaFX); Spring-compatible lifecycle class (not Spring); real IdleConnectionPool reuse; recovery probes; routing = score OR hot-hour OR live cluster OR reset prediction.

## Commands
```bash
cd pattern-aware-pool
export JAVA_HOME=...(JDK 17+)
mvn test
mvn -q exec:java -Ddemo.mainClass=com.college.pap.demo.FullSystemDemo
mvn -q exec:java -Ddemo.mainClass=com.college.pap.demo.ExperimentRunner
```
