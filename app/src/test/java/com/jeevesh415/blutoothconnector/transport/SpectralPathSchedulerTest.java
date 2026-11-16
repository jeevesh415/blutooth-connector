package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SpectralPathSchedulerTest {
    @Test public void allocationsFormProbabilityDistribution() {
        SpectralPathScheduler scheduler = new SpectralPathScheduler();
        scheduler.path("wifi", "wifi-lan").observe(5_000_000, 8);
        scheduler.path("bt", "bluetooth-rfcomm").observe(500_000, 40);

        List<SpectralPathScheduler.Allocation> allocations = scheduler.allocate();
        assertEquals(2, allocations.size());

        double sum = 0;
        for (SpectralPathScheduler.Allocation allocation : allocations) {
            assertTrue(allocation.fraction >= 0.0);
            assertTrue(allocation.fraction <= 1.0);
            sum += allocation.fraction;
        }
        assertEquals(1.0, sum, 1e-9);
    }

    @Test public void snapshotsExposeConsistentMetricsAndAllocations() {
        SpectralPathScheduler scheduler = new SpectralPathScheduler();
        SpectralPathScheduler.Path wifi = scheduler.path("wifi", "wifi-lan");
        wifi.observe(5_000_000, 8);
        wifi.observe(6_000_000, 7);
        scheduler.path("bt", "bluetooth-rfcomm").observe(500_000, 40);

        List<SpectralPathScheduler.PathSnapshot> snapshots = scheduler.snapshots();
        assertEquals(2, snapshots.size());
        assertEquals("wifi", snapshots.get(0).id);
        assertEquals("wifi-lan", snapshots.get(0).kind);
        assertEquals(2, snapshots.get(0).throughputSamples);
        assertEquals(2, snapshots.get(0).rttSamples);
        assertTrue(snapshots.get(0).estimatedBytesPerSecond > 0);
        assertTrue(snapshots.get(0).estimatedRttMs > 0);
        assertTrue(Double.isFinite(snapshots.get(0).utility));

        double sum = 0;
        for (SpectralPathScheduler.PathSnapshot snapshot : snapshots) {
            assertTrue(snapshot.allocationFraction >= 0.0);
            assertTrue(snapshot.allocationFraction <= 1.0);
            assertTrue(Double.isFinite(snapshot.instabilityScore));
            sum += snapshot.allocationFraction;
        }
        assertEquals(1.0, sum, 1e-9);

        try {
            snapshots.clear();
            fail("Snapshot list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: callers receive a point-in-time read-only view.
        }
    }

    @Test public void nonFiniteObservationsCannotPoisonScheduler() {
        SpectralPathScheduler scheduler = new SpectralPathScheduler();
        SpectralPathScheduler.Path path = scheduler.path("extreme", "wifi-lan");

        path.observe(Double.POSITIVE_INFINITY, Double.NaN);
        path.observe(Double.NaN, Double.POSITIVE_INFINITY);
        assertEquals(0, path.failureRateEstimate(), 0.0);

        // Large but finite values exercise normalization in the spectral DFT.
        for (int i = 0; i < 12; i++) {
            double rate = (i % 2 == 0)
                    ? Double.MAX_VALUE
                    : Double.MAX_VALUE * 0.5;
            path.observe(rate, rate);
        }

        List<SpectralPathScheduler.PathSnapshot> snapshots = scheduler.snapshots();
        assertEquals(1, snapshots.size());
        SpectralPathScheduler.PathSnapshot snapshot = snapshots.get(0);
        assertTrue(Double.isFinite(snapshot.estimatedBytesPerSecond));
        assertTrue(Double.isFinite(snapshot.estimatedRttMs));
        assertTrue(Double.isFinite(snapshot.instabilityScore));
        assertTrue(Double.isFinite(snapshot.utility));
        assertTrue(Double.isFinite(snapshot.allocationFraction));
        assertEquals(12, snapshot.throughputSamples);
        assertEquals(12, snapshot.rttSamples);
        assertFalse(Double.isNaN(snapshot.allocationFraction));
        assertEquals(1.0, snapshot.allocationFraction, 1e-9);
    }

    @Test public void invalidObservationDoesNotDecayFailurePenalty() {
        SpectralPathScheduler.Path path =
                new SpectralPathScheduler().path("wifi", "wifi-lan");
        path.observeFailure();
        double afterFailure = path.failureRateEstimate();

        path.observe(Double.NaN, Double.POSITIVE_INFINITY);
        assertEquals(afterFailure, path.failureRateEstimate(), 0.0);

        path.observe(1_000_000, -1);
        assertEquals(afterFailure * 0.70, path.failureRateEstimate(), 1e-12);
    }

    @Test public void emptySchedulerReturnsEmptyImmutableResults() {
        SpectralPathScheduler scheduler = new SpectralPathScheduler();
        assertTrue(scheduler.allocate().isEmpty());
        assertTrue(scheduler.snapshots().isEmpty());
    }
}
