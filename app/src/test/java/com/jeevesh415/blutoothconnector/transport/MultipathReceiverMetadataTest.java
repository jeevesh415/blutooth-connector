package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.io.IOException;
import java.util.BitSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class MultipathReceiverMetadataTest {
    @Test
    public void restoresValidCompletionBitmap() throws Exception {
        BitSet restored = MultipathReceiver.decodeCompletionBitmap(
                new byte[] {0x05}, 8);

        assertEquals(2, restored.cardinality());
        org.junit.Assert.assertTrue(restored.get(0));
        org.junit.Assert.assertTrue(restored.get(2));
    }

    @Test
    public void rejectsBitmapLongerThanChunkGeometryAllows() throws Exception {
        assertInvalid(new byte[] {0, 0}, 8);
    }

    @Test
    public void rejectsSetBitsBeyondDeclaredChunkCount() throws Exception {
        // Bit 9 is set, but valid indices for a 9-chunk transfer are 0..8.
        assertInvalid(new byte[] {0, 0x02}, 9);
    }

    @Test
    public void rejectsNullBitmapAndInvalidChunkCount() throws Exception {
        assertInvalid(null, 8);
        assertInvalid(new byte[0], 0);
        assertInvalid(new byte[0], 1_000_001);
    }

    private static void assertInvalid(byte[] bitmap, int chunkCount)
            throws Exception {
        try {
            MultipathReceiver.decodeCompletionBitmap(bitmap, chunkCount);
            fail("Expected malformed completion bitmap to be rejected");
        } catch (IOException expected) {
            // Expected: invalid persisted state must not mark chunks complete.
        }
    }
}
