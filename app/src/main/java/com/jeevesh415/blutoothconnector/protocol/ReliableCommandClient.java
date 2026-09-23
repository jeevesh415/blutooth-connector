package com.jeevesh415.blutoothconnector.protocol;

import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class ReliableCommandClient implements AutoCloseable {
    public interface Sender {
        void send(Frame frame) throws Exception;
    }

    private static final long DEFAULT_FINAL_TIMEOUT_MS = 8000;
    private static final long MIN_FINAL_TIMEOUT_MS = 3000;
    private static final long MAX_FINAL_TIMEOUT_MS = 30000;
    private static final long RETRY_BASE_MS = 400;
    private static final int MAX_ATTEMPTS = 4;

    private final Sender sender;
    private final LongSupplier timeoutSupplier;
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
        this(sender, () -> DEFAULT_FINAL_TIMEOUT_MS);
    }

    public ReliableCommandClient(Sender sender, LongSupplier timeoutSupplier) {
        if (sender == null) throw new IllegalArgumentException("sender");
        this.sender = sender;
        this.timeoutSupplier = timeoutSupplier == null
                ? () -> DEFAULT_FINAL_TIMEOUT_MS
                : timeoutSupplier;
    }

    public CompletableFuture<Frame> execute(
            long sequence, String capability, String operation, JSONObject arguments) {
        if (capability == null || capability.isEmpty()) {
            CompletableFuture<Frame> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                    new IllegalArgumentException("Capability is required"));
            return failed;
        }
        if (operation == null || operation.isEmpty()) {
            CompletableFuture<Frame> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                    new IllegalArgumentException("Operation is required"));
            return failed;
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        Pending state = new Pending(
                future,
                sequence,
                capability,
                operation,
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
        }, finalTimeoutMs(), TimeUnit.MILLISECONDS);

        future.whenComplete((ok, error) -> {
            Pending current = pending.remove(requestId);
            if (current != null && current.retryTask != null) {
                current.retryTask.cancel(false);
            }
        });
        return future;
    }

    private long adaptiveTimeoutMs() {
        long adaptive;
        try {
            adaptive = timeoutSupplier.getAsLong();
        } catch (Exception ignored) {
            adaptive = DEFAULT_FINAL_TIMEOUT_MS;
        }
        return Math.max(
                1000L,
                Math.min(MAX_FINAL_TIMEOUT_MS, adaptive));
    }

    private long finalTimeoutMs() {
        long adaptive = adaptiveTimeoutMs();
        return Math.max(
                MIN_FINAL_TIMEOUT_MS,
                Math.min(MAX_FINAL_TIMEOUT_MS, adaptive * MAX_ATTEMPTS));
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
            long delayBase = Math.max(
                    RETRY_BASE_MS,
                    Math.min(2000L, adaptiveTimeoutMs() / 4));
            long delay = delayBase * (1L << Math.min(state.attempts - 1, 2));
            state.retryTask = scheduler.schedule(
                    () -> sendAttempt(requestId, state),
                    delay,
                    TimeUnit.MILLISECONDS);
        }
    }

    public boolean accept(Frame frame) {
        if (frame == null
                || (!Protocol.RESULT.equals(frame.type)
                && !Protocol.ERROR.equals(frame.type))) {
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
