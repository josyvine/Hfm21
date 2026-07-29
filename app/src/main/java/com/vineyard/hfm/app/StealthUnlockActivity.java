package com.vineyard.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * Enables or disables the visibility of stealth options inside HFM slider menu.
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
            tvDescription.setText("Identity Verified. Would you like to RESTORE (UNHIDE) the HFM Hide & Stealth slider options?");
            btnToggle.setText("UNHIDE SLIDER OPTIONS");
        } else {
            tvDescription.setText("Identity Verified. Would you like to HIDE the HFM Hide & Stealth slider options?");
            btnToggle.setText("HIDE SLIDER OPTIONS");
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
                .setSubtitle("Confirm fingerprint to change slider option visibility")
                .setNegativeButtonText("Cancel")
                .build();
    }

    private void executeToggle() {
        SharedPreferences prefs = getSharedPreferences("hfm_stealth_prefs", Context.MODE_PRIVATE);

        if (isCurrentlyHidden) {
            // UNHIDE SLIDER OPTIONS
            prefs.edit().putBoolean("is_stealth_hidden", false).apply();
            Toast.makeText(this, "HFM Slider Options RESTORED.", Toast.LENGTH_LONG).show();
        } else {
            // HIDE SLIDER OPTIONS
            prefs.edit().putBoolean("is_stealth_hidden", true).apply();
            Toast.makeText(this, "HFM Slider Options HIDDEN.", Toast.LENGTH_LONG).show();
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);

        finish();
    }
}