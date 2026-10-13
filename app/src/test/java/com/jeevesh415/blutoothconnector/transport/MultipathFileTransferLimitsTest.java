package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class MultipathFileTransferLimitsTest {
    @Test(expected = IllegalArgumentException.class)
    public void rejectsMoreThanThirtyTwoEndpointsBeforeScheduling() throws Exception {
        File source = Files.createTempFile("bcl-transfer-limit", ".bin").toFile();
        try {
            Files.write(source.toPath(), new byte[] { 1 });

            List<BulkEndpointInfo> endpoints = new ArrayList<>();
            for (int i = 0; i < 33; i++) {
                endpoints.add(new BulkEndpointInfo(
                        "192.168.1." + (i + 1),
                        10000 + i,
                        "unused-token",
                        "tcp-local"));
            }

            MultipathFileTransfer.send(source, endpoints, 1);
        } finally {
            Files.deleteIfExists(source.toPath());
        }
    }
}
