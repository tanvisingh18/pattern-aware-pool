package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.prediction.RiskWeights;
import com.college.pap.routing.EndpointRegistry;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deployable builder: wire real JDBC URLs into the predictive pool.
 *
 * <pre>
 * ConnectionPool pool = PoolBuilder.create()
 *     .primary("primary-db", "jdbc:postgresql://db1:5432/app", "user", "pass")
 *     .backup("backup-db", "jdbc:postgresql://db2:5432/app", "user", "pass")
 *     .highRiskThreshold(0.55)
 *     .buildPredictive();
 * </pre>
 */
public final class PoolBuilder {

    public record EndpointSpec(EndpointId id, String jdbcUrl, String username, String password) {}

    private EndpointSpec primary;
    private final Map<EndpointId, EndpointSpec> backups = new LinkedHashMap<>();
    private final PoolConfig config = new PoolConfig();
    private Clock clock = Clock.systemUTC();
    private int loginTimeoutSeconds = 5;

    private PoolBuilder() {}

    public static PoolBuilder create() {
        return new PoolBuilder();
    }

    public PoolBuilder primary(String id, String jdbcUrl, String username, String password) {
        this.primary = new EndpointSpec(new EndpointId(id), jdbcUrl, username, password);
        return this;
    }

    public PoolBuilder backup(String id, String jdbcUrl, String username, String password) {
        EndpointId endpointId = new EndpointId(id);
        this.backups.put(endpointId, new EndpointSpec(endpointId, jdbcUrl, username, password));
        return this;
    }

    public PoolBuilder highRiskThreshold(double threshold) {
        config.setHighRiskThreshold(threshold);
        return this;
    }

    public PoolBuilder riskWeights(double alpha, double beta, double gamma) {
        config.setWeights(new RiskWeights(alpha, beta, gamma));
        return this;
    }

    public PoolBuilder preWarmLeadMinutes(int minutes) {
        config.setPreWarmLeadMinutes(minutes);
        return this;
    }

    public PoolBuilder warmPoolSize(int size) {
        config.setWarmPoolSize(size);
        return this;
    }

    public PoolBuilder loginTimeoutSeconds(int seconds) {
        this.loginTimeoutSeconds = seconds;
        return this;
    }

    public PoolBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        return this;
    }

    public PoolConfig config() {
        return config;
    }

    public ConnectionPool buildPredictive() {
        return build(true);
    }

    public ConnectionPool buildReactiveBaseline() {
        return build(false);
    }

    private ConnectionPool build(boolean predictive) {
        if (primary == null) {
            throw new IllegalStateException("primary endpoint is required");
        }
        EndpointRegistry registry = new EndpointRegistry(
                primary.id(),
                backups.keySet().stream().toList());

        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary.id(), toJdbc(primary));
        for (EndpointSpec backup : backups.values()) {
            connectors.put(backup.id(), toJdbc(backup));
        }

        ConnectionPool pool = predictive
                ? ConnectionPool.predictive(registry, connectors, config, clock)
                : ConnectionPool.reactiveBaseline(registry, connectors, config, clock);
        pool.start();
        return pool;
    }

    private JdbcEndpointConnector toJdbc(EndpointSpec spec) {
        return new JdbcEndpointConnector(
                spec.id(),
                spec.jdbcUrl(),
                spec.username(),
                spec.password(),
                loginTimeoutSeconds);
    }
}
