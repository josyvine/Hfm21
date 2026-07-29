package com.vineyard.hfm.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

/**
 * Biometric gate activity invoked via dialer notification.
 * Enables or disables the launcher component for MainActivity.
 */
public class StealthUnlockActivity extends FragmentActivity {

    private TextView tvDescription;
    private Button btnToggle;
    private Button btnCancel;

    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;
    private boolean isCurrentlyHidden;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stealth_unlock);

        tvDescription = findViewById(R.id.tv_stealth_description);
        btnToggle = findViewById(R.id.btn_stealth_toggle);
        btnCancel = findViewById(R.id.btn_stealth_cancel);

        SharedPreferences prefs = getSharedPreferences("hfm_stealth_prefs", Context.MODE_PRIVATE);
        isCurrentlyHidden = prefs.getBoolean("is_stealth_hidden", false);

        updateUI();
        setupBiometrics();

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                biometricPrompt.authenticate(promptInfo);
            }
        });
    }

    private void updateUI() {
        if (isCurrentlyHidden) {
            tvDescription.setText("Identity Verified. Would you like to RESTORE (UNHIDE) the HFM App icon and slider options?");
            btnToggle.setText("UNHIDE APP & SLIDER");
        } else {
            tvDescription.setText("Identity Verified. Would you like to HIDE the HFM App icon and slider options?");
            btnToggle.setText("HIDE APP & SLIDER");
        }
    }

    private void setupBiometrics() {
        Executor executor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                executeToggle();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(StealthUnlockActivity.this, "Authentication error: " + errString, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(StealthUnlockActivity.this, "Fingerprint not recognized.", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFM Stealth Gate")
                .setSubtitle("Confirm fingerprint to change visibility")
                .setNegativeButtonText("Cancel")
                .build();
    }

    private void executeToggle() {
        PackageManager pm = getPackageManager();
        ComponentName componentName = new ComponentName(this, MainActivity.class);
        SharedPreferences prefs = getSharedPreferences("hfm_stealth_prefs", Context.MODE_PRIVATE);

        if (isCurrentlyHidden) {
            // UNHIDE
            pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
            prefs.edit().putBoolean("is_stealth_hidden", false).apply();
            Toast.makeText(this, "HFM App Icon and Slider items RESTORED.", Toast.LENGTH_LONG).show();

            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } else {
            // HIDE
            pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
            prefs.edit().putBoolean("is_stealth_hidden", true).apply();
            Toast.makeText(this, "HFM App Icon and Slider items HIDDEN.", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}