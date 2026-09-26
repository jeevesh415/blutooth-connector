package com.jeevesh415.blutoothconnector.control;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Explicit per-peer authorization for high-risk remote input.
 *
 * The Bluetooth pairing relationship is not treated as sufficient authority.
 * A user must separately authorize a peer before Accessibility-backed control
 * is accepted.
 */
public final class RemoteControlAuthorization {
    private static final String PREFS = "remote_control_authorization";
    private static final String KEY_PREFIX = "peer_";

    private RemoteControlAuthorization() {}

    public static void authorize(Context context, String peerAddress) {
        set(context, peerAddress, true);
    }

    public static void revoke(Context context, String peerAddress) {
        set(context, peerAddress, false);
    }

    public static boolean isAuthorized(Context context, String peerAddress) {
        if (context == null || !validAddress(peerAddress)) return false;
        return prefs(context).getBoolean(KEY_PREFIX + fingerprint(peerAddress), false);
    }

    private static void set(Context context, String peerAddress, boolean value) {
        if (context == null || !validAddress(peerAddress)) {
            throw new IllegalArgumentException("Invalid peer address");
        }
        prefs(context).edit()
                .putBoolean(KEY_PREFIX + fingerprint(peerAddress), value)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String fingerprint(String peerAddress) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(peerAddress.trim().toUpperCase(java.util.Locale.US)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format(java.util.Locale.US, "%02x", b));
            }
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static boolean validAddress(String address) {
        if (address == null) return false;
        return address.matches("(?i)[0-9A-F]{2}(:[0-9A-F]{2}){5}");
    }
}
