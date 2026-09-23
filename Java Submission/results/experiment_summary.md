# Experiment Summary (honest protocol)

- Seeds: **30** (Random seeds 1..30)
- Learning: 7 days × 24h × 12 req/h via live FlakyEndpointConnector (no answer-seeding)
- Measurement: day 8 windows; `reuseEnabled=false`; latency = simulated ms (no sleep)
- Modes: reactive, circuit_breaker (open after 3 primary fails / 60s / half-open probe), predictive
- Wall time: 101.6s

| Mode | Scenario | n | Connect Failures (mean±sd) | Success Rate | Preemptive Failovers | Warm Hits | Backup Sel | Latency sim ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| reactive | morning-healthy | 30 | 19.3±6.5 | 99.8%±0.4 | 0.0±0.0 | 0.0±0.0 | 18.8±6.3 | 24±0 |
| reactive | bad-window | 30 | 85.9±4.0 | 98.9%±1.0 | 0.0±0.0 | 0.0±0.0 | 83.7±3.5 | 21±0 |
| reactive | evening-stable | 30 | 15.1±5.3 | 99.8%±0.5 | 0.0±0.0 | 0.0±0.0 | 14.7±5.4 | 24±0 |
| reactive | window-start | 30 | 0.5±0.6 | 99.4%±0.5 | 0.0±0.0 | 0.0±0.0 | 110.4±6.6 | 22±0 |
| reactive | reuse-off | 30 | 10.7±3.1 | 100.0%±0.0 | 0.0±0.0 | 0.0±0.0 | 10.5±3.2 | 20±0 |
| reactive | reuse-on | 30 | 0.0±0.0 | 100.0%±0.0 | 0.0±0.0 | 0.0±0.0 | 0.0±0.0 | 20±0 |
| reactive | pattern-shift-h14 | 30 | 14.7±5.2 | 99.9%±0.3 | 0.0±0.0 | 0.0±0.0 | 14.6±5.2 | 24±0 |
| reactive | pattern-shift-h15 | 30 | 78.1±3.2 | 98.8%±1.2 | 0.0±0.0 | 0.0±0.0 | 76.0±2.6 | 21±0 |
| circuit_breaker | morning-healthy | 30 | 5.4±1.1 | 98.9%±1.0 | 0.0±0.0 | 0.0±0.0 | 88.8±12.1 | 21±1 |
| circuit_breaker | bad-window | 30 | 3.2±1.1 | 99.0%±0.9 | 0.0±0.0 | 0.0±0.0 | 99.9±0.6 | 20±0 |
| circuit_breaker | evening-stable | 30 | 5.0±1.4 | 99.4%±0.8 | 0.0±0.0 | 0.0±0.0 | 50.8±21.4 | 22±1 |
| circuit_breaker | window-start | 30 | 0.5±0.6 | 99.2%±0.6 | 0.0±0.0 | 0.0±0.0 | 177.8±9.9 | 20±0 |
| circuit_breaker | reuse-off | 30 | 10.7±3.1 | 100.0%±0.0 | 0.0±0.0 | 0.0±0.0 | 10.6±3.1 | 20±0 |
| circuit_breaker | reuse-on | 30 | 0.0±0.0 | 100.0%±0.0 | 0.0±0.0 | 0.0±0.0 | 0.0±0.0 | 20±0 |
| circuit_breaker | pattern-shift-h14 | 30 | 5.5±1.0 | 99.3%±0.8 | 0.0±0.0 | 0.0±0.0 | 60.1±24.1 | 22±1 |
| circuit_breaker | pattern-shift-h15 | 30 | 3.9±2.0 | 98.8%±1.3 | 0.0±0.0 | 0.0±0.0 | 89.5±1.0 | 20±0 |
| predictive | morning-healthy | 30 | 2.9±0.8 | 100.0%±0.0 | 77.6±14.5 | 77.6±14.5 | 80.5±14.0 | 5±4 |
| predictive | bad-window | 30 | 0.8±0.7 | 100.0%±0.0 | 99.2±0.8 | 99.2±0.8 | 100.0±0.2 | 0±0 |
| predictive | evening-stable | 30 | 3.2±1.4 | 100.0%±0.2 | 52.2±21.0 | 52.2±21.0 | 55.3±20.1 | 9±6 |
| predictive | window-start | 30 | 0.0±0.0 | 100.0%±0.0 | 175.0±15.0 | 175.0±15.0 | 175.5±13.8 | 1±2 |
| predictive | reuse-off | 30 | 10.8±3.2 | 100.0%±0.0 | 0.0±0.2 | 0.0±0.0 | 10.6±3.2 | 20±0 |
| predictive | reuse-on | 30 | 0.0±0.0 | 100.0%±0.0 | 0.0±0.0 | 0.0±0.0 | 0.0±0.0 | 20±0 |
| predictive | pattern-shift-h14 | 30 | 2.5±1.3 | 99.9%±0.3 | 72.4±15.2 | 72.4±15.2 | 74.8±14.2 | 5±4 |
| predictive | pattern-shift-h15 | 30 | 0.7±0.8 | 100.0%±0.0 | 89.1±1.3 | 89.1±1.3 | 89.8±0.6 | 0±0 |

Compare to `before_fixes.csv` (audit seeded-history targets). Numbers here are measured — not curated.
