package com.college.pap.routing;

import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.pool.PoolConfig;

/**
 * Shared hot-hour detection used by {@link RoutingDecider} and BackupPreWarmer.
 */
public final class HotHourRules {

    private HotHourRules() {
    }

    /**
     * Hot hour when there is enough evidence at {@code hour} and the EWMA
     * failure rate meets the configured threshold (plain rate is display-only).
     */
    public static boolean isHot(EndpointRiskProfile profile, int hour, PoolConfig config) {
        if (profile == null || config == null) {
            return false;
        }
        return profile.isHotHour(hour, config.hotHourMinSamples(), config.hotHourThreshold());
    }

    public static boolean isHot(
            EndpointRiskProfile profile, int hour, int minSamples, double threshold) {
        if (profile == null) {
            return false;
        }
        return profile.isHotHour(hour, minSamples, threshold);
    }
}
