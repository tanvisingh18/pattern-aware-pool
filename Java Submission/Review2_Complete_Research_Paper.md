# Review-2 Complete Research Paper

**Title:** History-Aware Predictive Connection Pooling: A Client-Side Learning Approach to Self-Healing JDBC Connections in Structurally Unreliable Networks

**Authors:** Sara Sharma (23BCE0967), Tanvi Singh (23BCE2155)  
**Guide:** Mr. Syamasudha Veeragandham, VIT Vellore

> Full formatted DOCX: `Review2_Complete_Research_Paper.docx`

## Highlight Result (Afternoon Bad Window)
| Mode | Connect Failures / 100 req | Preemptive Failovers | Warm Hits |
|---|---:|---:|---:|
| Reactive | 83 | 0 | 0 |
| Predictive | 3 | 97 | 8 |

## Completed Software
- Layer 1: FailureHistoryStore + PatternAnalyzer
- Layer 2: PredictionEngine + RoutingDecider
- Layer 3: BackupPreWarmer + WarmPool + ConnectionValidator
- ConnectionPool (predictive + reactive baseline)
- FlakyEndpointConnector simulator
- ExperimentRunner + FullSystemDemo
- RMI PoolManagementRemote + PoolLifecycle
- MonitoringDashboard (Swing)
- Unit/integration tests

## Commands
```bash
cd pattern-aware-pool
mvn test
java -cp target/classes com.college.pap.demo.FullSystemDemo
java -cp target/classes com.college.pap.demo.ExperimentRunner
```
