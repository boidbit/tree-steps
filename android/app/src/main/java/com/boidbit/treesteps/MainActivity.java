package com.boidbit.treesteps;

import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final long EXIT_WINDOW_MS = 2000;
    private long lastBack;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TreeStepsPlugin.class);
        super.onCreate(savedInstanceState);
        // Back first closes whatever the page has open (settings, 3D, onboarding); otherwise press twice to exit.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                getBridge().getWebView().evaluateJavascript("(window.treeBack && window.treeBack()) ? 'y' : 'n'", value -> {
                    if ("\"y\"".equals(value)) return;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastBack < EXIT_WINDOW_MS) {
                        finish();
                    } else {
                        lastBack = now;
                        Toast.makeText(MainActivity.this, "Press back again to exit", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
}
