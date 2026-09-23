package com.college.pap.demo;

import com.college.pap.model.EndpointId;
import com.college.pap.pool.ConnectionPool;
import com.college.pap.pool.EndpointConnector;
import com.college.pap.pool.PapConnection;
import com.college.pap.pool.PoolBuilder;
import com.college.pap.pool.PooledDataSource;
import com.college.pap.prediction.RiskScore;
import com.college.pap.routing.RoutingDecision;
import org.h2.tools.Server;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Deployable real-JDBC demo (no flaky simulator).
 *
 * <p>Phases:
 * <ol>
 *   <li>Outage — primary URL points at a free port with no server; real connection refused → failover.</li>
 *   <li>Recovery — start an H2 TCP server on that port; recovery probe detects health and routes back.</li>
 *   <li>PooledDataSource — show {@code SESSION_ID()} reuse across checkouts.</li>
 * </ol>
 *
 * <p>Printed risk is always the <b>requested</b> endpoint's score, not the selected one's.
 */
public final class RealJdbcDeployDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Real JDBC Deployable Demo (no simulator) ===\n");

        Path dataDir = Path.of("data");
        Files.createDirectories(dataDir);
        String backupUrl = "jdbc:h2:file:" + dataDir.toAbsolutePath() + "/backup-db;DB_CLOSE_DELAY=-1";
        bootstrapSchema(backupUrl);

        int freePort = freeTcpPort();
        String primaryUrl = "jdbc:h2:tcp://127.0.0.1:" + freePort + "/./primary-db";
        Path primaryDir = dataDir.resolve("primary-tcp");
        Files.createDirectories(primaryDir);

        Server tcpServer = null;
        try {
            System.out.println("Phase 1 — primary URL on free port " + freePort + " (server not started yet)");
            try (ConnectionPool pool = PoolBuilder.create()
                    .primary("primary-db", primaryUrl, "sa", "")
                    .backup("backup-db", backupUrl, "sa", "")
                    .loginTimeoutSeconds(2)
                    .preWarmLeadMinutes(10)
                    .warmPoolSize(3)
                    .highRiskThreshold(0.40)
                    .buildPredictive()) {

                pool.config().setRecoveryProbeSeconds(1);

                System.out.println("Primary JDBC : " + primaryUrl);
                System.out.println("Backup JDBC  : " + backupUrl);
                System.out.println();

                System.out.println("1) Requests while primary is down — expect real connection-refused + failover...");
                for (int i = 1; i <= 5; i++) {
                    checkout(pool, i);
                }

                pool.analyzer().analyzeAll();
                System.out.println("\n2) Learned profiles:");
                pool.analyzer().getAllProfiles().forEach((id, profile) ->
                        System.out.println("   " + profile));

                System.out.println("\n3) More requests — pool should prefer backup after learning...");
                for (int i = 6; i <= 8; i++) {
                    checkout(pool, i);
                }

                System.out.println("\nPhase 2 — starting H2 TCP primary on port " + freePort + "...");
                tcpServer = Server.createTcpServer(
                        "-tcp",
                        "-tcpPort",
                        String.valueOf(freePort),
                        "-tcpAllowOthers",
                        "-ifNotExists",
                        "-baseDir",
                        primaryDir.toAbsolutePath().toString()).start();
                bootstrapSchema(primaryUrl);
                System.out.println("   H2 TCP listening: " + tcpServer.getStatus());

                System.out.println("4) Recovery probes (backgroundTick / probePrimaryNow)...");
                for (int attempt = 1; attempt <= 5; attempt++) {
                    pool.backgroundTick();
                    pool.preWarmer().probePrimaryNow();
                    long ok = pool.metrics().recoveryProbesOk();
                    long fail = pool.metrics().recoveryProbesFail();
                    System.out.printf("   probe round %d: recoveryProbesOk=%d fail=%d%n", attempt, ok, fail);
                    if (ok >= 1) {
                        break;
                    }
                    Thread.sleep(200);
                }

                System.out.println("5) Requests after recovery — expect routing back toward primary...");
                for (int i = 9; i <= 12; i++) {
                    checkout(pool, i);
                }

                System.out.println("\n6) Metrics:");
                System.out.println("   " + pool.metrics().snapshot());
            }

            System.out.println("\nPhase 3 — PooledDataSource SESSION_ID() reuse on healthy primary...");
            try (ConnectionPool pool = PoolBuilder.create()
                    .primary("primary-db", primaryUrl, "sa", "")
                    .backup("backup-db", backupUrl, "sa", "")
                    .loginTimeoutSeconds(2)
                    .warmPoolSize(2)
                    .buildPredictive()) {

                pool.config().setReuseEnabled(true);
                PooledDataSource ds = new PooledDataSource(pool);
                String firstSession = null;
                for (int i = 1; i <= 3; i++) {
                    try (Connection c = ds.getConnection();
                         Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery("SELECT SESSION_ID()")) {
                        rs.next();
                        String session = rs.getString(1);
                        if (firstSession == null) {
                            firstSession = session;
                        }
                        System.out.printf(
                                "   checkout #%d SESSION_ID=%s sameAsFirst=%s%n",
                                i, session, session.equals(firstSession));
                    }
                }
            }

            System.out.println("\nDeploy note: replace URLs with your MySQL/Postgres endpoints for production.");
        } finally {
            if (tcpServer != null) {
                tcpServer.stop();
            }
        }
    }

    private static int freeTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void bootstrapSchema(String jdbcUrl) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS healthcheck(id INT PRIMARY KEY, ok BOOLEAN)");
            st.execute("MERGE INTO healthcheck KEY(id) VALUES (1, TRUE)");
        }
    }

    private static void checkout(ConnectionPool pool, int n) {
        EndpointId requested = pool.registry().primary();
        try (PapConnection pap = pool.getConnection(requested)) {
            RoutingDecision decision = pap.routingDecision().orElse(null);
            Object handle = pap.nativeHandle();
            String jdbcState = "n/a";
            if (handle instanceof Connection jdbc) {
                try (Statement st = jdbc.createStatement();
                     var rs = st.executeQuery("SELECT ok FROM healthcheck WHERE id = 1")) {
                    jdbcState = rs.next() ? "SELECT ok=" + rs.getBoolean(1) : "empty";
                }
            }
            String reason = decision == null ? "?" : decision.reason().name();
            String trigger = decision == null ? "?" : decision.trigger().name();
            // Requested endpoint risk — not the selected endpoint's score.
            String risk = "?";
            if (decision != null) {
                RiskScore requestedScore = decision.allScores().get(decision.requested());
                if (requestedScore == null) {
                    requestedScore = decision.allScores().get(requested);
                }
                if (requestedScore != null) {
                    risk = String.format("%.2f", requestedScore.score());
                }
            }
            System.out.printf(
                    "   #%d requested=%-10s selected=%-10s preWarmed=%-5s reason=%-20s trigger=%-16s requestedRisk=%s sql=[%s]%n",
                    n,
                    requested,
                    pap.endpointId(),
                    pap.isPreWarmed(),
                    reason,
                    trigger,
                    risk,
                    jdbcState);
        } catch (EndpointConnector.ConnectionFailedException e) {
            System.out.printf("   #%d FAILED: %s (%s)%n", n, e.getMessage(), e.failureType());
        } catch (Exception e) {
            System.out.printf("   #%d ERROR: %s%n", n, e.getMessage());
        }
    }
}
