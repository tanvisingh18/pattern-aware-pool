package com.college.pap.pool;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Drop-in {@link DataSource} decorator around {@link ConnectionPool}.
 * Returns a JDBC {@link Connection} proxy whose {@code close()} returns the
 * handle to the idle pool instead of destroying it.
 */
public final class PooledDataSource implements DataSource {

    private final ConnectionPool pool;
    private PrintWriter logWriter;
    private int loginTimeoutSeconds = 5;

    public PooledDataSource(ConnectionPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    public ConnectionPool pool() {
        return pool;
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            PapConnection pap = pool.getConnection();
            return JdbcConnectionProxy.wrap(pap);
        } catch (EndpointConnector.ConnectionFailedException e) {
            throw new SQLException("pool checkout failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "credential override not supported; use getConnection()");
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeoutSeconds = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeoutSeconds;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("parent logger not supported");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
