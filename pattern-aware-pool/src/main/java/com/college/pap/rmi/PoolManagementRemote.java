package com.college.pap.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * RMI management interface — tune weights/thresholds at runtime without restart.
 */
public interface PoolManagementRemote extends Remote {
    String getMetricsSnapshot() throws RemoteException;

    double getHighRiskThreshold() throws RemoteException;

    void setHighRiskThreshold(double threshold) throws RemoteException;

    int getPreWarmLeadMinutes() throws RemoteException;

    void setPreWarmLeadMinutes(int minutes) throws RemoteException;

    void setRiskWeights(double alpha, double beta, double gamma) throws RemoteException;

    String getRiskWeights() throws RemoteException;

    double getHotHourThreshold() throws RemoteException;

    void setHotHourThreshold(double threshold) throws RemoteException;

    int getClusterAvoidRun() throws RemoteException;

    void setClusterAvoidRun(int runLength) throws RemoteException;

    long getRecoveryProbeSeconds() throws RemoteException;

    void setRecoveryProbeSeconds(long seconds) throws RemoteException;
}
