package com.college.pap.pool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDK proxy around a real {@link Connection}. {@code close()} returns the
 * {@link PapConnection} to the idle pool instead of closing the physical JDBC link.
 * After close, every method except {@code close}, {@code isClosed}, and {@code isValid}
 * throws {@code SQLException("connection closed")}.
 */
final class JdbcConnectionProxy implements InvocationHandler {

    private final PapConnection pap;
    private final Connection delegate;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private JdbcConnectionProxy(PapConnection pap, Connection delegate) {
        this.pap = pap;
        this.delegate = delegate;
    }

    static Connection wrap(PapConnection pap) throws SQLException {
        Objects.requireNonNull(pap, "pap");
        Connection jdbc = pap.unwrapJdbc()
                .orElseThrow(() -> new SQLException(
                        "endpoint " + pap.endpointId() + " did not yield a JDBC Connection"));
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new JdbcConnectionProxy(pap, jdbc));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("close".equals(name)) {
            if (closed.compareAndSet(false, true)) {
                pap.close(); // release to idle pool
            }
            return null;
        }
        if ("isClosed".equals(name)) {
            return closed.get() || !pap.isOpen();
        }
        if ("isValid".equals(name)) {
            if (closed.get()) {
                return false;
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
        if (closed.get()) {
            throw new SQLException("connection closed");
        }
        if ("unwrap".equals(name) && args != null && args.length == 1 && args[0] == Connection.class) {
            return delegate;
        }
        if ("isWrapperFor".equals(name) && args != null && args.length == 1 && args[0] == Connection.class) {
            return true;
        }
        try {
            return method.invoke(delegate, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
