# Pattern-Aware Predictive Connection Pool

Advanced Java final-year project — history-aware JDBC connection pool that learns, predicts, reroutes, and pre-warms.

## Deployable (real JDBC) — use this

```bash
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:../.tools/apache-maven-3.9.9/bin:$PATH"
cd pattern-aware-pool
mvn test
mvn -q -Ddemo.mainClass=com.college.pap.demo.RealJdbcDeployDemo exec:java
```

Wire your own databases:

```java
ConnectionPool pool = PoolBuilder.create()
    .primary("primary-db", "jdbc:postgresql://db1:5432/app", "user", "pass")
    .backup("backup-db", "jdbc:postgresql://db2:5432/app", "user", "pass")
    .buildPredictive();

try (PapConnection c = pool.getConnection()) {
    java.sql.Connection jdbc = (java.sql.Connection) c.nativeHandle();
    // use jdbc...
}
```

## What is real vs optional
- **Real / deployable:** `JdbcEndpointConnector` + `PoolBuilder` via `DriverManager`
- **Optional simulator:** `FlakyEndpointConnector` only for controlled experiments/paper graphs
- Failures in `RealJdbcDeployDemo` are real TCP connection-refused errors, not mocks

## Review-2 document
`../Java Submission/Review2_Complete_Research_Paper.docx`
