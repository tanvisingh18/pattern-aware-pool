# Experiment Summary (honest protocol)

- Seeds: **30** (Random seeds 1..30)
- Learning: 7 days × 24h × 12 req/h via live FlakyEndpointConnector (no answer-seeding)
- Measurement: day 8 windows; `reuseEnabled=false`; latency = simulated ms (no sleep)
- Modes: reactive, circuit_breaker (open after 3 primary fails / 60s / half-open probe), predictive
- Wall time: 158.8s

| Mode | Scenario | n | Connect Failures (mean±sd) | Success Rate | Preemptive Failovers | Warm Hits | Backup Sel | Latency sim ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| reactive | morning-healthy | 30 | 19.3±6.5 | 99.8%±0.4 | 0.0±0.0 | 0.0±0.0 | 18.8±6.3 | 24±0 |
| reactive | bad-window | 30 | 85.9±4.0 | 98.9%±1.0 | 0.0±0.0 | 0.0±0.0 | 83.7±3.5 | 21±0 |
| reactive | evening-stable | 30 | 15.1±5.3 | 99.8%±0.5 | 0.0±0.0 | 0.0±0.0 | 14.7±5.4 | 24±0 |
| circuit_breaker | morning-healthy | 30 | 5.4±1.1 | 98.9%±1.0 | 0.0±0.0 | 0.0±0.0 | 88.8±12.1 | 21±1 |
| circuit_breaker | bad-window | 30 | 3.2±1.1 | 99.0%±0.9 | 0.0±0.0 | 0.0±0.0 | 99.9±0.6 | 20±0 |
| circuit_breaker | evening-stable | 30 | 5.0±1.4 | 99.4%±0.8 | 0.0±0.0 | 0.0±0.0 | 50.8±21.4 | 22±1 |
| predictive | morning-healthy | 30 | 11.9±3.4 | 99.9%±0.3 | 18.7±9.8 | 18.7±9.8 | 30.4±12.0 | 20±3 |
| predictive | bad-window | 30 | 0.0±0.0 | 100.0%±0.0 | 100.0±0.0 | 100.0±0.0 | 100.0±0.0 | 0±0 |
| predictive | evening-stable | 30 | 9.1±3.8 | 99.9%±0.4 | 15.8±11.6 | 15.8±11.6 | 24.7±14.3 | 20±4 |

Compare to `before_fixes.csv` (audit seeded-history targets). Numbers here are measured — not curated.
