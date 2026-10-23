package com.boidbit.treesteps;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TreeStepsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
