package com.college.pap.rmi;

import com.college.pap.pool.ConnectionPool;
import com.college.pap.pool.PoolBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real {@link PoolManagementService} (exported RMI object) and unexports after.
 */
class PoolManagementServiceTest {

    private PoolManagementService service;
    private ConnectionPool pool;

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            UnicastRemoteObject.unexportObject(service, true);
            service = null;
        }
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    @Test
    void realServiceMutatesLiveConfigAndIsUnexported() throws Exception {
        Path dataDir = Path.of("target", "rmi-test-db");
        Files.createDirectories(dataDir);
        String url = "jdbc:h2:file:" + dataDir.toAbsolutePath() + "/rmi;DB_CLOSE_DELAY=-1";
        try (Connection c = java.sql.DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS healthcheck(id INT PRIMARY KEY, ok BOOLEAN)");
            st.execute("MERGE INTO healthcheck KEY(id) VALUES (1, TRUE)");
        }

        pool = PoolBuilder.create()
                .primary("primary-db", url, "sa", "")
                .backup("backup-db", url, "sa", "")
                .highRiskThreshold(0.55)
                .buildPredictive();

        service = new PoolManagementService(pool);
        PoolManagementRemote remote = service;

        assertEquals(0.55, remote.getHighRiskThreshold(), 1e-9);
        remote.setHighRiskThreshold(0.0);
        assertEquals(0.0, pool.config().highRiskThreshold(), 1e-9);

        remote.setHotHourThreshold(0.77);
        assertEquals(0.77, pool.config().hotHourThreshold(), 1e-9);
        assertEquals(0.77, remote.getHotHourThreshold(), 1e-9);

        remote.setClusterAvoidRun(4);
        assertEquals(4, pool.config().clusterAvoidRun());
        assertEquals(4, remote.getClusterAvoidRun());

        remote.setRecoveryProbeSeconds(12);
        assertEquals(12, pool.config().recoveryProbeSeconds());
        assertEquals(12, remote.getRecoveryProbeSeconds());

        remote.setRiskWeights(0.5, 0.3, 0.2);
        assertEquals(0.5, pool.config().weights().alpha(), 1e-9);
        assertEquals(0.3, pool.config().weights().beta(), 1e-9);
        assertEquals(0.2, pool.config().weights().gamma(), 1e-9);
        assertTrue(remote.getRiskWeights() != null && !remote.getRiskWeights().isBlank());

        assertTrue(remote.getMetricsSnapshot().length() > 0);
    }
}
