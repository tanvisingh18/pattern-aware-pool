package com.college.pap.demo;

import com.college.pap.lifecycle.PoolLifecycle;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.pool.ConnectionPool;
import com.college.pap.pool.EndpointConnector;
import com.college.pap.pool.FlakyEndpointConnector;
import com.college.pap.pool.PapConnection;
import com.college.pap.pool.PoolConfig;
import com.college.pap.prediction.RiskScore;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.routing.RoutingDecision;
import com.college.pap.util.MutableClock;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** End-to-end demo of the complete three-layer pool for Review-2. */
public final class FullSystemDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Pattern-Aware Pool — Full System Demo (Layers 1–3) ===\n");

        LocalDate day = LocalDate.of(2026, 7, 26);
        MutableClock clock = MutableClock.utc(day.atTime(10, 0).toInstant(ZoneOffset.UTC));

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);

        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, FlakyEndpointConnector.forTests(
                primary, FlakyEndpointConnector.PatternConfig.primaryFlaky(), clock, new java.util.Random(42)));
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock, new java.util.Random(43)));

        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setReuseEnabled(false);
        config.setPreWarmLeadMinutes(15);
        config.setWarmPoolSize(5);
        config.setAnalyzerPeriodSeconds(3600);
        config.setPreWarmPeriodSeconds(3600);
        config.setRecoveryProbeSeconds(5);

        try (ConnectionPool pool = ConnectionPool.predictiveManual(registry, connectors, config, clock)) {
            PoolLifecycle lifecycle = new PoolLifecycle(pool, false, 1099);
            lifecycle.init();

            System.out.println("1) Learning phase across 24h...");
            for (int h = 0; h < 24; h++) {
                for (int i = 0; i < 12; i++) {
                    clock.set(day.atTime(h, Math.min(59, i * 5)).toInstant(ZoneOffset.UTC));
                    pool.backgroundTick();
                    try (PapConnection ignored = pool.getConnection()) {
                        // learning
                    } catch (Exception ignored) {
                        // expected in bad windows
                    }
                }
            }
            pool.analyzer().analyzeAll();
            printProfiles(pool);

            System.out.println("\n2) Pre-warm check at 13:50 (before bad window)...");
            clock.set(day.atTime(13, 50).toInstant(ZoneOffset.UTC));
            pool.backgroundTick();
            System.out.println("   Warm pool size : " + pool.preWarmer().warmPool().size());
            System.out.println("   Pre-warm events: " + pool.metrics().preWarmEvents());

            System.out.println("\n3) Checkout at 09:15 (healthy hour)...");
            clock.set(day.atTime(9, 15).toInstant(ZoneOffset.UTC));
            pool.backgroundTick();
            demoCheckout(pool, primary);

            System.out.println("\n4) Checkout at 14:20 (predicted bad window)...");
            clock.set(day.atTime(14, 20).toInstant(ZoneOffset.UTC));
            pool.backgroundTick();
            for (int i = 0; i < 5; i++) {
                demoCheckout(pool, primary);
            }

            System.out.println("\n5) Metrics snapshot:");
            System.out.println("   " + pool.metrics().snapshot());
            System.out.println("   Monitoring ThreadGroup: "
                    + pool.threadFactory().threadGroup().getName());

            lifecycle.destroy();
        }

        System.out.println("\nDemo complete. Next: mvn -q exec:java -Ddemo.mainClass=com.college.pap.demo.ExperimentRunner");
    }

    private static void printProfiles(ConnectionPool pool) {
        for (EndpointId id : pool.registry().all()) {
            EndpointRiskProfile p = pool.analyzer().getProfile(id);
            if (p == null) {
                continue;
            }
            System.out.printf("   %s recentFail=%.0f%% hour14=%.0f%% cluster=%s%n",
                    id,
                    p.recentFailureRate() * 100,
                    p.failureRateAtHour(14) * 100,
                    p.clusterState());
        }
    }

    private static void demoCheckout(ConnectionPool pool, EndpointId requested) {
        try (PapConnection c = pool.getConnection(requested)) {
            // Print the decision that produced THIS connection — do not recompute.
            RoutingDecision decision = c.routingDecision().orElse(null);
            if (decision != null) {
                for (RiskScore score : decision.allScores().values()) {
                    System.out.println("   " + score);
                }
                System.out.println("   Selected: " + c.endpointId()
                        + " preWarmed=" + c.isPreWarmed()
                        + " reason=" + decision.reason()
                        + " trigger=" + decision.trigger()
                        + " risk=" + String.format("%.2f", decision.selectedScore().score()));
            } else {
                System.out.println("   Selected: " + c.endpointId()
                        + " preWarmed=" + c.isPreWarmed()
                        + " (no routing decision attached)");
            }
        } catch (Exception e) {
            System.out.println("   Checkout failed: " + e.getMessage());
        }
    }
}
