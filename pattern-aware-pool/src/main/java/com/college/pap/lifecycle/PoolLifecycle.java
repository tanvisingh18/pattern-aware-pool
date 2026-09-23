package com.college.pap.lifecycle;

import com.college.pap.pool.ConnectionPool;
import com.college.pap.rmi.PoolManagementRemote;
import com.college.pap.rmi.PoolManagementService;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Objects;

/**
 * Mirrors Spring @PostConstruct / @PreDestroy lifecycle for the pool + optional RMI export.
 * (A real @Component wrapper can delegate to this same class.)
 */
public final class PoolLifecycle {
    public static final String RMI_NAME = "PatternAwarePoolManagement";

    private final ConnectionPool pool;
    private final boolean exportRmi;
    private final int rmiPort;
    private Registry registry;
    private PoolManagementService remote;

    public PoolLifecycle(ConnectionPool pool, boolean exportRmi, int rmiPort) {
        this.pool = Objects.requireNonNull(pool);
        this.exportRmi = exportRmi;
        this.rmiPort = rmiPort;
    }

    /** @PostConstruct equivalent */
    public void init() throws Exception {
        pool.start();
        if (exportRmi) {
            registry = LocateRegistry.createRegistry(rmiPort);
            remote = new PoolManagementService(pool);
            registry.rebind(RMI_NAME, remote);
        }
    }

    /** @PreDestroy equivalent */
    public void destroy() throws Exception {
        if (registry != null) {
            try {
                registry.unbind(RMI_NAME);
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (remote != null) {
            try {
                java.rmi.server.UnicastRemoteObject.unexportObject(remote, true);
            } catch (Exception ignored) {
                // ignore
            }
        }
        pool.shutdown();
    }

    public PoolManagementRemote remote() {
        return remote;
    }
}
