# Experiment Results

| Mode | Scenario | Req | Success | Connect Failures | Preemptive Failovers | Warm Hits | Backup Selections |
|---|---|---:|---:|---:|---:|---:|---:|
| reactive | morning-healthy | 100 | 100.0% | 17 | 0 | 0 | 17 |
| predictive | morning-healthy | 100 | 100.0% | 4 | 89 | 0 | 93 |
| reactive | afternoon-bad-window | 100 | 99.0% | 91 | 0 | 0 | 89 |
| predictive | afternoon-bad-window | 100 | 100.0% | 2 | 98 | 8 | 100 |
| reactive | evening-stable | 80 | 100.0% | 11 | 0 | 0 | 11 |
| predictive | evening-stable | 80 | 98.8% | 10 | 67 | 0 | 74 |

**Reading the table:** lower *Connect Failures* and higher *Preemptive Failovers*/*Warm Hits* in the afternoon window show predictive avoidance working. Reactive mode still completes many requests via post-failure failover, but pays primary connect failures first.
