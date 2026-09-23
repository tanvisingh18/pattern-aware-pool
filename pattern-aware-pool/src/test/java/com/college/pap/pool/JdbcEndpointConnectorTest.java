package com.college.pap.pool;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcEndpointConnectorTest {

    @Test
    void opensRealH2ConnectionAndValidates() throws Exception {
        Path dir = Path.of("target", "test-db");
        Files.createDirectories(dir);
        String url = "jdbc:h2:file:" + dir.toAbsolutePath() + "/jdbc-test;DB_CLOSE_DELAY=-1";

        JdbcEndpointConnector connector = new JdbcEndpointConnector(
                new com.college.pap.model.EndpointId("test-db"),
                url,
                "sa",
                "");

        try (PapConnection pap = connector.connect()) {
            assertTrue(pap.nativeHandle() instanceof Connection);
            assertTrue(new ConnectionValidator().validate(pap));
            Connection jdbc = (Connection) pap.nativeHandle();
            try (Statement st = jdbc.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS t(id INT)");
                st.execute("INSERT INTO t VALUES (1)");
            }
        }
    }

    @Test
    void unreachableHostIsRecordedAsNetworkFailure() {
        JdbcEndpointConnector connector = new JdbcEndpointConnector(
                new com.college.pap.model.EndpointId("down-db"),
                "jdbc:h2:tcp://127.0.0.1:1/nope",
                "sa",
                "",
                2);

        EndpointConnector.ConnectionFailedException ex = null;
        try {
            connector.connect();
        } catch (EndpointConnector.ConnectionFailedException e) {
            ex = e;
        }
        assertTrue(ex != null);
        assertEquals(
                com.college.pap.model.FailureType.NETWORK_UNREACHABLE,
                ex.failureType());
    }
}
