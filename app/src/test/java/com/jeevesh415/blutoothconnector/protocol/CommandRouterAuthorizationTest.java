package com.jeevesh415.blutoothconnector.protocol;

import com.jeevesh415.blutoothconnector.capability.Capability;
import com.jeevesh415.blutoothconnector.capability.CapabilityRegistry;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CommandRouterAuthorizationTest {
    @Test public void protectedCapabilityRequiresPeerAuthorizationContext() throws Exception {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(new Capability() {
            @Override public String id() { return "protected.test"; }
            @Override public String version() { return "1.0"; }
            @Override public boolean requiresExplicitAuthorization() { return true; }
            @Override public boolean canHandle(Frame command) { return true; }
            @Override public Frame handle(Frame command) {
                return command;
            }
        });

        CommandRouter router = new CommandRouter(registry);
        Frame command = new Frame(
                Protocol.VERSION,
                Protocol.COMMAND,
                1,
                System.currentTimeMillis(),
                new JSONObject()
                        .put("requestId", "r1")
                        .put("capability", "protected.test")
                        .put("operation", "run"));

        Frame result = router.route(command, "AA:BB:CC:DD:EE:FF", null);
        assertEquals(Protocol.ERROR, result.type);
        assertEquals(
                "AUTHORIZATION_REQUIRED",
                result.payload.optString("code"));
    }
}
