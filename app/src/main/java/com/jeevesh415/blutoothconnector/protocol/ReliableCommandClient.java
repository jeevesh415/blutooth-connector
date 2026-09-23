package com.jeevesh415.blutoothconnector.protocol;

import com.jeevesh415.blutoothconnector.transport.FramedConnection;

import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ReliableCommandClient implements AutoCloseable {
    public interface Sender {
        void send(Frame frame) throws Exception;
    }

    private static final long RETRY_BASE_MS = 400;
    private static final int MAX_ATTEMPTS = 4;
    private final Sender sender;
    private final Map<String, CompletableFuture<Frame>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    public ReliableCommandClient(Sender sender) {
        this.sender = sender;
    }

    public CompletableFuture<Frame> execute(
            long sequence, String capability, String operation, JSONObject arguments) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        pending.put(requestId, future);

        Runnable[] attempt = new Runnable[1];
        int[] count = new int[] {0};

        attempt[0] = () -> {
            if (future.isDone()) return;
            count[0]++;
            try {
                JSONObject payload = new JSONObject()
                        .put("requestId", requestId)
                        .put("capability", capability)
                        .put("operation", operation)
                        .put("arguments", arguments == null ? new JSONObject() : arguments);
                sender.send(new Frame(
                        Protocol.VERSION,
                        Protocol.COMMAND,
                        sequence,
                        System.currentTimeMillis(),
                        payload));
            } catch (Exception e) {
                if (count[0] >= MAX_ATTEMPTS) {
                    pending.remove(requestId);
                    future.completeExceptionally(e);
                    return;
                }
            }

            if (count[0] < MAX_ATTEMPTS) {
                long delay = RETRY_BASE_MS * (1L << Math.min(count[0] - 1, 3));
                scheduler.schedule(attempt[0], delay, TimeUnit.MILLISECONDS);
            }
        };

        attempt[0].run();
        future.whenComplete((ok, error) -> pending.remove(requestId));
        return future;
    }

    public boolean accept(Frame frame) {
        if (!Protocol.RESULT.equals(frame.type) && !Protocol.ERROR.equals(frame.type)) return false;
        String requestId = frame.payload.optString("requestId", "");
        if (requestId.isEmpty()) return false;
        CompletableFuture<Frame> future = pending.get(requestId);
        if (future == null) return false;
        if (Protocol.RESULT.equals(frame.type)
                && "error".equals(frame.payload.optString("status", ""))) {
            future.complete(frame);
        } else {
            future.complete(frame);
        }
        return true;
    }

    @Override public void close() {
        scheduler.shutdownNow();
        for (CompletableFuture<Frame> future : pending.values()) {
            future.completeExceptionally(new java.io.IOException("Command client closed"));
        }
        pending.clear();
    }
}
