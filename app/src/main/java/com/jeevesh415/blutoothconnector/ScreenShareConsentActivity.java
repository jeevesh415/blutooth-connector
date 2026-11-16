package com.jeevesh415.blutoothconnector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Receiver-side consent gate for an authenticated remote screen-view request.
 *
 * Approval is intentionally two-step: the owner taps Share here, then Android
 * displays its own MediaProjection system consent UI. No screen is captured
 * before both decisions succeed.
 */
public final class ScreenShareConsentActivity extends Activity {
    public static final String EXTRA_PEER_ADDRESS = "peer_address";
    public static final String EXTRA_REQUEST_ID = "screen_request_id";
    public static final String EXTRA_PEER_NAME = "peer_name";
    private static final int REQUEST_CAPTURE = 8101;

    private String peerAddress;
    private String requestId;
    private String peerName;
    private ConnectionService service;
    private ServiceConnection serviceConnection;
    private TextView status;
    private Button approveButton;
    private boolean responded;
    private boolean capturePromptOpen;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        peerAddress = getIntent().getStringExtra(EXTRA_PEER_ADDRESS);
        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        peerName = getIntent().getStringExtra(EXTRA_PEER_NAME);
        if (peerAddress == null || requestId == null) {
            finish();
            return;
        }
        buildUi();
        bindConnectionService();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(0xFF08111F);

        TextView title = new TextView(this);
        title.setText("Screen-sharing request");
        title.setTextColor(0xFFF4F8FF);
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setText((peerName == null || peerName.trim().isEmpty()
                ? "A connected device" : peerName)
                + " wants to view this phone's screen. Your screen remains private "
                + "unless you approve this request and then approve Android's "
                + "screen-capture prompt.");
        detail.setTextColor(0xFFB8C8D8);
        detail.setTextSize(15);
        detail.setPadding(0, dp(14), 0, dp(16));
        root.addView(detail);

        status = new TextView(this);
        status.setText("Checking the authenticated connection…");
        status.setTextColor(0xFF55D6FF);
        status.setTextSize(13);
        root.addView(status);

        approveButton = new Button(this);
        approveButton.setText("Share screen");
        approveButton.setAllCaps(false);
        approveButton.setEnabled(false);
        approveButton.setOnClickListener(v -> requestAndroidCaptureConsent());
        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(-1, dp(54));
        buttonParams.topMargin = dp(22);
        root.addView(approveButton, buttonParams);

        Button decline = new Button(this);
        decline.setText("Decline");
        decline.setAllCaps(false);
        decline.setOnClickListener(v -> declineRequest());
        LinearLayout.LayoutParams declineParams =
                new LinearLayout.LayoutParams(-1, dp(50));
        declineParams.topMargin = dp(8);
        root.addView(decline, declineParams);

        setContentView(root);
    }

    private void bindConnectionService() {
        Intent intent = new Intent(this, ConnectionService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            serviceConnection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    service = ((ConnectionService.LocalBinder) binder).service();
                    boolean valid = service.hasPendingScreenShareRequest(
                            peerAddress, requestId);
                    approveButton.setEnabled(valid);
                    status.setText(valid
                            ? "Connected request verified. Screen-only sharing; microphone is off."
                            : "This request expired or the connection is no longer valid.");
                    if (!valid) {
                        responded = true;
                        finish();
                    }
                }

                @Override public void onServiceDisconnected(ComponentName name) {
                    service = null;
                    approveButton.setEnabled(false);
                    status.setText("Connection lost. No screen is being shared.");
                }
            };
            bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        } catch (Exception error) {
            status.setText("Could not verify this request. No screen is being shared.");
            approveButton.setEnabled(false);
        }
    }

    private void requestAndroidCaptureConsent() {
        if (service == null
                || !service.hasPendingScreenShareRequest(peerAddress, requestId)) {
            status.setText("The request is no longer valid.");
            approveButton.setEnabled(false);
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            status.setText("Android screen capture is unavailable on this device.");
            return;
        }
        capturePromptOpen = true;
        approveButton.setEnabled(false);
        status.setText("Approve Android's system screen-capture prompt to continue.");
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        capturePromptOpen = false;
        if (resultCode != RESULT_OK || data == null) {
            declineRequest();
            return;
        }
        try {
            if (service == null) throw new IllegalStateException("Connection service disconnected");
            service.startRequestedScreenShare(peerAddress, requestId, resultCode, data);
            responded = true;
            status.setText("Screen sharing started.");
            finish();
        } catch (Exception error) {
            if (service != null) {
                try {
                    service.respondToScreenShareRequest(peerAddress, requestId, "declined");
                } catch (Exception ignored) {}
            }
            responded = true;
            status.setText("Screen sharing could not start: " + error.getMessage());
            finish();
        }
    }

    private void declineRequest() {
        if (responded) return;
        if (service != null) {
            try {
                service.respondToScreenShareRequest(peerAddress, requestId, "declined");
            } catch (Exception ignored) {}
        }
        responded = true;
        finish();
    }

    @Override public void onBackPressed() {
        if (!capturePromptOpen) declineRequest();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (serviceConnection != null) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceConnection = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
