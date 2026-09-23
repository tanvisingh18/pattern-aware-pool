package com.college.pap.analysis;

import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects threshold-triggered reset periods: success-streak lengths that end in
 * failure (streaks ≥ {@value #MIN_STREAK}) whose last three values cluster around
 * a median period {@code P} (±2). When the live streak reaches {@code P - 1},
 * routing may prefer backup before the next physical connect.
 */
public final class ResetPatternDetector {

    public static final int MIN_STREAK = 10;
    public static final int TOLERANCE = 2;
    private static final int KEEP_STREAKS = 8;

    private final ConcurrentHashMap<EndpointId, EndpointResetState> states = new ConcurrentHashMap<>();

    public void observe(ConnectionAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        observe(attempt.endpointId(), !attempt.isFailure());
    }

    public void observe(EndpointId endpointId, boolean success) {
        Objects.requireNonNull(endpointId, "endpointId");
        states.computeIfAbsent(endpointId, id -> new EndpointResetState()).observe(success);
    }

    public OptionalInt detectedPeriod(EndpointId endpointId) {
        EndpointResetState state = states.get(endpointId);
        if (state == null || state.detectedPeriod < 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(state.detectedPeriod);
    }

    public int currentSuccessStreak(EndpointId endpointId) {
        EndpointResetState state = states.get(endpointId);
        return state == null ? 0 : state.currentSuccessStreak;
    }

    /** True when a stable period P is known, streak ≥ P−1, and prediction not yet consumed. */
    public boolean preferBackup(EndpointId endpointId) {
        EndpointResetState state = states.get(endpointId);
        return state != null && state.preferBackup();
    }

    /**
     * Call after a {@code RESET_PREDICTED} failover so avoidance applies to the
     * next physical connect only, not forever while the streak stays frozen.
     */
    public void consumePrediction(EndpointId endpointId) {
        EndpointResetState state = states.get(endpointId);
        if (state != null) {
            state.consumePrediction();
        }
    }

    public List<Integer> completedStreaks(EndpointId endpointId) {
        EndpointResetState state = states.get(endpointId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return List.copyOf(state.completedStreaks);
        }
    }

    private static final class EndpointResetState {
        private final Deque<Integer> completedStreaks = new ArrayDeque<>();
        private int currentSuccessStreak;
        private int detectedPeriod = -1;
        private boolean predictionConsumed;

        synchronized void observe(boolean success) {
            if (success) {
                currentSuccessStreak++;
                return;
            }
            if (currentSuccessStreak >= MIN_STREAK) {
                completedStreaks.addLast(currentSuccessStreak);
                while (completedStreaks.size() > KEEP_STREAKS) {
                    completedStreaks.removeFirst();
                }
                recomputePeriod();
            }
            currentSuccessStreak = 0;
            predictionConsumed = false;
        }

        synchronized boolean preferBackup() {
            return detectedPeriod > 0
                    && !predictionConsumed
                    && currentSuccessStreak >= detectedPeriod - 1;
        }

        synchronized void consumePrediction() {
            predictionConsumed = true;
        }

        private void recomputePeriod() {
            if (completedStreaks.size() < 3) {
                detectedPeriod = -1;
                return;
            }
            List<Integer> last3 = new ArrayList<>(3);
            var it = completedStreaks.descendingIterator();
            while (it.hasNext() && last3.size() < 3) {
                last3.add(it.next());
            }
            int median = medianOfThree(last3.get(0), last3.get(1), last3.get(2));
            boolean consistent = true;
            for (int length : last3) {
                if (Math.abs(length - median) > TOLERANCE) {
                    consistent = false;
                    break;
                }
            }
            detectedPeriod = consistent ? median : -1;
        }

        private static int medianOfThree(int a, int b, int c) {
            if (a > b) {
                int t = a;
                a = b;
                b = t;
            }
            if (b > c) {
                int t = b;
                b = c;
                c = t;
            }
            if (a > b) {
                int t = a;
                a = b;
                b = t;
            }
            return b;
        }
    }
}
