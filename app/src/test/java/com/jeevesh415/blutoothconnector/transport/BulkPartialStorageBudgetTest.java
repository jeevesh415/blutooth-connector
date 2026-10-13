package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public final class BulkPartialStorageBudgetTest {
    @Test
    public void accountsForVisibleAndHiddenPartialFilesTogether() throws Exception {
        File directory = Files.createTempDirectory("bcl-partial-budget").toFile();
        try {
            setLength(new File(directory, "tcp-transfer.part"), 2_048);
            setLength(new File(directory, ".l2cap-transfer.part"), 4_096);

            long total = MultipathReceiver.reservedPartialBytes(
                    directory, new File(directory, ".new-candidate.part"));

            assertEquals(6_144L, total);
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    public void excludesCandidatePartialFromExistingReservation() throws Exception {
        File directory = Files.createTempDirectory("bcl-partial-budget").toFile();
        try {
            setLength(new File(directory, "existing.part"), 2_048);
            File candidate = new File(directory, ".candidate.part");
            setLength(candidate, 4_096);

            long total = MultipathReceiver.reservedPartialBytes(
                    directory, candidate);

            assertEquals(2_048L, total);
        } finally {
            deleteTree(directory);
        }
    }

    private static void setLength(File file, long length) throws Exception {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(length);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        if (!file.delete()) file.deleteOnExit();
    }
}
