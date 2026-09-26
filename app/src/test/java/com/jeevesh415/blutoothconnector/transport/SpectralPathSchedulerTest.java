package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
            sum += allocation.fraction;
        }
        assertEquals(1.0, sum, 1e-9);
    }
}
