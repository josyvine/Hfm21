package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Activity for the Sender to generate and display encrypted QR Codes for:
 * 1. Option A (NETWORK): One-Time Network Pairing (generated before file upload).
 * 2. Option B (INSTANT_DROP): Direct One-Step Single/Batch File Drop (generated after upload & sharding).
 */
public class ClientQrGenerateActivity extends ComponentActivity {

    private static final String TAG = "ClientQrGenerateActivity";

    public static final String EXTRA_MODE = "extra_mode"; // "NETWORK" or "INSTANT_DROP"
    public static final String EXTRA_DROP_REQUEST_ID = "extra_drop_request_id";
    public static final String EXTRA_SECRET_NUMBER = "extra_secret_number";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_FILE_SIZE = "extra_file_size";

    public static final String MODE_NETWORK = "NETWORK";
    public static final String MODE_INSTANT_DROP = "INSTANT_DROP";

    private ImageButton btnBackQr;
    private TextView tvQrTitle;
    private TextView tvQrSubtitle;
    private ImageView ivQrCode;
    private TextView tvQrInstruction;
    private TextView tvSecretPinDisplay;
    private Button btnShareQr;
    private Button btnGenerateNetworkQr;
    private ProgressBar progressBarQr;

    private Bitmap generatedQrBitmap;
    private String currentMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_qr_generate);

        initializeViews();
        setupListeners();

        Intent intent = getIntent();
        currentMode = intent.getStringExtra(EXTRA_MODE);
        if (currentMode == null) {
            currentMode = MODE_NETWORK; // Default to Network Pairing QR
        }

        if (MODE_INSTANT_DROP.equals(currentMode)) {
            String dropRequestId = intent.getStringExtra(EXTRA_DROP_REQUEST_ID);
            String secretNumber = intent.getStringExtra(EXTRA_SECRET_NUMBER);
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            long fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0);

            generateInstantDropQr(dropRequestId, secretNumber, fileName, fileSize);
        } else {
            generateNetworkQr();
        }
    }

    private void initializeViews() {
        btnBackQr = findViewById(R.id.btn_back_qr);
        tvQrTitle = findViewById(R.id.tv_qr_title);
        tvQrSubtitle = findViewById(R.id.tv_qr_subtitle);
        ivQrCode = findViewById(R.id.iv_qr_code);
        tvQrInstruction = findViewById(R.id.tv_qr_instruction);
        tvSecretPinDisplay = findViewById(R.id.tv_secret_pin_display);
        btnShareQr = findViewById(R.id.btn_share_qr);
        btnGenerateNetworkQr = findViewById(R.id.btn_generate_network_qr);
        progressBarQr = findViewById(R.id.progressBarQr);
    }

    private void setupListeners() {
        btnBackQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnShareQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (generatedQrBitmap != null) {
                    shareQrImage();
                } else {
                    Toast.makeText(ClientQrGenerateActivity.this, "QR Code is generating, please wait...", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnGenerateNetworkQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentMode = MODE_NETWORK;
                generateNetworkQr();
            }
        });
    }

    /**
     * OPTION A: Generates Network Pairing QR Code (Before File Upload)
     */
    private void generateNetworkQr() {
        tvQrTitle.setText("Network Pairing QR");
        tvQrSubtitle.setText("Have the Receiver scan this once to pair with your network.");
        tvSecretPinDisplay.setVisibility(View.GONE);
        btnGenerateNetworkQr.setVisibility(View.GONE);

        EncryptionHelper helper = EncryptionHelper.getInstance(this);
        String configJson = helper.getFirebaseConfig();
        String companyName = helper.getCompanyName();
        String projectId = helper.getProjectId();

        if (configJson == null || projectId == null) {
            Toast.makeText(this, "Firebase Configuration missing. Please run setup first.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("type", MODE_NETWORK);
            payload.put("firebaseConfig", configJson);
            payload.put("companyName", companyName);
            payload.put("projectId", projectId);
            payload.put("timestamp", System.currentTimeMillis());

            tvQrInstruction.setText("Network: " + companyName);
            encodeAndDisplayQr(payload.toString());

        } catch (Exception e) {
            Log.e(TAG, "Network QR Generation failed", e);
            Toast.makeText(this, "Failed to generate Network QR Code.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * OPTION B: Generates Instant File Drop QR Code (After File Upload & Sharding)
     */
    private void generateInstantDropQr(String dropRequestId, String secretNumber, String fileName, long fileSize) {
        tvQrTitle.setText("Instant File Drop QR");
        tvQrSubtitle.setText("Scan to connect and download this file in 1 second!");
        btnGenerateNetworkQr.setVisibility(View.VISIBLE);

        if (secretNumber != null) {
            tvSecretPinDisplay.setText("Secret PIN: " + secretNumber);
            tvSecretPinDisplay.setVisibility(View.VISIBLE);
        }

        EncryptionHelper helper = EncryptionHelper.getInstance(this);
        String configJson = helper.getFirebaseConfig();
        String companyName = helper.getCompanyName();
        String projectId = helper.getProjectId();

        if (configJson == null || projectId == null || dropRequestId == null) {
            Toast.makeText(this, "Error: Incomplete drop request details.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("type", MODE_INSTANT_DROP);
            payload.put("firebaseConfig", configJson);
            payload.put("companyName", companyName);
            payload.put("projectId", projectId);
            payload.put("dropRequestId", dropRequestId);
            payload.put("secretNumber", secretNumber);
            payload.put("filename", fileName != null ? fileName : "Shared File");
            payload.put("filesize", fileSize);
            payload.put("timestamp", System.currentTimeMillis());

            tvQrInstruction.setText("File: " + (fileName != null ? fileName : "Shared File"));
            encodeAndDisplayQr(payload.toString());

        } catch (Exception e) {
            Log.e(TAG, "Instant Drop QR Generation failed", e);
            Toast.makeText(this, "Failed to generate Instant Drop QR Code.", Toast.LENGTH_SHORT).show();
        }
    }

    private void encodeAndDisplayQr(String plainJsonPayload) {
        progressBarQr.setVisibility(View.VISIBLE);
        ivQrCode.setVisibility(View.INVISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String encryptedPayload = EncryptionHelper.getInstance(ClientQrGenerateActivity.this)
                            .encryptQrPayload(plainJsonPayload);

                    if (encryptedPayload != null) {
                        MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
                        BitMatrix bitMatrix = multiFormatWriter.encode(encryptedPayload, BarcodeFormat.QR_CODE, 512, 512);
                        BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
                        final Bitmap bitmap = barcodeEncoder.createBitmap(bitMatrix);

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                generatedQrBitmap = bitmap;
                                ivQrCode.setImageBitmap(generatedQrBitmap);
                                progressBarQr.setVisibility(View.GONE);
                                ivQrCode.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                } catch (WriterException e) {
                    Log.e(TAG, "ZXing QR Encoding error", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressBarQr.setVisibility(View.GONE);
                            Toast.makeText(ClientQrGenerateActivity.this, "QR Code encoding error.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void shareQrImage() {
        try {
            File cachePath = new File(getCacheDir(), "images");
            if (!cachePath.exists()) {
                cachePath.mkdirs();
            }

            File newFile = new File(cachePath, "hfm_share_qr.png");
            FileOutputStream stream = new FileOutputStream(newFile);
            generatedQrBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            stream.close();

            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", newFile);

            if (contentUri != null) {
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "HFM Network QR Code");
                shareIntent.putExtra(Intent.EXTRA_TEXT, "Scan this QR code using HFM App to connect.");

                startActivity(Intent.createChooser(shareIntent, "Share QR via:"));
            }
        } catch (IOException e) {
            Log.e(TAG, "Sharing QR Image failed", e);
            Toast.makeText(this, "Could not share QR image.", Toast.LENGTH_SHORT).show();
        }
    }
}