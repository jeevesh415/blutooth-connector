package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BluetoothThroughputOptimizerTest {
    @Test
    public void bufferRemainsBoundedAndGrowsWithObservedPipe() {
        BluetoothThroughputOptimizer optimizer =
                new BluetoothThroughputOptimizer();

        int initial = optimizer.bufferBytes(4096);
        optimizer.observe(8L * 1024 * 1024, 1_000_000_000L);
        int after = optimizer.bufferBytes(4096);

        assertTrue(initial >= 64 * 1024);
        assertTrue(after >= initial);
        assertTrue(after <= 8 * 1024 * 1024);
    }

    @Test
    public void throughputDoesNotBecomeRtt() {
        BluetoothThroughputOptimizer optimizer =
                new BluetoothThroughputOptimizer();
        optimizer.observeThroughput(10L * 1024 * 1024, 1_000_000_000L);

        assertEquals(0.0, optimizer.estimatedRttMilliseconds(), 0.0001);
    }

    @Test
    public void rttIsUsedForBufferSizing() {
        BluetoothThroughputOptimizer optimizer =
                new BluetoothThroughputOptimizer();
        optimizer.observeThroughput(10L * 1024 * 1024, 1_000_000_000L);
        int baseline = optimizer.bufferBytes(4096);

        optimizer.observeRtt(100_000_000L);
        int largerRtt = optimizer.bufferBytes(4096);

        assertEquals(100.0, optimizer.estimatedRttMilliseconds(), 0.0001);
        assertTrue(largerRtt >= baseline);
    }

    @Test
    public void resetClearsFeedbackState() {
        BluetoothThroughputOptimizer optimizer =
                new BluetoothThroughputOptimizer();
        optimizer.observeThroughput(20L * 1024 * 1024, 1_000_000_000L);
        optimizer.observeRtt(50_000_000L);
        optimizer.reset();

        assertEquals(0.0, optimizer.estimatedBytesPerSecond(), 0.0001);
        assertEquals(0.0, optimizer.estimatedRttMilliseconds(), 0.0001);
    }
    @Test(expected = java.io.IOException.class)
    public void chunkCountRejectsOverflow() throws Exception {
        BluetoothL2capBulkTransport.validateChunkCount(1_000_001L);
    }

    @Test
    public void chunkCountAcceptsBoundary() throws Exception {
        BluetoothL2capBulkTransport.validateChunkCount(1_000_000L);
    }
}
