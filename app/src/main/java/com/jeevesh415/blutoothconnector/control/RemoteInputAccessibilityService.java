package com.jeevesh415.blutoothconnector.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

/**
 * Explicitly user-enabled remote input endpoint.
 *
 * Security model:
 * - Android AccessibilityService must be enabled by the user.
 * - The service accepts only the small command vocabulary below.
 * - Coordinates are bounds checked before gesture dispatch.
 * - Text insertion is limited and never executes shell/ADB commands.
 */
public final class RemoteInputAccessibilityService extends AccessibilityService {
    private static volatile RemoteInputAccessibilityService instance;

    public static RemoteInputAccessibilityService instance() {
        return instance;
    }

    @Override protected void onServiceConnected() {
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public boolean execute(JSONObject command) {
        if (command == null) return false;
        String type = command.optString("type", "");
        if ("tap".equals(type)) {
            return tap(
                    command.optFloat("x", -1),
                    command.optFloat("y", -1));
        }
        if ("swipe".equals(type)) {
            return swipe(
                    command.optFloat("x1", -1),
                    command.optFloat("y1", -1),
                    command.optFloat("x2", -1),
                    command.optFloat("y2", -1),
                    Math.max(1, Math.min(2000, command.optLong("durationMs", 250))));
        }
        if ("back".equals(type)) {
            return performGlobalAction(GLOBAL_ACTION_BACK);
        }
        if ("home".equals(type)) {
            return performGlobalAction(GLOBAL_ACTION_HOME);
        }
        if ("recents".equals(type)) {
            return performGlobalAction(GLOBAL_ACTION_RECENTS);
        }
        if ("lock".equals(type)) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        if ("text".equals(type)) {
            return setFocusedText(command.optString("text", ""));
        }
        return false;
    }

    private boolean tap(float x, float y) {
        if (!validCoordinate(x, y)) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(
                                new GestureDescription.StrokeDescription(
                                        path, 0, 1))
                        .build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        if (!validCoordinate(x1, y1)
                || !validCoordinate(x2, y2)) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(
                                new GestureDescription.StrokeDescription(
                                        path, 0, durationMs))
                        .build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean validCoordinate(float x, float y) {
        if (x < 0 || y < 0) return false;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowManager wm = getSystemService(WindowManager.class);
            if (wm != null) {
                android.graphics.Rect bounds =
                        wm.getCurrentWindowMetrics().getBounds();
                return x <= bounds.width() && y <= bounds.height();
            }
        }
        android.util.DisplayMetrics metrics =
                getResources().getDisplayMetrics();
        return x <= metrics.widthPixels && y <= metrics.heightPixels;
    }

    private boolean setFocusedText(String text) {
        if (text == null || text.length() > 4096) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = root.findFocus(
                AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text);
        return focused.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
}
