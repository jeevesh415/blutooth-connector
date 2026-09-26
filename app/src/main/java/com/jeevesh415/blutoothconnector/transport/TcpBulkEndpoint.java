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

        public Endpoint(
                String host,
                int port,
                String tokenBase64,
                String transport) {
            this.host = host;
            this.port = port;
            this.tokenBase64 = tokenBase64;
            this.transport = transport == null
                    ? "tcp-local"
                    : transport;
        }
    }

    private static final int MAX_PARALLEL_CONNECTIONS = 8;
    private static final int SOCKET_READ_TIMEOUT_MS = 15_000;

    private final ExecutorService acceptExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService transferExecutor =
            Executors.newFixedThreadPool(
                    MAX_PARALLEL_CONNECTIONS);
    private final SecureRandom random = new SecureRandom();

    private ServerSocket server;
    private File directory;
    private byte[] token;
    private Listener listener;

    public synchronized void start(
            File directory,
            Listener listener) throws Exception {
        if (server != null) return;
        if (!directory.exists() && !directory.mkdirs()) {
            throw new java.io.IOException(
                    "Cannot create bulk directory");
        }

        this.directory = directory;
        this.listener = listener;
        this.token =
                new byte[BulkTransferProtocol.TOKEN_BYTES];
        random.nextBytes(token);

        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(
                new java.net.InetSocketAddress(0));

        acceptExecutor.execute(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    socket.setTcpNoDelay(true);
                    transferExecutor.execute(
                            () -> handle(socket));
                } catch (Exception error) {
                    if (server != null
                            && !server.isClosed()
                            && listener != null) {
                        listener.onError(error);
                    }
                }
            }
        });
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(
                    SOCKET_READ_TIMEOUT_MS);
            s.setReceiveBufferSize(
                    4 * 1024 * 1024);

            BufferedInputStream bufferedIn =
                    new BufferedInputStream(
                            s.getInputStream(),
                            1024 * 1024);
            DataInputStream in =
                    new DataInputStream(bufferedIn);
            DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(
                                    s.getOutputStream(),
                                    64 * 1024));

            bufferedIn.mark(4);
            int magic = in.readInt();
            bufferedIn.reset();

            if (magic != 0x42434C32) {
                throw new java.io.IOException(
                        "Unsupported bulk protocol; "
                                + "BCL3 multipath is required");
            }

            File result =
                    MultipathReceiver.receive(
                            in, out, directory, token);
            if (result != null && listener != null) {
                listener.onTransferComplete(result);
            }
        } catch (Exception error) {
            if (listener != null) {
                listener.onError(error);
            }
        }
    }

    /**
     * Returns a copy of the application bulk-transfer secret used to
     * authenticate/encrypt BCL3 payloads. The caller must not retain it
     * longer than required.
     */
    public synchronized byte[] authorizationToken() {
        return token == null ? null : token.clone();
    }

    public synchronized int port() {
        return server == null
                ? -1
                : server.getLocalPort();
    }

    public synchronized List<Endpoint> endpoints() {
        if (server == null || token == null) {
            return Collections.emptyList();
        }

        String encoded =
                BulkTransferProtocol.encodeToken(token);
        List<Endpoint> result = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> all =
                    NetworkInterface.getNetworkInterfaces();

            while (all.hasMoreElements()) {
                NetworkInterface nif = all.nextElement();
                if (!nif.isUp()
                        || nif.isLoopback()
                        || nif.isVirtual()) {
                    continue;
                }

                String transport =
                        classify(nif.getName());

                Enumeration<InetAddress> addresses =
                        nif.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress addr =
                            addresses.nextElement();

                    if (!(addr instanceof Inet4Address)
                            || addr.isLoopbackAddress()
                            || addr.isLinkLocalAddress()) {
                        continue;
                    }

                    String host =
                            addr.getHostAddress();
                    if (!isPrivateIpv4(host)) {
                        continue;
                    }

                    result.add(new Endpoint(
                            host,
                            port(),
                            encoded,
                            transport));
                }
            }
        } catch (Exception ignored) {}

        return result;
    }

    private static String classify(
            String interfaceName) {
        String n = interfaceName == null
                ? ""
                : interfaceName.toLowerCase(
                        java.util.Locale.US);

        if (n.startsWith("p2p")) {
            return "wifi-direct";
        }
        if (n.startsWith("aware")
                || n.startsWith("nan")) {
            return "wifi-aware";
        }
        if (n.startsWith("wlan")) {
            return "wifi-lan";
        }
        if (n.startsWith("eth")) {
            return "ethernet";
        }
        return "tcp-local";
    }

    private static boolean isPrivateIpv4(
            String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;

        int a;
        int b;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            return false;
        }

        return a == 10
                || (a == 172
                    && b >= 16
                    && b <= 31)
                || (a == 192 && b == 168)
                || (a == 100
                    && b >= 64
                    && b <= 127);
    }

    @Override public synchronized void close() {
        if (server != null) {
            try { server.close(); }
            catch (Exception ignored) {}
            server = null;
        }

        acceptExecutor.shutdownNow();
        transferExecutor.shutdownNow();
        token = null;
    }
}
