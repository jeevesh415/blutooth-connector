package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MultipathLoopbackTest {
    @Test public void stripedTransferReassemblesAcrossTwoPaths() throws Exception {
        File root = Files.createTempDirectory("bcl2-loopback").toFile();
        File source = new File(root, "source.bin");
        File receiverDir = new File(root, "receiver");
        assertTrue(receiverDir.mkdirs());

        byte[] contents = new byte[2_500_000];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = (byte) ((i * 31 + 17) & 0xff);
        }
        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write(contents);
        }

        byte[] token = new byte[BulkTransferProtocol.TOKEN_BYTES];
        for (int i = 0; i < token.length; i++) token[i] = (byte) (i * 3 + 1);
        String tokenBase64 = BulkTransferProtocol.encodeToken(token);

        LoopbackPath pathA = new LoopbackPath(receiverDir, token);
        LoopbackPath pathB = new LoopbackPath(receiverDir, token);
        pathA.start();
        pathB.start();

        try {
            List<BulkEndpointInfo> endpoints = Arrays.asList(
                    new BulkEndpointInfo("127.0.0.1", pathA.port(), tokenBase64, "loopback-a"),
                    new BulkEndpointInfo("127.0.0.1", pathB.port(), tokenBase64, "loopback-b"));

            assertEquals(source.length(),
                    MultipathFileTransfer.send(source, endpoints, 3));

            File received = new File(receiverDir, source.getName());
            assertTrue("Final file was not created", received.isFile());
            assertArrayEquals(
                    BulkTransferProtocol.sha256(source),
                    BulkTransferProtocol.sha256(received));
            assertEquals(source.length(), received.length());
        } finally {
            pathA.close();
            pathB.close();
            deleteTree(root);
        }
    }

    private static final class LoopbackPath implements AutoCloseable {
        private final File directory;
        private final byte[] token;
        private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
        private final ExecutorService handlers = Executors.newFixedThreadPool(4);
        private ServerSocket server;

        LoopbackPath(File directory, byte[] token) {
            this.directory = directory;
            this.token = token.clone();
        }

        void start() throws Exception {
            server = new ServerSocket(0);
            acceptor.execute(() -> {
                while (server != null && !server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        handlers.execute(() -> {
                            try (Socket s = socket) {
                                MultipathReceiver.receive(s, directory, token);
                            } catch (Exception ignored) {
                                // The sender is responsible for retrying failed chunks.
                            }
                        });
                    } catch (Exception ignored) {
                        if (server != null && !server.isClosed()) {
                            // Test fixture only.
                        }
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        @Override public void close() {
            try {
                if (server != null) server.close();
            } catch (Exception ignored) {}
            acceptor.shutdownNow();
            handlers.shutdownNow();
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
