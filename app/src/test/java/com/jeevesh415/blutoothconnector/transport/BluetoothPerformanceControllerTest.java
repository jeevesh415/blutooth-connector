package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BluetoothPerformanceControllerTest {
    @Test
    public void throughputOptimizerStillBoundsHostPipe() {
        BluetoothThroughputOptimizer optimizer =
                new BluetoothThroughputOptimizer();

        optimizer.observe(64L * 1024 * 1024, 1_000_000_000L);

        int buffer = optimizer.bufferBytes(4096);
        assertTrue(buffer >= 64 * 1024);
        assertTrue(buffer <= 8 * 1024 * 1024);
        assertEquals(64.0, optimizer.estimatedMbps(), 0.01);
    }
}
