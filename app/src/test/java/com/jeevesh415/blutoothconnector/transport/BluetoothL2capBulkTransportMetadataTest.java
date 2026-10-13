package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.io.IOException;

public final class BluetoothL2capBulkTransportMetadataTest {
    @Test
    public void acceptsExactAndPartialFinalChunks() throws Exception {
        BluetoothL2capBulkTransport.validateTransferMetadata(
                512L * 1024L, 512 * 1024, 1);
        BluetoothL2capBulkTransport.validateTransferMetadata(
                512L * 1024L + 1L, 512 * 1024, 2);
        BluetoothL2capBulkTransport.validateTransferMetadata(
                16L * 1024L, 16 * 1024, 1);
    }

    @Test(expected = IOException.class)
    public void rejectsChunkCountThatCannotCoverDeclaredFile() throws Exception {
        BluetoothL2capBulkTransport.validateTransferMetadata(
                512L * 1024L + 1L, 512 * 1024, 1);
    }

    @Test(expected = IOException.class)
    public void rejectsChunkCountThatClaimsExtraChunks() throws Exception {
        BluetoothL2capBulkTransport.validateTransferMetadata(
                512L * 1024L, 512 * 1024, 2);
    }

    @Test(expected = IOException.class)
    public void rejectsChunkSizeOutsideProtocolBounds() throws Exception {
        BluetoothL2capBulkTransport.validateTransferMetadata(
                1024, 1024, 1);
    }
}
