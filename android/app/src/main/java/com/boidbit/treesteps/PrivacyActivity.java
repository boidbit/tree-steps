package com.boidbit.treesteps;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

/**
 * The privacy policy, bundled with the app. Health Connect opens it when someone asks why Tree Steps
 * reads their steps (and Android 14+ from the app's permission usage screen).
 */
public class PrivacyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        web.setFitsSystemWindows(true);
        setContentView(web);
        web.loadUrl("file:///android_asset/public/privacy.html");
    }
}
