package com.jeevesh415.blutoothconnector.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class Protocol {
    private Protocol() {}

    public static final int VERSION = 1;
    public static final int MAX_FRAME_BYTES = 64 * 1024;
    public static final UUID RFCOMM_UUID =
            UUID.fromString("7f6c4d32-6a3a-4d61-9d89-0b3b7b9e2a41");

    public static final String HELLO = "HELLO";
    public static final String CHALLENGE = "CHALLENGE";
    public static final String AUTH = "AUTH";
    public static final String AUTH_OK = "AUTH_OK";
    public static final String PING = "PING";
    public static final String PONG = "PONG";
    public static final String CAPABILITIES = "CAPABILITIES";
    public static final String COMMAND = "COMMAND";
    public static final String RESULT = "RESULT";
    public static final String EVENT = "EVENT";
    public static final String ERROR = "ERROR";

    // Real-time media signaling carried over the authenticated control plane.
    public static final String RTC_OFFER = "RTC_OFFER";
    public static final String RTC_ANSWER = "RTC_ANSWER";
    public static final String RTC_ICE = "RTC_ICE";
    public static final String RTC_CONTROL = "RTC_CONTROL";
    public static final String RTC_STOP = "RTC_STOP";

    public static String id(long sequence) {
        return String.format(Locale.US, "%08x", sequence);
    }

    public static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
