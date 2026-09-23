package com.college.pap.demo;

import com.college.pap.model.EndpointId;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Honest comparative experiments: REACTIVE vs CIRCUIT_BREAKER vs PREDICTIVE.
 *
 * <p>Learning drives 7 simulated days of traffic through the live connectors
 * (no history seeding with day-8 answers). Day 8 measurement windows are clean
 * after {@link com.college.pap.monitoring.PoolMetrics#reset()}. Latency is a
 * simulated metric — connectors do not {@code Thread.sleep}.
 */
public final class ExperimentRunner {

    public static final int DEFAULT_SEEDS = 30;
    public static final int LEARNING_DAYS = 7;
    public static final int REQUESTS_PER_HOUR = 12;

    public record ResultRow(
            int seed,
            String mode,
            String scenario,
            int requests,
            double successRate,
            long userFacingConnectFailures,
            long primaryConnectAttempts,
            long backupSelections,
            long preemptiveFailovers,
            long warmHits,
            long recoveryProbesOk,
            long recoveryProbesFail,
            long backgroundPreWarmConnects,
            long physicalConnects,
            double meanCheckoutLatencySimMs,
            double p95CheckoutLatencySimMs) {}

    public record SummaryRow(
            String mode,
            String scenario,
            int n,
            double meanUserFacingFailures,
            double sdUserFacingFailures,
            double meanPrimaryConnectAttempts,
            double sdPrimaryConnectAttempts,
            double meanSuccessRate,
            double sdSuccessRate,
            double meanBackupSelections,
            double sdBackupSelections,
            double meanPreemptiveFailovers,
            double sdPreemptiveFailovers,
            double meanWarmHits,
            double sdWarmHits,
            double meanRecoveryProbesOk,
            double sdRecoveryProbesOk,
            double meanRecoveryProbesFail,
            double sdRecoveryProbesFail,
            double meanBackgroundPreWarmConnects,
            double sdBackgroundPreWarmConnects,
            double meanPhysicalConnects,
            double sdPhysicalConnects,
            double meanLatencySimMs,
            double sdLatencySimMs,
            double meanP95LatencySimMs,
            double sdP95LatencySimMs) {}

    public static void main(String[] args) throws Exception {
        int seeds = DEFAULT_SEEDS;
        if (args.length > 0) {
            seeds = Integer.parseInt(args[0]);
        }

        Path outDir = Path.of("docs", "results");
        Files.createDirectories(outDir);

        writeBeforeFixesBaseline(outDir.resolve("before_fixes.csv"));

        List<ResultRow> rows = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int seed = 1; seed <= seeds; seed++) {
            System.out.println("Seed " + seed + "/" + seeds + "...");
            rows.addAll(runSeed(seed));
        }
        double elapsedSec = (System.nanoTime() - t0) / 1_000_000_000.0;
        System.out.printf(Locale.US, "Completed %d seeds in %.1fs%n", seeds, elapsedSec);

        writeRunsCsv(outDir.resolve("experiment_runs.csv"), rows);
        List<SummaryRow> summary = summarize(rows);
        writeSummaryCsv(outDir.resolve("experiment_summary.csv"), summary);
        writeSummaryMd(outDir.resolve("experiment_summary.md"), summary, seeds, elapsedSec);

        Path submissionResults = Path.of("..", "Java Submission", "results");
        Files.createDirectories(submissionResults);
        for (String name : List.of(
                "before_fixes.csv",
                "experiment_runs.csv",
                "experiment_summary.csv",
                "experiment_summary.md")) {
            Files.copy(
                    outDir.resolve(name),
                    submissionResults.resolve(name),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        printSummary(summary);
    }

    static List<ResultRow> runSeed(int seed) throws Exception {
        List<ResultRow> rows = new ArrayList<>();
        for (String mode : List.of("reactive", "circuit_breaker", "predictive")) {
            rows.addAll(runMode(seed, mode));
        }
        return rows;
    }

    private static List<ResultRow> runMode(int seed, String mode) throws Exception {
        LocalDate startDay = LocalDate.of(2026, 7, 1);
        Instant start = startDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        MutableClock clock = MutableClock.utc(start);

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);

        // Separate RNGs so primary/backup don't consume each other's stream.
        Random primaryRng = new Random(seed * 1000L + 11);
        Random backupRng = new Random(seed * 1000L + 29);

        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, FlakyEndpointConnector.forTests(
                primary, FlakyEndpointConnector.PatternConfig.primaryFlaky(), clock, primaryRng));
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock, backupRng));

        PoolConfig config = experimentConfig();
        ConnectionPool pool = switch (mode) {
            case "predictive" -> ConnectionPool.predictiveManual(registry, connectors, config, clock);
            case "circuit_breaker" -> ConnectionPool.circuitBreaker(registry, connectors, config, clock);
            default -> ConnectionPool.reactiveBaseline(registry, connectors, config, clock);
        };

        try {
            driveLearning(pool, clock, startDay);
            LocalDate measureDay = startDay.plusDays(LEARNING_DAYS);
            // Chronological day-8 windows so MutableClock / circuit state advance forward.
            List<ResultRow> measured = new ArrayList<>();
            measured.add(measure(pool, clock, seed, mode, "morning-healthy",
                    measureDay.atTime(9, 15).toInstant(ZoneOffset.UTC), 100));
            measured.add(measure(pool, clock, seed, mode, "bad-window",
                    measureDay.atTime(14, 20).toInstant(ZoneOffset.UTC), 100));
            measured.add(measure(pool, clock, seed, mode, "evening-stable",
                    measureDay.atTime(18, 0).toInstant(ZoneOffset.UTC), 80));
            measured.add(measureWindowStart(pool, clock, seed, mode, measureDay));
            measured.addAll(measureReuseComparison(seed, mode, measureDay));
            measured.addAll(measurePatternShift(seed, mode, startDay, measureDay));
            return measured;
        } finally {
            pool.close();
        }
    }

    private static PoolConfig experimentConfig() {
        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setReuseEnabled(false);
        config.setHistoryCapacity(8000);
        config.setWarmPoolSize(8);
        config.setPreWarmLeadMinutes(15);
        config.setHotHourMinSamples(5);
        config.setHotHourThreshold(0.50);
        config.setHighRiskThreshold(0.55);
        config.setClusterAvoidRun(3);
        config.setRecoveryProbeSeconds(30);
        config.setAnalyzerPeriodSeconds(3600);
        config.setPreWarmPeriodSeconds(3600);
        return config;
    }

    /**
     * 7 days × 24 hours × 12 requests. Clock advances in 30s steps with
     * {@code backgroundTick()} so predictive pre-warm/recovery can fire.
     * Does <b>not</b> seed FailureHistoryStore with fabricated day-8 answers.
     */
    private static void driveLearning(ConnectionPool pool, MutableClock clock, LocalDate startDay) {
        Instant cursor = startDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = startDay.plusDays(LEARNING_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC);
        int reqThisHour = 0;
        int hourBucket = -1;

        while (cursor.isBefore(end)) {
            clock.set(cursor);
            pool.backgroundTick();

            int hour = cursor.atZone(ZoneOffset.UTC).getHour()
                    + cursor.atZone(ZoneOffset.UTC).getDayOfYear() * 24;
            if (hour != hourBucket) {
                hourBucket = hour;
                reqThisHour = 0;
            }
            // Space 12 requests evenly across the hour (every 5 minutes).
            int minute = cursor.atZone(ZoneOffset.UTC).getMinute();
            int second = cursor.atZone(ZoneOffset.UTC).getSecond();
            if (second == 0 && minute % 5 == 0 && reqThisHour < REQUESTS_PER_HOUR) {
                try (PapConnection ignored = pool.getConnection()) {
                    // learning traffic
                } catch (EndpointConnector.ConnectionFailedException ignored) {
                    // expected in bad windows
                }
                reqThisHour++;
            }
            cursor = cursor.plusSeconds(30);
        }
        pool.analyzer().analyzeAll();
    }

    private static ResultRow measure(
            ConnectionPool pool,
            MutableClock clock,
            int seed,
            String mode,
            String scenario,
            Instant windowStart,
            int requests) {
        // Brief pre-warm opportunity for predictive before bad window.
        if ("predictive".equals(mode) && "bad-window".equals(scenario)) {
            clock.set(windowStart.minus(Duration.ofMinutes(30)));
            pool.backgroundTick();
            clock.set(windowStart.minus(Duration.ofMinutes(10)));
            pool.backgroundTick();
        }
        pool.metrics().reset();

        for (int i = 0; i < requests; i++) {
            clock.set(windowStart.plusSeconds(i));
            // Same background cadence for every mode (fair comparison).
            pool.backgroundTick();
            try (PapConnection ignored = pool.getConnection()) {
                // measured
            } catch (EndpointConnector.ConnectionFailedException ignored) {
                // counted in metrics
            }
        }

        var m = pool.metrics();
        long backupSelections = m.selectedEndpointCounts().getOrDefault("backup-db", 0L);
        return new ResultRow(
                seed,
                mode,
                scenario,
                requests,
                m.successRate(),
                m.userFacingConnectFailures(),
                m.primaryConnectAttempts(),
                backupSelections,
                m.preemptiveFailovers(),
                m.warmHits(),
                m.recoveryProbesOk(),
                m.recoveryProbesFail(),
                m.preWarmEvents(),
                m.physicalConnects(),
                m.averageLatencyMs(),
                m.p95LatencyMs());
    }

    /** 13:55–14:10 @ 1 req/5s; userFacingConnectFailures = failures in 14:00–14:05 only. */
    private static ResultRow measureWindowStart(
            ConnectionPool pool, MutableClock clock, int seed, String mode, LocalDate day) {
        Instant start = day.atTime(13, 55).toInstant(ZoneOffset.UTC);
        pool.metrics().reset();
        long fail1400to1405 = 0;
        int requests = 0;
        for (int sec = 0; sec <= 15 * 60; sec += 5) {
            Instant at = start.plusSeconds(sec);
            clock.set(at);
            pool.backgroundTick();
            requests++;
            try (PapConnection ignored = pool.getConnection()) {
                // ok
            } catch (EndpointConnector.ConnectionFailedException e) {
                var z = at.atZone(ZoneOffset.UTC);
                if (z.getHour() == 14 && z.getMinute() < 5) {
                    fail1400to1405++;
                }
            }
        }
        var m = pool.metrics();
        long backup = m.selectedEndpointCounts().getOrDefault("backup-db", 0L);
        return new ResultRow(seed, mode, "window-start", requests, m.successRate(),
                fail1400to1405, m.primaryConnectAttempts(), backup,
                m.preemptiveFailovers(), m.warmHits(), m.recoveryProbesOk(), m.recoveryProbesFail(),
                m.preWarmEvents(), m.physicalConnects(), m.averageLatencyMs(), m.p95LatencyMs());
    }

    /** Healthy-hour reuse on vs off (1000 requests each). */
    private static List<ResultRow> measureReuseComparison(int seed, String mode, LocalDate day)
            throws Exception {
        List<ResultRow> out = new ArrayList<>();
        for (boolean reuse : List.of(false, true)) {
            MutableClock clock = MutableClock.utc(day.atTime(10, 0).toInstant(ZoneOffset.UTC));
            EndpointId primary = new EndpointId("primary-db");
            EndpointId backup = new EndpointId("backup-db");
            EndpointRegistry registry = EndpointRegistry.of(primary, backup);
            Random pr = new Random(seed * 9100L + (reuse ? 3 : 1));
            Random br = new Random(seed * 9101L + (reuse ? 3 : 1));
            Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
            connectors.put(primary, FlakyEndpointConnector.forTests(
                    primary, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock, pr));
            connectors.put(backup, FlakyEndpointConnector.forTests(
                    backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock, br));
            PoolConfig config = experimentConfig();
            config.setReuseEnabled(reuse);
            ConnectionPool pool = switch (mode) {
                case "predictive" -> ConnectionPool.predictiveManual(registry, connectors, config, clock);
                case "circuit_breaker" -> ConnectionPool.circuitBreaker(registry, connectors, config, clock);
                default -> ConnectionPool.reactiveBaseline(registry, connectors, config, clock);
            };
            try {
                out.add(measure(pool, clock, seed, mode, reuse ? "reuse-on" : "reuse-off",
                        day.atTime(10, 0).toInstant(ZoneOffset.UTC), 1000));
            } finally {
                pool.close();
            }
        }
        return out;
    }


    /**
     * Days 1–7 bad at hour 14; day 8 shifts to hour 15.
     * Reports backup share 14:00–14:30 and connect failures 15:00–15:30.
     */
    private static List<ResultRow> measurePatternShift(
            int seed, String mode, LocalDate startDay, LocalDate measureDay) throws Exception {
        MutableClock clock = MutableClock.utc(startDay.atStartOfDay().toInstant(ZoneOffset.UTC));
        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);
        Random pr = new Random(seed * 7700L + 5);
        Random br = new Random(seed * 7701L + 5);
        FlakyEndpointConnector primaryConn = FlakyEndpointConnector.forTests(
                primary, FlakyEndpointConnector.PatternConfig.primaryFlaky(), clock, pr);
        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, primaryConn);
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock, br));
        PoolConfig config = experimentConfig();
        ConnectionPool pool = switch (mode) {
            case "predictive" -> ConnectionPool.predictiveManual(registry, connectors, config, clock);
            case "circuit_breaker" -> ConnectionPool.circuitBreaker(registry, connectors, config, clock);
            default -> ConnectionPool.reactiveBaseline(registry, connectors, config, clock);
        };
        try {
            driveLearning(pool, clock, startDay);
            primaryConn.setBadHourOverride(15);
            ResultRow at14 = measure(pool, clock, seed, mode, "pattern-shift-h14",
                    measureDay.atTime(14, 0).toInstant(ZoneOffset.UTC), 90);
            ResultRow at15 = measure(pool, clock, seed, mode, "pattern-shift-h15",
                    measureDay.atTime(15, 0).toInstant(ZoneOffset.UTC), 90);
            return List.of(at14, at15);
        } finally {
            pool.close();
        }
    }

    static void writeBeforeFixesBaseline(Path path) throws IOException {
        String csv = """
                # before_fixes.csv — curated baseline removed.
                # See FIXES.md for the measured remediation table.
                note,value
                status,removed_curated_baseline
                see,FIXES.md
                """;
        Files.writeString(path, csv, StandardCharsets.UTF_8);
        System.out.println("Wrote " + path.toAbsolutePath() + " (pointer to FIXES.md)");
    }

    static List<SummaryRow> summarize(List<ResultRow> rows) {
        Map<String, List<ResultRow>> groups = new LinkedHashMap<>();
        for (ResultRow r : rows) {
            String key = r.mode() + "|" + r.scenario();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<SummaryRow> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<ResultRow> g = e.getValue();
            String[] parts = e.getKey().split("\\|", 2);
            out.add(new SummaryRow(
                    parts[0],
                    parts[1],
                    g.size(),
                    mean(g, r -> r.userFacingConnectFailures()),
                    sd(g, r -> r.userFacingConnectFailures()),
                    mean(g, r -> r.primaryConnectAttempts()),
                    sd(g, r -> r.primaryConnectAttempts()),
                    mean(g, r -> r.successRate()),
                    sd(g, r -> r.successRate()),
                    mean(g, r -> r.backupSelections()),
                    sd(g, r -> r.backupSelections()),
                    mean(g, r -> r.preemptiveFailovers()),
                    sd(g, r -> r.preemptiveFailovers()),
                    mean(g, r -> r.warmHits()),
                    sd(g, r -> r.warmHits()),
                    mean(g, r -> r.recoveryProbesOk()),
                    sd(g, r -> r.recoveryProbesOk()),
                    mean(g, r -> r.recoveryProbesFail()),
                    sd(g, r -> r.recoveryProbesFail()),
                    mean(g, r -> r.backgroundPreWarmConnects()),
                    sd(g, r -> r.backgroundPreWarmConnects()),
                    mean(g, r -> r.physicalConnects()),
                    sd(g, r -> r.physicalConnects()),
                    mean(g, r -> r.meanCheckoutLatencySimMs()),
                    sd(g, r -> r.meanCheckoutLatencySimMs()),
                    mean(g, r -> r.p95CheckoutLatencySimMs()),
                    sd(g, r -> r.p95CheckoutLatencySimMs())));
        }
        return out;
    }

    private interface ToDouble {
        double apply(ResultRow r);
    }

    private static double mean(List<ResultRow> rows, ToDouble f) {
        double sum = 0;
        for (ResultRow r : rows) {
            sum += f.apply(r);
        }
        return rows.isEmpty() ? 0 : sum / rows.size();
    }

    private static double sd(List<ResultRow> rows, ToDouble f) {
        if (rows.size() < 2) {
            return 0;
        }
        double m = mean(rows, f);
        double acc = 0;
        for (ResultRow r : rows) {
            double d = f.apply(r) - m;
            acc += d * d;
        }
        return Math.sqrt(acc / (rows.size() - 1));
    }

    private static void writeRunsCsv(Path path, List<ResultRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("seed,mode,scenario,requests,success_rate,user_facing_connect_failures,")
                .append("primary_connect_attempts,backup_selections,preemptive_failovers,warm_hits,")
                .append("recovery_probes_ok,recovery_probes_fail,background_prewarm_connects,")
                .append("physical_connects,mean_checkout_latency_sim_ms,p95_checkout_latency_sim_ms\n");
        for (ResultRow r : rows) {
            sb.append(r.seed()).append(',')
                    .append(r.mode()).append(',')
                    .append(r.scenario()).append(',')
                    .append(r.requests()).append(',')
                    .append(String.format(Locale.US, "%.4f", r.successRate())).append(',')
                    .append(r.userFacingConnectFailures()).append(',')
                    .append(r.primaryConnectAttempts()).append(',')
                    .append(r.backupSelections()).append(',')
                    .append(r.preemptiveFailovers()).append(',')
                    .append(r.warmHits()).append(',')
                    .append(r.recoveryProbesOk()).append(',')
                    .append(r.recoveryProbesFail()).append(',')
                    .append(r.backgroundPreWarmConnects()).append(',')
                    .append(r.physicalConnects()).append(',')
                    .append(String.format(Locale.US, "%.3f", r.meanCheckoutLatencySimMs())).append(',')
                    .append(String.format(Locale.US, "%.3f", r.p95CheckoutLatencySimMs())).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + path.toAbsolutePath());
    }

    private static void writeSummaryCsv(Path path, List<SummaryRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("mode,scenario,n,")
                .append("mean_user_facing_failures,sd_user_facing_failures,")
                .append("mean_primary_connect_attempts,sd_primary_connect_attempts,")
                .append("mean_success_rate,sd_success_rate,")
                .append("mean_backup_selections,sd_backup_selections,")
                .append("mean_preemptive_failovers,sd_preemptive_failovers,")
                .append("mean_warm_hits,sd_warm_hits,")
                .append("mean_recovery_probes_ok,sd_recovery_probes_ok,")
                .append("mean_recovery_probes_fail,sd_recovery_probes_fail,")
                .append("mean_background_prewarm_connects,sd_background_prewarm_connects,")
                .append("mean_physical_connects,sd_physical_connects,")
                .append("mean_latency_sim_ms,sd_latency_sim_ms,")
                .append("mean_p95_latency_sim_ms,sd_p95_latency_sim_ms\n");
        for (SummaryRow r : rows) {
            sb.append(r.mode()).append(',')
                    .append(r.scenario()).append(',')
                    .append(r.n()).append(',')
                    .append(fmt(r.meanUserFacingFailures())).append(',')
                    .append(fmt(r.sdUserFacingFailures())).append(',')
                    .append(fmt(r.meanPrimaryConnectAttempts())).append(',')
                    .append(fmt(r.sdPrimaryConnectAttempts())).append(',')
                    .append(fmt(r.meanSuccessRate())).append(',')
                    .append(fmt(r.sdSuccessRate())).append(',')
                    .append(fmt(r.meanBackupSelections())).append(',')
                    .append(fmt(r.sdBackupSelections())).append(',')
                    .append(fmt(r.meanPreemptiveFailovers())).append(',')
                    .append(fmt(r.sdPreemptiveFailovers())).append(',')
                    .append(fmt(r.meanWarmHits())).append(',')
                    .append(fmt(r.sdWarmHits())).append(',')
                    .append(fmt(r.meanRecoveryProbesOk())).append(',')
                    .append(fmt(r.sdRecoveryProbesOk())).append(',')
                    .append(fmt(r.meanRecoveryProbesFail())).append(',')
                    .append(fmt(r.sdRecoveryProbesFail())).append(',')
                    .append(fmt(r.meanBackgroundPreWarmConnects())).append(',')
                    .append(fmt(r.sdBackgroundPreWarmConnects())).append(',')
                    .append(fmt(r.meanPhysicalConnects())).append(',')
                    .append(fmt(r.sdPhysicalConnects())).append(',')
                    .append(fmt(r.meanLatencySimMs())).append(',')
                    .append(fmt(r.sdLatencySimMs())).append(',')
                    .append(fmt(r.meanP95LatencySimMs())).append(',')
                    .append(fmt(r.sdP95LatencySimMs())).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + path.toAbsolutePath());
    }

    private static void writeSummaryMd(
            Path path, List<SummaryRow> rows, int seeds, double elapsedSec) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Experiment Summary (learn-then-measure)\n\n");
        sb.append("- Seeds: **").append(seeds).append("** (Random seeds 1..").append(seeds).append(")\n");
        sb.append("- Learning: ").append(LEARNING_DAYS)
                .append(" days × 24h × ").append(REQUESTS_PER_HOUR)
                .append(" req/h via live FlakyEndpointConnector (no answer-seeding)\n");
        sb.append("- Measurement: day 8 windows; routing experiments use `reuseEnabled=false`; ")
                .append("latency = simulated ms (no sleep)\n");
        sb.append("- Modes: reactive, circuit_breaker (open after 3 primary fails / 60s / half-open probe), predictive\n");
        sb.append("- recoveryProbeSeconds=30; backgroundTick cadence identical across modes\n");
        sb.append(String.format(Locale.US, "- Wall time: %.1fs%n%n", elapsedSec));
        sb.append("| Mode | Scenario | n | User-Facing Failures | Primary Connects | Success | ")
                .append("Probes ok/fail | Prewarm | Physical | Preemptive FO | Warm | Backup | Latency mean/p95 |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (SummaryRow r : rows) {
            sb.append("| ").append(r.mode())
                    .append(" | ").append(r.scenario())
                    .append(" | ").append(r.n())
                    .append(" | ").append(String.format(Locale.US, "%.1f±%.1f",
                            r.meanUserFacingFailures(), r.sdUserFacingFailures()))
                    .append(" | ").append(String.format(Locale.US, "%.1f±%.1f",
                            r.meanPrimaryConnectAttempts(), r.sdPrimaryConnectAttempts()))
                    .append(" | ").append(String.format(Locale.US, "%.1f%%±%.1f",
                            r.meanSuccessRate() * 100, r.sdSuccessRate() * 100))
                    .append(" | ").append(String.format(Locale.US, "%.1f±%.1f / %.1f±%.1f",
                            r.meanRecoveryProbesOk(), r.sdRecoveryProbesOk(),
                            r.meanRecoveryProbesFail(), r.sdRecoveryProbesFail()))
                    .append(" | ").append(String.format(Locale.US, "%.1f±%.1f",
                            r.meanBackgroundPreWarmConnects(), r.sdBackgroundPreWarmConnects()))
                    .append(" | ").append(String.format(Locale.US, "%.1f±%.1f",
                            r.meanPhysicalConnects(), r.sdPhysicalConnects()))
                    .append(" | ").append(String.format(Locale.US, "%.1f±%.1f",
                            r.meanPreemptiveFailovers(), r.sdPreemptiveFailovers()))
                    .append(" | ").append(String.format(Locale.US, "%.1f±%.1f",
                            r.meanWarmHits(), r.sdWarmHits()))
                    .append(" | ").append(String.format(Locale.US, "%.1f±%.1f",
                            r.meanBackupSelections(), r.sdBackupSelections()))
                    .append(" | ").append(String.format(Locale.US, "%.0f±%.0f / %.0f±%.0f",
                            r.meanLatencySimMs(), r.sdLatencySimMs(),
                            r.meanP95LatencySimMs(), r.sdP95LatencySimMs()))
                    .append(" |\n");
        }
        sb.append("\nSee `FIXES.md` for the remediation history. Numbers here are measured mean±sd.\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + path.toAbsolutePath());
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static void printSummary(List<SummaryRow> rows) {
        System.out.println("\n=== Experiment Summary ===");
        for (SummaryRow r : rows) {
            System.out.printf(Locale.US,
                    "%-16s %-18s uf_fail=%.1f±%.1f primary=%.1f±%.1f success=%.1f%% "
                            + "probes=%.1f/%.1f prewarm=%.1f physical=%.1f failover=%.1f warm=%.1f%n",
                    r.mode(),
                    r.scenario(),
                    r.meanUserFacingFailures(),
                    r.sdUserFacingFailures(),
                    r.meanPrimaryConnectAttempts(),
                    r.sdPrimaryConnectAttempts(),
                    r.meanSuccessRate() * 100,
                    r.meanRecoveryProbesOk(),
                    r.meanRecoveryProbesFail(),
                    r.meanBackgroundPreWarmConnects(),
                    r.meanPhysicalConnects(),
                    r.meanPreemptiveFailovers(),
                    r.meanWarmHits());
        }
    }
}
