# Pattern-Aware Predictive Connection Pool

Advanced Java final-year project — history-aware JDBC connection pool that learns, predicts, reroutes, and pre-warms.

## Prerequisites

- JDK 17+ (`JAVA_HOME` pointing at your JDK)
- No system Maven required — use the included Maven Wrapper (`./mvnw`)

```bash
export JAVA_HOME="…/Contents/Home"   # your JDK 17+ install
export PATH="$JAVA_HOME/bin:$PATH"
cd pattern-aware-pool
```

## Build & test

```bash
./mvnw test
```

## Demos

```bash
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.FullSystemDemo
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.ExperimentRunner
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.RealJdbcDeployDemo
```

Optional Swing monitor:

```bash
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.ui.MonitoringDashboard
```

## Deployable (real JDBC)

```java
ConnectionPool pool = PoolBuilder.create()
    .primary("primary-db", "jdbc:postgresql://db1:5432/app", "user", "pass")
    .backup("backup-db", "jdbc:postgresql://db2:5432/app", "user", "pass")
    .buildPredictive();

try (PapConnection c = pool.getConnection()) {
    // Prefer the decision attached to the connection — do not recompute.
    c.routingDecision().ifPresent(d ->
            System.out.println(d.reason() + " / " + d.trigger()));
    java.sql.Connection jdbc = (java.sql.Connection) c.nativeHandle();
}
```

## What is real vs optional

- **Real / deployable:** `JdbcEndpointConnector` + `PoolBuilder` via `DriverManager`
- **Optional simulator:** `FlakyEndpointConnector` for controlled experiments/paper graphs
- Failures in `RealJdbcDeployDemo` are real TCP connection-refused errors, not mocks
- Recovery phase starts an H2 TCP server on a free port and shows probe-driven return to primary
- `PooledDataSource` phase shows `SESSION_ID()` reuse across checkouts

## Review-2 document

`../Java Submission/Review2_Complete_Research_Paper.docx`

Experiment CSVs: `docs/results/` (copied to `../Java Submission/results/`).
