# Experiment Summary (learn-then-measure)

- Seeds: **30** (Random seeds 1..30)
- Learning: 7 days × 24h × 12 req/h via live FlakyEndpointConnector (no answer-seeding)
- Measurement: day 8 windows (chronological: morning → window-start → bad-window → evening); routing experiments use `reuseEnabled=false`; latency = simulated ms (no sleep)
- Window lengths / spacing:
  - morning-healthy, evening-stable, pattern-shift-h14/h15: **30 min @ 1 req / 18 s** (100 requests)
  - bad-window: **100 requests @ 1 req / 1 s** (dense, unchanged)
  - window-start: **13:55–14:10 @ 1 req / 5 s**; user-facing failures counted in **14:00–14:05** via `metrics.userFacingConnectFailures()` deltas (~60 requests in-slice)
  - reuse-on/off: **1000 requests @ 1 req / 1 s** in a healthy hour (own pool)
- Modes: reactive, circuit_breaker (open after 3 primary fails / 60s / half-open probe), predictive
- recoveryProbeSeconds=30; backgroundTick cadence identical across modes
- Wall time: 53.9s

| Mode | Scenario | n | User-Facing Failures | Primary Connects | Success | Probes ok/fail | Prewarm | Physical | Preemptive FO | Warm | Backup | Latency mean/p95 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| reactive | morning-healthy | 30 | 19.3±6.5 | 80.9±6.4 | 99.8%±0.4 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 99.8±0.4 | 0.0±0.0 | 0.0±0.0 | 18.8±6.3 | 24±0 / 25±0 |
| reactive | window-start | 30 | 51.1±2.7 | 68.7±5.1 | 99.3%±0.6 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 179.7±1.1 | 0.0±0.0 | 0.0±0.0 | 111.0±5.3 | 22±0 / 25±0 |
| reactive | bad-window | 30 | 85.6±3.7 | 15.2±3.3 | 99.2%±0.8 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 99.2±0.8 | 0.0±0.0 | 0.0±0.0 | 84.0±3.2 | 21±0 / 25±0 |
| reactive | evening-stable | 30 | 17.6±5.1 | 82.6±5.0 | 99.8%±0.4 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 99.8±0.4 | 0.0±0.0 | 0.0±0.0 | 17.2±4.8 | 24±0 / 25±0 |
| reactive | reuse-off | 30 | 10.7±3.1 | 989.4±3.1 | 100.0%±0.0 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 999.9±0.3 | 0.0±0.0 | 0.0±0.0 | 10.5±3.2 | 20±0 / 20±0 |
| reactive | reuse-on | 30 | 0.0±0.0 | 1.0±0.0 | 100.0%±0.0 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 1.0±0.0 | 0.0±0.0 | 0.0±0.0 | 0.0±0.0 | 0±0 / 0±0 |
| reactive | pattern-shift-h14 | 30 | 16.2±5.6 | 83.9±5.5 | 99.9%±0.3 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 99.9±0.3 | 0.0±0.0 | 0.0±0.0 | 16.0±5.5 | 24±0 / 25±0 |
| reactive | pattern-shift-h15 | 30 | 86.9±3.7 | 14.3±3.1 | 98.8%±1.3 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 98.8±1.3 | 0.0±0.0 | 0.0±0.0 | 84.5±3.1 | 21±0 / 25±0 |
| circuit_breaker | morning-healthy | 30 | 16.1±4.2 | 64.4±10.9 | 99.7%±0.5 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 99.7±0.5 | 0.0±0.0 | 0.0±0.0 | 35.6±10.9 | 23±1 / 25±0 |
| circuit_breaker | window-start | 30 | 8.0±2.2 | 30.2±10.5 | 99.0%±0.7 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 179.2±1.2 | 0.0±0.0 | 0.0±0.0 | 150.8±10.5 | 21±0 / 25±0 |
| circuit_breaker | bad-window | 30 | 3.8±1.5 | 0.6±0.9 | 99.3%±0.7 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 99.3±0.7 | 0.0±0.0 | 0.0±0.0 | 99.4±0.9 | 20±0 / 20±0 |
| circuit_breaker | evening-stable | 30 | 14.7±3.6 | 68.3±9.9 | 99.8%±0.5 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 99.8±0.5 | 0.0±0.0 | 0.0±0.0 | 31.7±9.9 | 23±1 / 25±0 |
| circuit_breaker | reuse-off | 30 | 10.7±3.1 | 989.4±3.1 | 100.0%±0.0 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 999.9±0.3 | 0.0±0.0 | 0.0±0.0 | 10.6±3.1 | 20±0 / 20±0 |
| circuit_breaker | reuse-on | 30 | 0.0±0.0 | 1.0±0.0 | 100.0%±0.0 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 1.0±0.0 | 0.0±0.0 | 0.0±0.0 | 0.0±0.0 | 0±0 / 0±0 |
| circuit_breaker | pattern-shift-h14 | 30 | 14.0±3.9 | 71.4±10.9 | 99.8%±0.4 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 99.8±0.4 | 0.0±0.0 | 0.0±0.0 | 28.6±10.9 | 24±1 / 25±0 |
| circuit_breaker | pattern-shift-h15 | 30 | 32.3±3.0 | 4.9±2.2 | 98.7%±1.4 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 98.7±1.4 | 0.0±0.0 | 0.0±0.0 | 95.1±2.2 | 21±0 / 23±3 |
| predictive | morning-healthy | 30 | 9.7±2.3 | 70.6±9.8 | 99.9%±0.3 | 6.1±2.3 / 6.9±3.0 | 19.4±7.8 | 80.1±8.1 | 19.8±8.0 | 19.8±8.0 | 29.3±9.7 | 20±2 / 25±0 |
| predictive | window-start | 30 | 0.8±1.1 | 27.5±13.0 | 100.0%±0.0 | 7.8±2.9 / 20.8±2.5 | 148.1±13.1 | 32.3±13.0 | 148.7±13.0 | 148.7±13.0 | 153.5±13.0 | 4±2 / 23±6 |
| predictive | bad-window | 30 | 0.3±0.7 | 0.0±0.2 | 100.0%±0.0 | 0.5±0.8 / 3.5±0.8 | 98.7±0.9 | 0.3±0.8 | 99.7±0.8 | 99.7±0.8 | 100.0±0.2 | 0±0 / 0±0 |
| predictive | evening-stable | 30 | 9.4±3.3 | 70.3±15.0 | 99.9%±0.3 | 6.7±3.6 / 7.2±4.4 | 21.1±12.3 | 79.5±12.2 | 20.4±12.2 | 20.4±12.2 | 29.6±15.0 | 19±3 / 25±0 |
| predictive | reuse-off | 30 | 10.8±3.2 | 989.3±3.2 | 100.0%±0.0 | 0.1±0.3 / 0.0±0.0 | 1.1±2.8 | 999.9±0.3 | 0.0±0.2 | 0.0±0.0 | 10.6±3.2 | 20±0 / 20±0 |
| predictive | reuse-on | 30 | 0.0±0.0 | 1.0±0.0 | 100.0%±0.0 | 0.0±0.0 / 0.0±0.0 | 0.0±0.0 | 1.0±0.0 | 0.0±0.0 | 0.0±0.0 | 0.0±0.0 | 0±0 / 0±0 |
| predictive | pattern-shift-h14 | 30 | 9.9±3.0 | 66.3±13.6 | 99.8%±0.4 | 7.9±3.0 / 7.9±4.5 | 23.6±11.7 | 75.9±12.0 | 23.9±11.8 | 23.9±11.8 | 33.5±13.5 | 19±3 / 25±0 |
| predictive | pattern-shift-h15 | 30 | 3.5±2.0 | 0.8±1.1 | 100.0%±0.2 | 7.3±2.3 / 41.8±2.6 | 95.2±3.0 | 4.2±2.8 | 95.7±2.8 | 95.7±2.8 | 99.1±1.1 | 2±4 / 5±9 |

See `FIXES.md` for the remediation history. Numbers here are measured mean±sd.
