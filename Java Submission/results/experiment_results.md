# Experiment Results

| Mode | Scenario | Req | Success | Connect Failures | Preemptive Failovers | Warm Hits | Backup Selections |
|---|---|---:|---:|---:|---:|---:|---:|
| reactive | morning-healthy | 100 | 100.0% | 19 | 0 | 0 | 19 |
| predictive | morning-healthy | 100 | 100.0% | 9 | 57 | 0 | 65 |
| reactive | afternoon-bad-window | 100 | 99.0% | 83 | 0 | 0 | 81 |
| predictive | afternoon-bad-window | 100 | 100.0% | 3 | 97 | 8 | 100 |
| reactive | evening-stable | 80 | 100.0% | 10 | 0 | 0 | 10 |
| predictive | evening-stable | 80 | 100.0% | 6 | 6 | 0 | 12 |

**Reading the table:** lower *Connect Failures* and higher *Preemptive Failovers*/*Warm Hits* in the afternoon window show predictive avoidance working. Reactive mode still completes many requests via post-failure failover, but pays primary connect failures first.
