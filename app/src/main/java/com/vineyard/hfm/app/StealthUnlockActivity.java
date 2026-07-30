package com.vineyard.hfm.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

/**
 * Hybrid gate activity invoked via dialer notification.
 * Enables or disables the visibility of stealth options inside HFM slider menu
 * using Fingerprint scan with automatic Stealth PIN fallback.
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
                BiometricManager biometricManager = BiometricManager.from(StealthUnlockActivity.this);
                int canAuthenticate = biometricManager.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.BIOMETRIC_WEAK
                );

                if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                    biometricPrompt.authenticate(promptInfo);
                } else {
                    // Fingerprint not enrolled or hardware unavailable -> fallback directly to PIN
                    showPinFallbackDialog();
                }
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
                // On error (e.g. no fingerprints, hardware disabled, or user clicked "Use PIN") -> open PIN dialog
                showPinFallbackDialog();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(StealthUnlockActivity.this, "Fingerprint not recognized. Try again or tap 'Use PIN'.", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFM Stealth Gate")
                .setSubtitle("Confirm fingerprint or enter PIN to change slider option visibility")
                .setNegativeButtonText("Use PIN")
                .build();
    }

    /**
     * Fallback authentication method when fingerprint is unavailable or fails.
     * Prompts for the 4-digit PIN configured in Stealth Manager.
     */
    private void showPinFallbackDialog() {
        if (isFinishing() || isDestroyed()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
        final AutoCompleteTextView pinInput = dialogView.findViewById(R.id.edit_text_receiver_username);
        pinInput.setHint("Enter 4-digit Stealth PIN");

        builder.setTitle("Enter Stealth Manager PIN")
                .setView(dialogView)
                .setPositiveButton("Verify", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String enteredPin = pinInput.getText().toString().trim();
                        SharedPreferences prefs = getSharedPreferences("hfm_stealth_prefs", Context.MODE_PRIVATE);
                        String savedPin = prefs.getString("stealth_pin", "");

                        if (enteredPin.equals(savedPin) && !savedPin.isEmpty()) {
                            Toast.makeText(StealthUnlockActivity.this, "PIN Verified Successfully!", Toast.LENGTH_SHORT).show();
                            executeToggle();
                        } else {
                            Toast.makeText(StealthUnlockActivity.this, "Incorrect PIN. Access Denied.", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });

        builder.create().show();
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
