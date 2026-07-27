package com.vineyard.hfm.app;

import android.app.Application;
import android.util.Log;

/**
 * Custom Application class for HFM.
 * This is the primary entry point of the process.
 * Initializes the dynamic Firebase configuration system globally before any
 * Activities, Services, or BroadcastReceivers execute.
 */
public class HfmApplication extends Application {

    private static final String TAG = "HfmApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Initializing HFM Application process context...");

        // Initialize FirebaseManager to load Developer Central Auth ([DEFAULT]) 
        // and saved Client Firebase ("client_hfm_app") instances at boot.
        FirebaseManager.initialize(this);
    }
}