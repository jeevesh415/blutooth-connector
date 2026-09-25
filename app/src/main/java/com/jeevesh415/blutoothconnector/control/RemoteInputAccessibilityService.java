package com.jeevesh415.blutoothconnector.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

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
            return tap((float) command.optDouble("x", -1),
                    (float) command.optDouble("y", -1));
        }
        if ("swipe".equals(type)) {
            return swipe(
                    (float) command.optDouble("x1", -1),
                    (float) command.optDouble("y1", -1),
                    (float) command.optDouble("x2", -1),
                    (float) command.optDouble("y2", -1),
                    Math.max(1, Math.min(2000, command.optLong("durationMs", 250))));
        }
        if ("back".equals(type)) return performGlobalAction(GLOBAL_ACTION_BACK);
        if ("home".equals(type)) return performGlobalAction(GLOBAL_ACTION_HOME);
        if ("recents".equals(type)) return performGlobalAction(GLOBAL_ACTION_RECENTS);
        if ("lock".equals(type)) return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        if ("text".equals(type)) return setFocusedText(command.optString("text", ""));
        return false;
    }

    public boolean executeNode(JSONObject command) {
        AccessibilityNodeInfo node = findNode(command);
        if (node == null) return false;

        String action = command.optString("action", "");
        if ("click".equals(action)) return node.performAction(
                AccessibilityNodeInfo.ACTION_CLICK);
        if ("longClick".equals(action)) return node.performAction(
                AccessibilityNodeInfo.ACTION_LONG_CLICK);
        if ("focus".equals(action)) return node.performAction(
                AccessibilityNodeInfo.ACTION_FOCUS);
        if ("clearFocus".equals(action)) return node.performAction(
                AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
        if ("scrollForward".equals(action)) return node.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if ("scrollBackward".equals(action)) return node.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        if ("setText".equals(action)) {
            String text = command.optString("text", "");
            if (text.length() > 4096) return false;
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text);
            return node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        return false;
    }

    public JSONObject inspectTree(int maxNodes) {
        JSONObject result = new JSONObject();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        try {
            result.put("windowPackage",
                    root == null || root.getPackageName() == null
                            ? JSONObject.NULL : root.getPackageName());
            JSONArray nodes = new JSONArray();
            if (root != null) {
                int limit = Math.max(1, Math.min(200, maxNodes));
                collect(root, nodes, limit);
            }
            result.put("nodes", nodes);
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to inspect accessibility tree", error);
        }
    }

    private void collect(
            AccessibilityNodeInfo node,
            JSONArray nodes,
            int limit) throws Exception {
        if (node == null || nodes.length() >= limit) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        boolean password = node.isPassword();
        JSONObject item = new JSONObject()
                .put("className",
                        node.getClassName() == null ? "" : node.getClassName())
                .put("viewId",
                        node.getViewIdResourceName() == null
                                ? "" : node.getViewIdResourceName())
                .put("text", password ? "" :
                        node.getText() == null ? "" : node.getText())
                .put("contentDescription", password ? "" :
                        node.getContentDescription() == null
                                ? "" : node.getContentDescription())
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom)
                .put("clickable", node.isClickable())
                .put("enabled", node.isEnabled())
                .put("focused", node.isFocused());

        nodes.put(item);
        for (int i = 0; i < node.getChildCount() && nodes.length() < limit; i++) {
            collect(node.getChild(i), nodes, limit);
        }
    }

    private AccessibilityNodeInfo findNode(JSONObject command) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || command == null) return null;

        String viewId = command.optString("viewId", "");
        if (!viewId.isEmpty()) {
            for (AccessibilityNodeInfo node :
                    root.findAccessibilityNodeInfosByViewId(viewId)) {
                if (node != null) return node;
            }
        }

        String text = command.optString("textMatch", "");
        if (!text.isEmpty()) {
            for (AccessibilityNodeInfo node :
                    root.findAccessibilityNodeInfosByText(text)) {
                if (node != null) return node;
            }
        }

        String description = command.optString("contentDescriptionMatch", "");
        if (!description.isEmpty()) {
            return findByDescription(root, description);
        }

        return null;
    }

    private AccessibilityNodeInfo findByDescription(
            AccessibilityNodeInfo node,
            String description) {
        if (node == null) return null;
        CharSequence value = node.getContentDescription();
        if (value != null && description.contentEquals(value)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match =
                    findByDescription(node.getChild(i), description);
            if (match != null) return match;
        }
        return null;
    }

    private boolean tap(float x, float y) {
        if (!validCoordinate(x, y)) return false;
        Path path = new Path();
        path.moveTo(x, y);
        return dispatchGesture(
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, 1))
                        .build(),
                null,
                null);
    }

    private boolean swipe(
            float x1,
            float y1,
            float x2,
            float y2,
            long durationMs) {
        if (!validCoordinate(x1, y1) || !validCoordinate(x2, y2)) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        return dispatchGesture(
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                        .build(),
                null,
                null);
    }

    private boolean validCoordinate(float x, float y) {
        if (x < 0 || y < 0) return false;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowManager wm = getSystemService(WindowManager.class);
            if (wm != null) {
                Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                return x <= bounds.width() && y <= bounds.height();
            }
        }
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
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
