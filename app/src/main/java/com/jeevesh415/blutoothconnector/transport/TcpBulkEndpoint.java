package com.jeevesh415.blutoothconnector.transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TcpBulkEndpoint implements AutoCloseable {
    public interface Listener {
        void onTransferComplete(File file);
        void onError(Exception error);
    }

    public static final class Endpoint {
        public final String host;
        public final int port;
        public final String tokenBase64;
        public final String transport;

        public Endpoint(String host, int port, String tokenBase64) {
            this(host, port, tokenBase64, "tcp-local");
        }

        public Endpoint(String host, int port, String tokenBase64, String transport) {
            this.host = host;
            this.port = port;
            this.tokenBase64 = tokenBase64;
            this.transport = transport == null ? "tcp-local" : transport;
        }
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final SecureRandom random = new SecureRandom();
    private ServerSocket server;
    private File directory;
    private byte[] token;
    private Listener listener;

    public synchronized void start(File directory, Listener listener) throws Exception {
        if (server != null) return;
        if (!directory.exists() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create bulk directory");
        }
        this.directory = directory;
        this.listener = listener;
        this.token = new byte[BulkTransferProtocol.TOKEN_BYTES];
        random.nextBytes(token);
        server = new ServerSocket(0);

        executor.execute(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    final Socket socket = server.accept();
                    executor.execute(() -> handle(socket));
                } catch (Exception e) {
                    if (server != null && listener != null) listener.onError(e);
                }
            }
        });
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setReceiveBufferSize(1024 * 1024);
            BufferedInputStream bufferedIn = new BufferedInputStream(s.getInputStream(), 1024 * 1024);
            DataInputStream in = new DataInputStream(bufferedIn);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(s.getOutputStream(), 1024 * 1024));

            bufferedIn.mark(4);
            int magic = in.readInt();
            bufferedIn.reset();

            File result;
            if (magic == 0x42434C32) {
                result = MultipathReceiver.receive(in, out, directory, token);
            } else if (magic == 0x42434C31) {
                result = ReliableFileTransfer.receive(in, out, directory, token);
            } else {
                throw new java.io.IOException("Unknown bulk protocol");
            }
            if (listener != null) listener.onTransferComplete(result);
        } catch (Exception e) {
            if (listener != null) listener.onError(e);
        }
    }

    public synchronized int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    public synchronized List<Endpoint> endpoints() {
        if (server == null) return Collections.emptyList();
        String encoded = BulkTransferProtocol.encodeToken(token);
        List<Endpoint> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all.hasMoreElements()) {
                NetworkInterface nif = all.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        result.add(new Endpoint(
                                addr.getHostAddress(), port(), encoded, nif.getName()));
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
            server = null;
        }
        executor.shutdownNow();
        token = null;
    }
}
