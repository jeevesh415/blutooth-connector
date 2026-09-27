package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;
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
}
