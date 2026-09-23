package com.college.pap.demo;

import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.pool.ConnectionPool;
import com.college.pap.pool.EndpointConnector;
import com.college.pap.pool.FlakyEndpointConnector;
import com.college.pap.pool.PapConnection;
import com.college.pap.pool.PoolConfig;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.util.MutableClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Controlled experiments: Reactive baseline vs Predictive pool.
 * Seeds Layer-1 history directly, then measures a clean request window.
 */
public final class ExperimentRunner {

    public record ResultRow(
            String mode,
            String scenario,
            int requests,
            double successRate,
            double avgLatencyMs,
            long preemptiveFailovers,
            long warmHits,
            long checkoutFailures,
            long connectFailures,
            long backupSelections) {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of("docs", "results");
        Files.createDirectories(outDir);

        List<ResultRow> rows = new ArrayList<>();
        rows.addAll(runScenario("morning-healthy", 9, 15, 100));
        rows.addAll(runScenario("afternoon-bad-window", 14, 20, 100));
        rows.addAll(runScenario("evening-stable", 18, 0, 80));

        writeCsv(outDir.resolve("experiment_results.csv"), rows);
        writeMarkdown(outDir.resolve("experiment_results.md"), rows);
        printSummary(rows);
    }

    private static List<ResultRow> runScenario(String name, int hour, int minute, int requests)
            throws Exception {
        return List.of(
                runOnce("reactive", name, hour, minute, requests, false),
                runOnce("predictive", name, hour, minute, requests, true));
    }

    private static ResultRow runOnce(
            String mode,
            String scenario,
            int hour,
            int minute,
            int requests,
            boolean predictive) throws Exception {
        LocalDate day = LocalDate.of(2026, 7, 26);
        Instant start = LocalDateTime.of(2026, 7, 26, hour, minute).toInstant(ZoneOffset.UTC);
        MutableClock clock = MutableClock.utc(start);

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);

        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, FlakyEndpointConnector.forTests(
                primary, FlakyEndpointConnector.PatternConfig.primaryFlaky(), clock));
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock));

        PoolConfig config = new PoolConfig();
        config.setAnalyzerPeriodSeconds(3600);
        config.setPreWarmPeriodSeconds(3600);
        config.setPreWarmLeadMinutes(15);
        config.setWarmPoolSize(8);

        ConnectionPool pool = predictive
                ? ConnectionPool.predictive(registry, connectors, config, clock)
                : ConnectionPool.reactiveBaseline(registry, connectors, config, clock);

        seedHistory(pool, primary, backup, day);
        pool.analyzer().analyzeAll();

        if (predictive && hour == 14) {
            clock.set(day.atTime(13, 50).toInstant(ZoneOffset.UTC));
            pool.preWarmer().tick();
            clock.set(start);
        }

        // Important: do not count learning/pre-warm in measured metrics.
        pool.metrics().reset();

        for (int i = 0; i < requests; i++) {
            clock.set(start.plusSeconds(i));
            try (PapConnection ignored = pool.getConnection()) {
                // ok
            } catch (EndpointConnector.ConnectionFailedException ignored) {
                // counted
            }
        }

        var m = pool.metrics();
        long backupSelections = m.selectedEndpointCounts().getOrDefault("backup-db", 0L);
        ResultRow row = new ResultRow(
                mode,
                scenario,
                requests,
                m.successRate(),
                m.averageLatencyMs(),
                m.preemptiveFailovers(),
                m.warmHits(),
                m.failedCheckouts(),
                m.connectFailures(),
                backupSelections);
        pool.close();
        return row;
    }

    /** Directly write patterned history so predictive mode has something to learn. */
    private static void seedHistory(
            ConnectionPool pool,
            EndpointId primary,
            EndpointId backup,
            LocalDate day) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int hour = 0; hour < 24; hour++) {
            for (int i = 0; i < 12; i++) {
                Instant ts = day.atTime(hour, Math.min(59, i * 4)).toInstant(ZoneOffset.UTC);
                boolean fail = hour == 14 ? rng.nextDouble() < 0.85 : rng.nextDouble() < 0.03;
                if (fail) {
                    pool.historyStore().record(ConnectionAttempt.failure(
                            primary, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 80));
                } else {
                    pool.historyStore().record(ConnectionAttempt.success(primary, ts, 12));
                }
                pool.historyStore().record(ConnectionAttempt.success(backup, ts, 10));
            }
        }
        // Historical burst that has already ended (success breaks the cluster).
        Instant burst = day.atTime(14, 5).toInstant(ZoneOffset.UTC);
        for (int i = 0; i < 4; i++) {
            pool.historyStore().record(ConnectionAttempt.failure(
                    primary,
                    burst.plusSeconds(i),
                    AttemptOutcome.FAILURE,
                    FailureType.NETWORK_UNREACHABLE,
                    40));
        }
        pool.historyStore().record(ConnectionAttempt.success(primary, burst.plusSeconds(10), 8));
        // Fresh healthy samples near end of day so recent-rate isn't stuck high.
        for (int i = 0; i < 15; i++) {
            Instant ts = day.atTime(16, i * 2).toInstant(ZoneOffset.UTC);
            pool.historyStore().record(ConnectionAttempt.success(primary, ts, 8));
            pool.historyStore().record(ConnectionAttempt.success(backup, ts, 8));
        }
    }

    private static void writeCsv(Path path, List<ResultRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("mode,scenario,requests,success_rate,avg_latency_ms,preemptive_failovers,warm_hits,checkout_failures,connect_failures,backup_selections\n");
        for (ResultRow r : rows) {
            sb.append(r.mode()).append(',')
                    .append(r.scenario()).append(',')
                    .append(r.requests()).append(',')
                    .append(String.format(Locale.US, "%.4f", r.successRate())).append(',')
                    .append(String.format(Locale.US, "%.3f", r.avgLatencyMs())).append(',')
                    .append(r.preemptiveFailovers()).append(',')
                    .append(r.warmHits()).append(',')
                    .append(r.checkoutFailures()).append(',')
                    .append(r.connectFailures()).append(',')
                    .append(r.backupSelections()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + path.toAbsolutePath());
    }

    private static void writeMarkdown(Path path, List<ResultRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Experiment Results\n\n");
        sb.append("| Mode | Scenario | Req | Success | Connect Failures | Preemptive Failovers | Warm Hits | Backup Selections |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (ResultRow r : rows) {
            sb.append("| ").append(r.mode())
                    .append(" | ").append(r.scenario())
                    .append(" | ").append(r.requests())
                    .append(" | ").append(String.format(Locale.US, "%.1f%%", r.successRate() * 100))
                    .append(" | ").append(r.connectFailures())
                    .append(" | ").append(r.preemptiveFailovers())
                    .append(" | ").append(r.warmHits())
                    .append(" | ").append(r.backupSelections())
                    .append(" |\n");
        }
        sb.append("\n**Reading the table:** lower *Connect Failures* and higher *Preemptive Failovers*/*Warm Hits* ")
                .append("in the afternoon window show predictive avoidance working. ")
                .append("Reactive mode still completes many requests via post-failure failover, ")
                .append("but pays primary connect failures first.\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + path.toAbsolutePath());
    }

    private static void printSummary(List<ResultRow> rows) {
        System.out.println("\n=== Experiment Summary ===");
        for (ResultRow r : rows) {
            System.out.printf(
                    "%-11s %-22s success=%.1f%% connectFail=%d failover=%d warm=%d backupSel=%d%n",
                    r.mode(),
                    r.scenario(),
                    r.successRate() * 100,
                    r.connectFailures(),
                    r.preemptiveFailovers(),
                    r.warmHits(),
                    r.backupSelections());
        }
    }
}
