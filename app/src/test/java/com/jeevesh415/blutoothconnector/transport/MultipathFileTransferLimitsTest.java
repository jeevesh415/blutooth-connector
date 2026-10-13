package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class MultipathFileTransferLimitsTest {
    @Test
    public void acceptsPrivateIpLiteralsAndRemovesRemoteIpv6Scope() {
        assertEquals(
                "192.168.1.2",
                MultipathFileTransfer.normalizeLocalEndpointHost(
                        "192.168.1.2", false));
        assertNotNull(
                MultipathFileTransfer.normalizeLocalEndpointHost(
                        "fd12::1%aware0", false));
    }

    @Test
    public void rejectsPublicAddressesAndHostnames() {
        assertNull(
                MultipathFileTransfer.normalizeLocalEndpointHost(
                        "8.8.8.8", false));
        assertNull(
                MultipathFileTransfer.normalizeLocalEndpointHost(
                        "example.com", false));
        assertNull(
                MultipathFileTransfer.normalizeLocalEndpointHost(
                        "2606:4700:4700::1111", false));
    }

    @Test
    public void loopbackIsAllowedOnlyForContextFreeTransfers() {
        assertEquals(
                "127.0.0.1",
                MultipathFileTransfer.normalizeLocalEndpointHost(
                        "127.0.0.1", true));
        assertNull(
                MultipathFileTransfer.normalizeLocalEndpointHost(
                        "127.0.0.1", false));
    }

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
