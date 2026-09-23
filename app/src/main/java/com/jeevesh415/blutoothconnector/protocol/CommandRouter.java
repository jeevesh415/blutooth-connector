package com.jeevesh415.blutoothconnector.protocol;

import com.jeevesh415.blutoothconnector.capability.Capability;
import com.jeevesh415.blutoothconnector.capability.CapabilityRegistry;

import org.json.JSONObject;

public final class CommandRouter {
    private final CapabilityRegistry registry;
    private final CommandDeduplicator deduplicator = new CommandDeduplicator();

    public CommandRouter(CapabilityRegistry registry) {
        this.registry = registry;
    }

    public Frame route(Frame command) throws Exception {
        if (!Protocol.COMMAND.equals(command.type)) {
            return error(command, "NOT_COMMAND", "Frame is not a command");
        }

        String requestId = command.payload.optString("requestId", "");
        if (requestId.isEmpty()) {
            return error(command, "MISSING_REQUEST_ID", "requestId is required");
        }

        Frame previous = deduplicator.get(requestId);
        if (previous != null) return previous;

        String capabilityId = command.payload.optString("capability", "");
        Capability capability = null;
        for (Capability candidate : registry.all()) {
            if (candidate.id().equals(capabilityId)) {
                capability = candidate;
                break;
            }
        }

        if (capability == null) {
            return store(requestId, error(command, "CAPABILITY_NOT_FOUND", capabilityId));
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
            return store(requestId, normalized);
        } catch (Exception e) {
            return store(requestId, error(command, "CAPABILITY_ERROR",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private Frame store(String requestId, Frame result) {
        deduplicator.put(requestId, result);
        return result;
    }

    private Frame error(Frame command, String code, String message) throws Exception {
        return new Frame(
                Protocol.VERSION,
                Protocol.ERROR,
                command.sequence,
                System.currentTimeMillis(),
                new JSONObject()
                        .put("requestId", command.payload.optString("requestId", ""))
                        .put("status", "error")
                        .put("code", code)
                        .put("message", message));
    }
}
