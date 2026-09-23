package com.jeevesh415.blutoothconnector.protocol;

import com.jeevesh415.blutoothconnector.capability.Capability;
import com.jeevesh415.blutoothconnector.capability.CapabilityRegistry;

import org.json.JSONObject;

public final class CommandRouter {
    private static final int MAX_REQUEST_ID = 128;
    private static final int MAX_CAPABILITY_ID = 128;
    private static final int MAX_OPERATION = 128;

    private final CapabilityRegistry registry;
    private final CommandDeduplicator deduplicator =
            new CommandDeduplicator();

    public CommandRouter(CapabilityRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry");
        this.registry = registry;
    }

    public Frame route(Frame command) throws Exception {
        return route(command, "default");
    }

    public Frame route(Frame command, String namespace) throws Exception {
        if (command == null || !Protocol.COMMAND.equals(command.type)) {
            return error(command, "NOT_COMMAND", "Frame is not a command");
        }

        String requestId =
                command.payload.optString("requestId", "");
        String scope = namespace == null ? "default" : namespace;

        if (requestId.isEmpty()) {
            return error(command, "MISSING_REQUEST_ID",
                    "requestId is required");
        }
        if (requestId.length() > MAX_REQUEST_ID) {
            return error(command, "REQUEST_ID_TOO_LONG",
                    "requestId is too long");
        }

        String cacheKey = scope + "|" + requestId;
        Frame previous = deduplicator.get(cacheKey);
        if (previous != null) return previous;

        String capabilityId =
                command.payload.optString("capability", "");
        String operation =
                command.payload.optString("operation", "");

        if (capabilityId.length() > MAX_CAPABILITY_ID
                || operation.length() > MAX_OPERATION) {
            return store(
                    cacheKey,
                    error(command, "FIELD_TOO_LONG",
                            "capability or operation is too long"));
        }

        Capability capability = null;
        for (Capability candidate : registry.all()) {
            if (candidate.id().equals(capabilityId)) {
                capability = candidate;
                break;
            }
        }

        if (capability == null) {
            return store(
                    cacheKey,
                    error(command, "CAPABILITY_NOT_FOUND",
                            capabilityId));
        }

        if (!capability.canHandle(command)) {
            return store(
                    cacheKey,
                    error(command, "OPERATION_NOT_SUPPORTED",
                            operation));
        }

        try {
            Frame result = capability.handle(command);
            Frame normalized = new Frame(
                    Protocol.VERSION,
                    Protocol.RESULT,
                    command.sequence,
                    System.currentTimeMillis(),
                    new JSONObject()
                            .put("requestId", requestId)
                            .put("status", "ok")
                            .put("result", result.payload)
                            .put("type", result.type));
            return store(cacheKey, normalized);
        } catch (Exception error) {
            return store(
                    cacheKey,
                    error(command, "CAPABILITY_ERROR",
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage()));
        }
    }

    private Frame store(String cacheKey, Frame result) {
        deduplicator.put(cacheKey, result);
        return result;
    }

    private Frame error(Frame command, String code, String message)
            throws Exception {
        String requestId = command == null
                ? ""
                : command.payload.optString("requestId", "");
        long sequence = command == null ? 0 : command.sequence;

        return new Frame(
                Protocol.VERSION,
                Protocol.ERROR,
                sequence,
                System.currentTimeMillis(),
                new JSONObject()
                        .put("requestId", requestId)
                        .put("status", "error")
                        .put("code", code)
                        .put("message", message));
    }
}
