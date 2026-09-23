package com.college.pap.demo;

import com.college.pap.model.EndpointId;
import com.college.pap.pool.ConnectionPool;
import com.college.pap.pool.EndpointConnector;
import com.college.pap.pool.PapConnection;
import com.college.pap.pool.PoolBuilder;
import com.college.pap.routing.RoutingDecision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

/**
 * Deployable real-JDBC demo (no flaky simulator).
 *
 * <p>Uses live {@code DriverManager} connections:
 * <ul>
 *   <li>backup = real local H2 database (always reachable)</li>
 *   <li>primary = real JDBC URL aimed at a closed TCP port (connection refused)</li>
 * </ul>
 *
 * That refused connect is a <b>real network failure</b>, not a mock. The pool records it,
 * learns the cluster, and failsover to the working backup.
 *
 * <p>To deploy against your own DBs, change the URLs in {@link #main} / {@link PoolBuilder}:
 * <pre>
 * PoolBuilder.create()
 *   .primary("primary-db", "jdbc:postgresql://db1:5432/app", "user", "pass")
 *   .backup("backup-db", "jdbc:postgresql://db2:5432/app", "user", "pass")
 *   .buildPredictive();
 * </pre>
 */
public final class RealJdbcDeployDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Real JDBC Deployable Demo (no simulator) ===\n");

        Path dataDir = Path.of("data");
        Files.createDirectories(dataDir);
        String backupUrl = "jdbc:h2:file:" + dataDir.toAbsolutePath() + "/backup-db;DB_CLOSE_DELAY=-1";
        // Closed port → real TCP connection refused via JDBC DriverManager
        String primaryUrl = "jdbc:h2:tcp://127.0.0.1:1/unreachable";

        // Ensure backup schema exists with a direct JDBC bootstrap.
        bootstrapBackup(backupUrl);

        try (ConnectionPool pool = PoolBuilder.create()
                .primary("primary-db", primaryUrl, "sa", "")
                .backup("backup-db", backupUrl, "sa", "")
                .loginTimeoutSeconds(2)
                .preWarmLeadMinutes(10)
                .warmPoolSize(3)
                .highRiskThreshold(0.40)
                .buildPredictive()) {

            System.out.println("Primary JDBC : " + primaryUrl);
            System.out.println("Backup JDBC  : " + backupUrl);
            System.out.println();

            System.out.println("1) First requests — primary will fail for real (connection refused)...");
            for (int i = 1; i <= 5; i++) {
                checkout(pool, i);
            }

            pool.analyzer().analyzeAll();
            System.out.println("\n2) Learned profiles:");
            pool.analyzer().getAllProfiles().forEach((id, profile) ->
                    System.out.println("   " + profile));

            System.out.println("\n3) Later requests — pool should prefer backup after learning...");
            for (int i = 6; i <= 10; i++) {
                checkout(pool, i);
            }

            System.out.println("\n4) Metrics:");
            System.out.println("   " + pool.metrics().snapshot());
            System.out.println("\nDeploy note: replace URLs with your MySQL/Postgres endpoints for production.");
        }
    }

    private static void bootstrapBackup(String backupUrl) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(backupUrl, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS healthcheck(id INT PRIMARY KEY, ok BOOLEAN)");
            st.execute("MERGE INTO healthcheck KEY(id) VALUES (1, TRUE)");
        }
    }

    private static void checkout(ConnectionPool pool, int n) {
        EndpointId primary = new EndpointId("primary-db");
        Instant at = Instant.now();
        try (PapConnection pap = pool.getConnection()) {
            RoutingDecision decision = pool.routingDecider().decide(primary, at);
            Object handle = pap.nativeHandle();
            String jdbcState = "n/a";
            if (handle instanceof Connection jdbc) {
                try (Statement st = jdbc.createStatement();
                     var rs = st.executeQuery("SELECT ok FROM healthcheck WHERE id = 1")) {
                    jdbcState = rs.next() ? "SELECT ok=" + rs.getBoolean(1) : "empty";
                }
            }
            System.out.printf(
                    "   #%d endpoint=%-10s preWarmed=%-5s reason=%-20s sql=[%s]%n",
                    n,
                    pap.endpointId(),
                    pap.isPreWarmed(),
                    decision.reason(),
                    jdbcState);
        } catch (EndpointConnector.ConnectionFailedException e) {
            System.out.printf("   #%d FAILED: %s (%s)%n", n, e.getMessage(), e.failureType());
        } catch (Exception e) {
            System.out.printf("   #%d ERROR: %s%n", n, e.getMessage());
        }
    }
}
