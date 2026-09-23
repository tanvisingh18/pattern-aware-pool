package com.college.pap.prediction;

import com.college.pap.model.ClusterState;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionEngineTest {

    @Test
    void computesWeightedRiskScore() {
        EndpointId id = new EndpointId("primary-db");
        double[] hours = new double[24];
        hours[14] = 0.80;

        EndpointRiskProfile profile = new EndpointRiskProfile(
                id,
                hours,
                0.50,
                new ClusterState(3, 3.0, true),
                Instant.parse("2026-07-26T14:00:00Z"),
                100);

        RiskWeights weights = new RiskWeights(0.45, 0.35, 0.20);
        PredictionEngine engine = new PredictionEngine(weights);
        RiskScore score = engine.score(profile, Instant.parse("2026-07-26T14:20:00Z"));

        double expectedCluster = new ClusterState(3, 3.0, true).clusterPenalty();
        double expected = 0.45 * 0.80 + 0.35 * expectedCluster + 0.20 * 0.50;

        assertEquals(14, score.hourOfDay());
        assertEquals(expected, score.score(), 0.0001);
        assertTrue(score.isHighRisk(0.55));
    }

    @Test
    void unknownEndpointIsLowRisk() {
        PredictionEngine engine = new PredictionEngine();
        RiskScore score = engine.scoreUnknown(
                new EndpointId("new-endpoint"),
                Instant.parse("2026-07-26T14:00:00Z"));
        assertEquals(0.0, score.score(), 0.0001);
    }
}
