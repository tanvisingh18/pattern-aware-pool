package com.college.pap.history;

import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureHistoryStoreTest {

    @Test
    void dropsOldestWhenCapacityExceeded() {
        FailureHistoryStore store = new FailureHistoryStore(3);
        EndpointId id = new EndpointId("primary-db");
        Instant t0 = Instant.parse("2026-07-26T10:00:00Z");

        store.record(ConnectionAttempt.success(id, t0, 1));
        store.record(ConnectionAttempt.success(id, t0.plusSeconds(1), 1));
        store.record(ConnectionAttempt.success(id, t0.plusSeconds(2), 1));
        store.record(ConnectionAttempt.success(id, t0.plusSeconds(3), 1));

        assertEquals(3, store.size(id));
        assertEquals(t0.plusSeconds(1), store.getHistory(id).get(0).timestamp());
    }
}
