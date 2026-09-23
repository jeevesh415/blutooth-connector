package com.jeevesh415.blutoothconnector.protocol;

import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ReliableCommandClient implements AutoCloseable {
    public interface Sender {
        void send(Frame frame) throws Exception;
    }

    private static final long RETRY_BASE_MS = 400;
    private static final int MAX_ATTEMPTS = 4;
    private static final long FINAL_TIMEOUT_MS = 8000;

    private final Sender sender;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    private static final class Pending {
        final CompletableFuture<Frame> future;
        final long sequence;
        final String capability;
        final String operation;
        final JSONObject arguments;
        int attempts;
        ScheduledFuture<?> retryTask;

        Pending(CompletableFuture<Frame> future, long sequence,
                String capability, String operation, JSONObject arguments) {
            this.future = future;
            this.sequence = sequence;
            this.capability = capability;
            this.operation = operation;
            this.arguments = arguments;
        }
    }

    public ReliableCommandClient(Sender sender) {
        this.sender = sender;
    }

    public CompletableFuture<Frame> execute(
            long sequence, String capability, String operation, JSONObject arguments) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        Pending state = new Pending(
                future, sequence, capability, operation,
                arguments == null ? new JSONObject() : arguments);
        pending.put(requestId, state);

        sendAttempt(requestId, state);

        scheduler.schedule(() -> {
            Pending current = pending.remove(requestId);
            if (current != null && !current.future.isDone()) {
                if (current.retryTask != null) current.retryTask.cancel(false);
                current.future.completeExceptionally(
                        new java.io.IOException("Command timed out: " + requestId));
            }
        }, FINAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        future.whenComplete((ok, error) -> {
            Pending current = pending.remove(requestId);
            if (current != null && current.retryTask != null) {
                current.retryTask.cancel(false);
            }
        });
        return future;
    }

    private void sendAttempt(String requestId, Pending state) {
        if (state.future.isDone()) return;
        state.attempts++;

        try {
            JSONObject payload = new JSONObject()
                    .put("requestId", requestId)
                    .put("capability", state.capability)
                    .put("operation", state.operation)
                    .put("arguments", state.arguments);

            sender.send(new Frame(
                    Protocol.VERSION,
                    Protocol.COMMAND,
                    state.sequence,
                    System.currentTimeMillis(),
                    payload));
        } catch (Exception e) {
            if (state.attempts >= MAX_ATTEMPTS) {
                pending.remove(requestId);
                state.future.completeExceptionally(e);
                return;
            }
        }

        if (state.attempts < MAX_ATTEMPTS && !state.future.isDone()) {
            long delay = RETRY_BASE_MS *
                    (1L << Math.min(state.attempts - 1, 3));
            state.retryTask = scheduler.schedule(
                    () -> sendAttempt(requestId, state),
                    delay, TimeUnit.MILLISECONDS);
        }
    }

    public boolean accept(Frame frame) {
        if (!Protocol.RESULT.equals(frame.type) && !Protocol.ERROR.equals(frame.type)) {
            return false;
        }

        String requestId = frame.payload.optString("requestId", "");
        if (requestId.isEmpty()) return false;

        Pending state = pending.get(requestId);
        if (state == null) return false;

        state.future.complete(frame);
        return true;
    }

    @Override public void close() {
        scheduler.shutdownNow();
        for (Pending state : pending.values()) {
            state.future.completeExceptionally(
                    new java.io.IOException("Command client closed"));
        }
        pending.clear();
    }
}
