package com.vineyard.hfm.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Startup Splash Activity for HFM.
 * Checks whether the app has an active Client Firebase configuration loaded.
 * - If setup is complete: Automatically navigates to MainActivity.
 * - If setup is missing: Displays option choices (Sender JSON Setup vs. Receiver QR Scan).
 */
@SuppressLint("CustomSplashScreen")
public class SplashSetupActivity extends AppCompatActivity {

    private static final int SPLASH_DELAY_MS = 1500; // 1.5 seconds

    private ProgressBar progressBarSplash;
    private LinearLayout layoutSetupOptions;
    private Button btnSetupSender;
    private Button btnScanReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash_setup);

        initializeViews();
        setupListeners();

        // Delayed check to allow splash screen branding to present smoothly
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                checkConfigurationAndRoute();
            }
        }, SPLASH_DELAY_MS);
    }

    private void initializeViews() {
        progressBarSplash = findViewById(R.id.progressBarSplash);
        layoutSetupOptions = findViewById(R.id.layout_setup_options);
        btnSetupSender = findViewById(R.id.btn_setup_sender);
        btnScanReceiver = findViewById(R.id.btn_scan_receiver);
    }

    private void setupListeners() {
        btnSetupSender.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SplashSetupActivity.this, ClientSetupActivity.class);
                startActivity(intent);
                finish();
            }
        });

        btnScanReceiver.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SplashSetupActivity.this, ClientQrScanActivity.class);
                startActivity(intent);
                finish();
            }
        });
    }

    private void checkConfigurationAndRoute() {
        EncryptionHelper encryptionHelper = EncryptionHelper.getInstance(this);

        if (encryptionHelper.isSetupDone()) {
            // Configuration exists -> Load main app manager interface
            Intent intent = new Intent(SplashSetupActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            // Configuration missing -> Show onboarding choices
            progressBarSplash.setVisibility(View.GONE);
            layoutSetupOptions.setVisibility(View.VISIBLE);
        }
    }
}