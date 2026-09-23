package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Objects;
import java.util.Properties;

/**
 * Real JDBC connector — opens live database connections via {@link DriverManager}.
 * This is the deployable path (MySQL, PostgreSQL, H2, etc.).
 */
public final class JdbcEndpointConnector implements EndpointConnector {

    private final EndpointId endpointId;
    private final String jdbcUrl;
    private final Properties props;
    private final int loginTimeoutSeconds;

    public JdbcEndpointConnector(EndpointId endpointId, String jdbcUrl, String username, String password) {
        this(endpointId, jdbcUrl, username, password, 5);
    }

    public JdbcEndpointConnector(
            EndpointId endpointId,
            String jdbcUrl,
            String username,
            String password,
            int loginTimeoutSeconds) {
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.props = new Properties();
        if (username != null) {
            this.props.setProperty("user", username);
        }
        if (password != null) {
            this.props.setProperty("password", password);
        }
        this.loginTimeoutSeconds = Math.max(1, loginTimeoutSeconds);
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public EndpointId endpointId() {
        return endpointId;
    }

    @Override
    public PapConnection connect() throws ConnectionFailedException {
        int previous = DriverManager.getLoginTimeout();
        try {
            DriverManager.setLoginTimeout(loginTimeoutSeconds);
            Connection jdbc = DriverManager.getConnection(jdbcUrl, props);
            jdbc.setAutoCommit(true);
            return new PapConnection(endpointId, jdbc, () -> closeQuietly(jdbc), false);
        } catch (SQLTimeoutException e) {
            throw new ConnectionFailedException(
                    "timeout connecting to " + endpointId + " (" + jdbcUrl + "): " + e.getMessage(),
                    FailureType.TIMEOUT);
        } catch (SQLException e) {
            throw new ConnectionFailedException(
                    "JDBC connect failed for " + endpointId + " (" + jdbcUrl + "): " + e.getMessage(),
                    classify(e));
        } finally {
            DriverManager.setLoginTimeout(previous);
        }
    }

    private static FailureType classify(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        Throwable cause = e.getCause();
        String causeMsg = cause == null || cause.getMessage() == null
                ? ""
                : cause.getMessage().toLowerCase();
        String combined = msg + " " + causeMsg;
        if (state.startsWith("08")
                || combined.contains("connection refused")
                || combined.contains("connection is broken")
                || combined.contains("network")
                || combined.contains("socket")
                || combined.contains("unreachable")
                || combined.contains("operation not permitted")
                || combined.contains("refused")
                || cause instanceof java.net.ConnectException
                || cause instanceof java.net.SocketException
                || cause instanceof java.net.NoRouteToHostException
                || cause instanceof java.net.UnknownHostException) {
            return FailureType.NETWORK_UNREACHABLE;
        }
        if (combined.contains("timeout") || combined.contains("timed out")) {
            return FailureType.TIMEOUT;
        }
        if (state.startsWith("28") || combined.contains("auth") || combined.contains("password")) {
            return FailureType.AUTH_FAILURE;
        }
        if (combined.contains("ssl")) {
            return FailureType.SSL_RESET;
        }
        return FailureType.UNKNOWN;
    }

    private static void closeQuietly(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // ignore on return-to-pool/close
        }
    }
}
