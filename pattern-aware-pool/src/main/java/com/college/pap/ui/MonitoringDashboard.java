package com.college.pap.ui;

import com.college.pap.monitoring.PoolMetrics;
import com.college.pap.pool.ConnectionPool;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Swing monitoring dashboard (JavaFX-equivalent syllabus deliverable that runs
 * without extra OpenJFX native deps on JDK 25). Shows live metrics + risk text.
 */
public final class MonitoringDashboard {

    private final Supplier<ConnectionPool> poolSupplier;

    public MonitoringDashboard(Supplier<ConnectionPool> poolSupplier) {
        this.poolSupplier = Objects.requireNonNull(poolSupplier);
    }

    public void show() {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Pattern-Aware Pool Monitor");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(720, 480);

            JTextArea area = new JTextArea();
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

            JLabel title = new JLabel(" Live pool metrics / risk / pre-warm status ");
            JButton refresh = new JButton("Refresh now");

            Runnable updater = () -> {
                ConnectionPool pool = poolSupplier.get();
                if (pool == null) {
                    area.setText("Pool not available");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(pool.metrics().snapshot()).append("\n\n");
                sb.append("Warm pool size: ").append(pool.preWarmer().warmPool().size()).append('\n');
                sb.append("ThreadGroup: ").append(pool.threadFactory().threadGroup().getName()).append("\n\n");
                sb.append("Endpoint profiles:\n");
                pool.analyzer().getAllProfiles().forEach((id, profile) ->
                        sb.append(" - ").append(profile).append('\n'));
                area.setText(sb.toString());
            };

            refresh.addActionListener(e -> updater.run());
            Timer timer = new Timer(1000, e -> updater.run());
            timer.start();

            JPanel top = new JPanel(new BorderLayout());
            top.add(title, BorderLayout.CENTER);
            top.add(refresh, BorderLayout.EAST);

            frame.add(top, BorderLayout.NORTH);
            frame.add(new JScrollPane(area), BorderLayout.CENTER);
            updater.run();
            frame.setVisible(true);
        });
    }

    /** Standalone launcher that runs a short live demo behind the dashboard. */
    public static void main(String[] args) throws Exception {
        // Reuse FullSystemDemo setup in a background thread and show dashboard.
        com.college.pap.util.MutableClock clock = com.college.pap.util.MutableClock.utc(
                java.time.LocalDate.of(2026, 7, 26).atTime(14, 0).toInstant(java.time.ZoneOffset.UTC));
        com.college.pap.model.EndpointId primary = new com.college.pap.model.EndpointId("primary-db");
        com.college.pap.model.EndpointId backup = new com.college.pap.model.EndpointId("backup-db");
        var registry = com.college.pap.routing.EndpointRegistry.of(primary, backup);
        java.util.Map<com.college.pap.model.EndpointId, com.college.pap.pool.EndpointConnector> connectors =
                new java.util.LinkedHashMap<>();
        connectors.put(primary, FlakyFrom(primary, clock));
        connectors.put(backup, FlakyBackup(backup, clock));
        PoolConfigSafe config = new PoolConfigSafe();
        ConnectionPool pool = ConnectionPool.predictive(registry, connectors, config.config, clock);
        pool.start();

        new MonitoringDashboard(() -> pool).show();

        // Keep generating traffic so the dashboard moves.
        Thread traffic = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                try (var c = pool.getConnection()) {
                    Thread.sleep(200);
                } catch (Exception ignored) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "dashboard-traffic");
        traffic.setDaemon(true);
        traffic.start();
    }

    private static com.college.pap.pool.EndpointConnector FlakyFrom(
            com.college.pap.model.EndpointId id, com.college.pap.util.MutableClock clock) {
        return com.college.pap.pool.FlakyEndpointConnector.forTests(
                id, com.college.pap.pool.FlakyEndpointConnector.PatternConfig.primaryFlaky(), clock);
    }

    private static com.college.pap.pool.EndpointConnector FlakyBackup(
            com.college.pap.model.EndpointId id, com.college.pap.util.MutableClock clock) {
        return com.college.pap.pool.FlakyEndpointConnector.forTests(
                id, com.college.pap.pool.FlakyEndpointConnector.PatternConfig.healthyBackup(), clock);
    }

    private static final class PoolConfigSafe {
        final com.college.pap.pool.PoolConfig config = new com.college.pap.pool.PoolConfig();
    }
}
