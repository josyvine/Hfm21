package com.vineyard.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Locale;

public class DropProgressActivity extends Activity {

    // Actions defined in services
    public static final String ACTION_UPDATE_STATUS = "com.vineyard.hfm.app.action.UPDATE_STATUS";
    public static final String ACTION_TRANSFER_COMPLETE = "com.vineyard.hfm.app.action.TRANSFER_COMPLETE";
    public static final String ACTION_TRANSFER_ERROR = "com.vineyard.hfm.app.action.TRANSFER_ERROR";

    // Extras for the intent
    public static final String EXTRA_STATUS_MAJOR = "status_major";
    public static final String EXTRA_STATUS_MINOR = "status_minor";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_MAX_PROGRESS = "max_progress";
    public static final String EXTRA_BYTES_TRANSFERRED = "bytes_transferred";

    // UI Elements
    private TextView titleTextView, statusMajorTextView, statusMinorTextView, progressDetailsTextView;
    private ProgressBar progressBar;
    private Button cancelButton;

    private BroadcastReceiver statusReceiver;
    private boolean isSender;
    private boolean isTransferCompleteOrErrored = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drop_progress);

        initializeViews();
        isSender = getIntent().getBooleanExtra("is_sender", false);
        setupInitialState();
        setupListeners();
        setupBroadcastReceiver();
    }

    private void initializeViews() {
        titleTextView = findViewById(R.id.transfer_status_title);
        statusMajorTextView = findViewById(R.id.status_text_major);
        statusMinorTextView = findViewById(R.id.status_text_minor);
        progressDetailsTextView = findViewById(R.id.progress_details_text);
        progressBar = findViewById(R.id.progress_bar_drop);
        cancelButton = findViewById(R.id.button_cancel_drop);
    }
    
    private void setupInitialState() {
        if (isSender) {
            titleTextView.setText("HFM Morphing - Sending");
        } else {
            titleTextView.setText("HFM Morphing - Receiving");
        }
    }

    private void setupListeners() {
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isTransferCompleteOrErrored) {
                    finish();
                    return;
                }
                
                Intent serviceIntent;
                if (isSender) {
                    serviceIntent = new Intent(DropProgressActivity.this, SenderService.class);
                } else {
                    serviceIntent = new Intent(DropProgressActivity.this, DownloadService.class);
                }
                stopService(serviceIntent);
                finish();
            }
        });
    }
    
    private void setupBroadcastReceiver() {
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                if (ACTION_UPDATE_STATUS.equals(action)) {
                    String major = intent.getStringExtra(EXTRA_STATUS_MAJOR);
                    String minor = intent.getStringExtra(EXTRA_STATUS_MINOR);
                    int progress = intent.getIntExtra(EXTRA_PROGRESS, -1);
                    int max = intent.getIntExtra(EXTRA_MAX_PROGRESS, -1);
                    long bytes = intent.getLongExtra(EXTRA_BYTES_TRANSFERRED, -1);
                    
                    if (major != null) statusMajorTextView.setText(major);
                    if (minor != null) statusMinorTextView.setText(minor);

                    // Update Progress Bar
                    if (progress != -1 && max != -1) {
                        progressBar.setIndeterminate(false);
                        progressBar.setMax(max);
                        progressBar.setProgress(progress);
                    } else {
                        progressBar.setIndeterminate(true);
                    }
                    
                    // Update Progress Details (Transferred vs Total)
                    if (bytes != -1 && max != -1) {
                        String bytesStr = Formatter.formatFileSize(context, bytes);
                        progressDetailsTextView.setText(String.format(Locale.US, "Processed: %s", bytesStr));
                    } else {
                        progressDetailsTextView.setText("");
                    }
                    
                } else if (ACTION_TRANSFER_COMPLETE.equals(action)) {
                    isTransferCompleteOrErrored = true;
                    statusMajorTextView.setText("Morphing Complete!");
                    statusMinorTextView.setText("File safely reconstructed in Vault.");
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(progressBar.getMax());
                    
                    final String originalFileName = intent.getStringExtra("original_file_name");
                    final String vaultFilePath = intent.getStringExtra("vault_file_path");

                    if (!isSender && vaultFilePath != null && originalFileName != null) {
                        // GLITCH 2 FIX: Resolve context-aware action label dynamically
                        SecureVaultManager vaultManager = new SecureVaultManager(DropProgressActivity.this);
                        String actionLabel = vaultManager.getActionLabel(originalFileName);

                        cancelButton.setText(actionLabel);
                        cancelButton.setBackgroundColor(android.graphics.Color.parseColor("#4f46e5")); 
                        cancelButton.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                java.io.File vaultFile = new java.io.File(vaultFilePath);
                                new SecureVaultManager(DropProgressActivity.this).playSecurely(vaultFile, originalFileName);
                            }
                        });
                    } else {
                        cancelButton.setText("Done");
                    }

                } else if (DownloadService.ACTION_DOWNLOAD_ERROR.equals(action)) {
                    isTransferCompleteOrErrored = true;
                    statusMajorTextView.setText("Transfer Failed");
                    statusMinorTextView.setText("Please check the error report.");
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    cancelButton.setText("Close");

                    String errorReport = intent.getStringExtra(DownloadService.EXTRA_ERROR_MESSAGE);
                    if (errorReport != null && !errorReport.isEmpty()) {
                        showErrorDialog(errorReport);
                    } else {
                        showErrorDialog("An unknown transfer error occurred.");
                    }
                }
            }
        };
    }
    
    private void showErrorDialog(String errorReport) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Transfer Failed: Error Report");
        builder.setMessage(errorReport);
        builder.setPositiveButton("Close", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_UPDATE_STATUS);
        filter.addAction(ACTION_TRANSFER_COMPLETE);
        filter.addAction(DownloadService.ACTION_DOWNLOAD_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }
    
    @Override
    public void onBackPressed() {
        if (!isTransferCompleteOrErrored) {
            Toast.makeText(this, "Please cancel the transfer to go back.", Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }
}