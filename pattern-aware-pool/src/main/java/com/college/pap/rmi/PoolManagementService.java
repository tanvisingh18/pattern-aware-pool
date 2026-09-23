package com.college.pap.rmi;

import com.college.pap.pool.ConnectionPool;
import com.college.pap.prediction.RiskWeights;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Objects;

public final class PoolManagementService extends UnicastRemoteObject implements PoolManagementRemote {
    private final ConnectionPool pool;

    public PoolManagementService(ConnectionPool pool) throws RemoteException {
        super();
        this.pool = Objects.requireNonNull(pool);
    }

    @Override
    public String getMetricsSnapshot() {
        return pool.metrics().snapshot();
    }

    @Override
    public double getHighRiskThreshold() {
        return pool.config().highRiskThreshold();
    }

    @Override
    public void setHighRiskThreshold(double threshold) {
        pool.config().setHighRiskThreshold(threshold);
    }

    @Override
    public int getPreWarmLeadMinutes() {
        return pool.config().preWarmLeadMinutes();
    }

    @Override
    public void setPreWarmLeadMinutes(int minutes) {
        pool.config().setPreWarmLeadMinutes(minutes);
    }

    @Override
    public void setRiskWeights(double alpha, double beta, double gamma) {
        pool.config().setWeights(new RiskWeights(alpha, beta, gamma));
    }

    @Override
    public String getRiskWeights() {
        return pool.config().weights().toString();
    }
}
