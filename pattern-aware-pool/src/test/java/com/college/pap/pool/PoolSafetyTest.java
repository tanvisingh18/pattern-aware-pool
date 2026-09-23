package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.routing.EndpointRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-H2 pool safety: rollback on release, closed proxy, borrow timeout,
 * concurrency cap, and broken-idle eviction.
 */
class PoolSafetyTest {

    @Test
    void openTransactionRolledBackOnRelease() throws Exception {
        String url = "jdbc:h2:mem:tx-rollback-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        EndpointId id = new EndpointId("h2-tx");
        try (ConnectionPool pool = singleEndpointPool(id, url, 5, 3000, 5000)) {
            PooledDataSource ds = new PooledDataSource(pool);

            try (Connection setup = ds.getConnection();
                 Statement st = setup.createStatement()) {
                st.execute("CREATE TABLE t(id INT PRIMARY KEY)");
            }

            try (Connection a = ds.getConnection()) {
                a.setAutoCommit(false);
                try (Statement st = a.createStatement()) {
                    st.executeUpdate("INSERT INTO t VALUES (1)");
                }
                // leave transaction open — close returns to pool and must roll back
            }

            try (Connection b = ds.getConnection()) {
                assertTrue(b.getAutoCommit(), "borrower B must see autoCommit=true");
                try (Statement st = b.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "uncommitted insert must be rolled back");
                }
            }
        }
    }

    @Test
    void statementOnClosedProxyThrowsSqlException() throws Exception {
        String url = "jdbc:h2:mem:closed-proxy-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        EndpointId id = new EndpointId("h2-closed");
        try (ConnectionPool pool = singleEndpointPool(id, url, 5, 3000, 5000)) {
            PooledDataSource ds = new PooledDataSource(pool);
            Connection c = ds.getConnection();
            c.close();
            c.close(); // idempotent
            SQLException ex = assertThrows(SQLException.class, () -> c.createStatement());
            assertTrue(ex.getMessage().toLowerCase().contains("closed"));
            assertTrue(c.isClosed());
        }
    }

    @Test
    void borrowTimeoutThrowsWithinWindow() throws Exception {
        String url = "jdbc:h2:mem:borrow-to-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        EndpointId id = new EndpointId("h2-to");
        IdleConnectionPool idle = new IdleConnectionPool(
                new JdbcEndpointConnector(id, url, "sa", ""),
                new ConnectionValidator(),
                1,
                () -> true,
                () -> 300L,
                () -> 5000L,
                null);
        try {
            PapConnection held = idle.acquire();
            long start = System.nanoTime();
            EndpointConnector.ConnectionFailedException ex = assertThrows(
                    EndpointConnector.ConnectionFailedException.class,
                    idle::acquire);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(ex.isPoolExhausted());
            assertTrue(ex.getMessage().toLowerCase().contains("pool exhausted"));
            assertTrue(elapsedMs >= 300 && elapsedMs <= 1000,
                    "expected timeout in 300–1000 ms, got " + elapsedMs);
            held.close();
        } finally {
            idle.drainAndClose();
        }
    }

    @Test
    void concurrentBorrowsNeverExceedMaxPoolSize() throws Exception {
        String url = "jdbc:h2:mem:conc-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        EndpointId id = new EndpointId("h2-conc");
        int max = 5;
        try (ConnectionPool pool = singleEndpointPool(id, url, max, 5000, 5000)) {
            AtomicLong maxOpen = new AtomicLong();
            AtomicInteger completed = new AtomicInteger();
            int threads = 20;
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> {
                    try {
                        start.await();
                        try (PapConnection c = pool.getConnection()) {
                            long open = pool.idlePool(id).openPhysicalCount();
                            maxOpen.accumulateAndGet(open, Math::max);
                            Thread.sleep(200);
                            completed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        fail(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            exec.shutdown();
            assertEquals(threads, completed.get());
            assertTrue(maxOpen.get() <= max,
                    "open physical peaked at " + maxOpen.get() + " > " + max);
        }
    }

    @Test
    void brokenIdleConnectionEvictedAndReplaced() throws Exception {
        String url = "jdbc:h2:mem:broken-idle-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        EndpointId id = new EndpointId("h2-broken");
        AtomicInteger creates = new AtomicInteger();
        EndpointConnector counting = new CountingConnector(
                new JdbcEndpointConnector(id, url, "sa", ""), creates);

        IdleConnectionPool idle = new IdleConnectionPool(
                counting,
                new ConnectionValidator(),
                5,
                () -> true,
                () -> 3000L,
                () -> 0L, // always validate on borrow
                null);
        try {
            PapConnection first = idle.acquire();
            Connection jdbc = first.unwrapJdbc().orElseThrow();
            first.close();
            assertEquals(1, creates.get());
            assertEquals(1, idle.idleCount());

            // Close physical JDBC behind the pool's back
            jdbc.close();

            PapConnection second = idle.acquire();
            assertTrue(second.unwrapJdbc().orElseThrow().isValid(1));
            assertTrue(creates.get() >= 2, "expected replacement create, got " + creates.get());
            second.close();
        } finally {
            idle.drainAndClose();
        }
    }

    @Test
    void getConnectionWithCredentialsUnsupported() throws Exception {
        String url = "jdbc:h2:mem:cred-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        EndpointId id = new EndpointId("h2-cred");
        try (ConnectionPool pool = singleEndpointPool(id, url, 2, 3000, 5000)) {
            PooledDataSource ds = new PooledDataSource(pool);
            assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                    () -> ds.getConnection("u", "p"));
        }
    }

    private static ConnectionPool singleEndpointPool(
            EndpointId id,
            String url,
            int maxPool,
            long borrowTimeoutMs,
            long validationIdleMs) {
        PoolConfig config = new PoolConfig();
        config.setMaxPoolSizePerEndpoint(maxPool);
        config.setBorrowTimeoutMillis(borrowTimeoutMs);
        config.setValidationIdleMillis(validationIdleMs);
        config.setReuseEnabled(true);
        EndpointRegistry registry = EndpointRegistry.of(id);
        Map<EndpointId, EndpointConnector> connectors = Map.of(
                id, new JdbcEndpointConnector(id, url, "sa", ""));
        return ConnectionPool.reactiveBaseline(registry, connectors, config, java.time.Clock.systemUTC());
    }

    private static final class CountingConnector implements EndpointConnector {
        private final EndpointConnector delegate;
        private final AtomicInteger creates;

        CountingConnector(EndpointConnector delegate, AtomicInteger creates) {
            this.delegate = delegate;
            this.creates = creates;
        }

        @Override
        public EndpointId endpointId() {
            return delegate.endpointId();
        }

        @Override
        public PapConnection connect() throws ConnectionFailedException {
            creates.incrementAndGet();
            return delegate.connect();
        }
    }
}
