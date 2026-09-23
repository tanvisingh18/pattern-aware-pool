package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleConnectionPoolTest {

    @Test
    void checkoutCloseCyclesReusePhysicalConnections() throws Exception {
        Path dir = Path.of("target", "test-db");
        Files.createDirectories(dir);
        String url = "jdbc:h2:file:" + dir.toAbsolutePath() + "/idle-pool-test;DB_CLOSE_DELAY=-1";

        EndpointId id = new EndpointId("h2-pool");
        AtomicInteger creates = new AtomicInteger();
        EndpointConnector counting = new CountingConnector(
                new JdbcEndpointConnector(id, url, "sa", ""),
                creates);

        IdleConnectionPool pool = new IdleConnectionPool(
                counting, new ConnectionValidator(), 10);

        try {
            for (int i = 0; i < 10; i++) {
                PapConnection conn = pool.acquire();
                assertTrue(conn.isOpen());
                assertTrue(conn.unwrapJdbc().isPresent());
                conn.close();
            }
            assertTrue(creates.get() < 10,
                    "expected connection reuse, but saw " + creates.get() + " physical creates");
            assertTrue(creates.get() >= 1);
            assertTrue(pool.idleCount() >= 1);
        } finally {
            pool.drainAndClose();
        }
    }

    @Test
    void secondCheckoutReusesSamePhysicalHandle() throws Exception {
        String url = "jdbc:h2:mem:idle-reuse-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        EndpointId id = new EndpointId("h2-mem");
        AtomicInteger creates = new AtomicInteger();
        IdleConnectionPool pool = new IdleConnectionPool(
                new CountingConnector(new JdbcEndpointConnector(id, url, "sa", ""), creates),
                new ConnectionValidator(),
                5);

        try {
            PapConnection first = pool.acquire();
            Object handle = first.nativeHandle();
            first.close();

            PapConnection second = pool.acquire();
            assertTrue(handle == second.nativeHandle(), "second checkout should reuse idle connection");
            second.close();

            assertTrue(creates.get() == 1, "expected a single physical create, got " + creates.get());
        } finally {
            pool.drainAndClose();
        }
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
