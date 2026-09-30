package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BluetoothThroughputOptimizerTest {
    @Test
    public void bufferRemainsBoundedAndGrowsWithObservedThroughput() {
        BluetoothThroughputOptimizer optimizer =
                new BluetoothThroughputOptimizer();

        int initial = optimizer.bufferBytes(4096);
        optimizer.observeThroughput(
                8L * 1024 * 1024,
                1_000_000_000L);
        int after = optimizer.bufferBytes(4096);

        assertTrue(initial >= 64 * 1024);
        assertTrue(after >= initial);
        assertTrue(after <= 8 * 1024 * 1024);
    }

    @Test
    public void throughputSampleDoesNotBecomeRttSample() {
        BluetoothThroughputOptimizer optimizer =
                new BluetoothThroughputOptimizer();

        optimizer.observeThroughput(
                100L * 1024 * 1024,
                10_000_000_000L);

        assertEquals(0.0, optimizer.estimatedRttMilliseconds(), 0.0001);

        optimizer.observeRtt(10_000_000L);
        assertEquals(10.0, optimizer.estimatedRttMilliseconds(), 0.0001);
    }

    @Test
    public void legacyObserveAliasStillMeansThroughput() {
        BluetoothThroughputOptimizer optimizer =
                new BluetoothThroughputOptimizer();

        optimizer.observe(
                8L * 1024 * 1024,
                1_000_000_000L);

        assertTrue(optimizer.estimatedMbps() > 0.0);
        assertEquals(0.0, optimizer.estimatedRttMilliseconds(), 0.0001);
    }
}
