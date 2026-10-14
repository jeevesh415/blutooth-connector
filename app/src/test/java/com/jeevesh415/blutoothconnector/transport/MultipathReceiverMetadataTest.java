package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.BitSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void truncatesOversizedOrphanPartialToDeclaredSize() throws Exception {
        File directory = Files.createTempDirectory("bcl-partial-test").toFile();
        File part = new File(directory, "sample.part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(new byte[32]);
        }

        MultipathReceiver.normalizePartialFile(part, 16L);
        assertEquals(16L, part.length());

        // A smaller partial is retained: its bitmap is empty when metadata is
        // absent, so every chunk must be resent before promotion.
        MultipathReceiver.normalizePartialFile(part, 24L);
        assertEquals(16L, part.length());

        Files.deleteIfExists(part.toPath());
        Files.deleteIfExists(directory.toPath());
    }

    @Test
    public void integrityResetClearsDurableBitmapAndPartialBytes() throws Exception {
        File directory = Files.createTempDirectory("bcl-integrity-reset").toFile();
        File part = new File(directory, "sample.part");
        File metadata = new File(part.getPath() + ".meta");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(new byte[] {1, 2, 3, 4, 5, 6});
        }
        try (FileOutputStream out = new FileOutputStream(metadata)) {
            out.write(new byte[] {9, 8, 7});
        }
        BitSet complete = new BitSet();
        complete.set(0, 3);

        MultipathReceiver.resetAfterIntegrityFailure(part, complete);

        assertEquals(0L, part.length());
        assertEquals(0, complete.cardinality());
        assertFalse("Persisted completion metadata must be invalidated first",
                metadata.exists());

        Files.deleteIfExists(part.toPath());
        Files.deleteIfExists(metadata.toPath());
        Files.deleteIfExists(directory.toPath());
    }

    @Test(expected = IOException.class)
    public void rejectsInvalidPartialFileSize() throws Exception {
        MultipathReceiver.normalizePartialFile(
                new File("unused.part"), 0L);
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
