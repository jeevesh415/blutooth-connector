package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class BulkTransferProtocolTest {
    @Test public void requestRoundTrip() throws Exception {
        byte[] token = new byte[BulkTransferProtocol.TOKEN_BYTES];
        byte[] hash = new byte[BulkTransferProtocol.HASH_BYTES];
        for (int i = 0; i < token.length; i++) token[i] = (byte) i;
        for (int i = 0; i < hash.length; i++) hash[i] = (byte) (255 - i);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        BulkTransferProtocol.writeRequest(out, token, 1234, 0, hash, "file.bin");

        BulkTransferProtocol.Request request =
                BulkTransferProtocol.readRequest(
                        new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertArrayEquals(token, request.token);
        assertEquals(1234L, request.fileSize);
        assertEquals("file.bin", request.fileName);
        assertEquals(0L, request.offset);
    }
}
