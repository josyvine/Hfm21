package com.vineyard.hfm.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity for the Receiver to scan the Sender's QR Code.
 * Handles both Option A (Network Pairing) and Option B (Instant File Drop) payloads automatically:
 * - Option A (NETWORK): Connects to Sender's Firebase database permanently and saves host name.
 * - Option B (INSTANT_DROP): Connects to Sender's DB and immediately launches DownloadService.
 */
@androidx.camera.core.ExperimentalGetImage
public class ClientQrScanActivity extends ComponentActivity {

    private static final String TAG = "ClientQrScanActivity";
    private static final int PERMISSION_REQUEST_CAMERA = 2001;

    private PreviewView viewFinder;
    private ImageButton btnBackScan;
    private TextView tvScanStatus;
    private Button btnUploadQrGallery;
    private ProgressBar progressBar;

    private ExecutorService cameraExecutor;
    private BarcodeScanner scanner;
    private boolean isProcessing = false;

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        processGalleryImage(imageUri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_qr_scan);

        initializeViews();
        setupListeners();

        cameraExecutor = Executors.newSingleThreadExecutor();

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        scanner = BarcodeScanning.getClient(options);

        if (hasCameraPermission()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
        }
    }

    private void initializeViews() {
        viewFinder = findViewById(R.id.view_finder_qr);
        btnBackScan = findViewById(R.id.btn_back_scan);
        tvScanStatus = findViewById(R.id.tv_scan_status);
        btnUploadQrGallery = findViewById(R.id.btn_upload_qr_gallery);
        progressBar = findViewById(R.id.progressBarScan);
    }

    private void setupListeners() {
        btnBackScan.setOnClickListener(v -> finish());

        btnUploadQrGallery.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            galleryLauncher.launch(intent);
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera Provider initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "Camera use case binding failed", e);
        }
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (isProcessing || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty()) {
                        String rawValue = barcodes.get(0).getRawValue();
                        if (rawValue != null) {
                            handleScannedQrPayload(rawValue);
                        }
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Live QR scanning analysis error", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void processGalleryImage(Uri uri) {
        if (isProcessing) return;

        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            progressBar.setVisibility(View.VISIBLE);

            scanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        if (!barcodes.isEmpty()) {
                            String rawValue = barcodes.get(0).getRawValue();
                            if (rawValue != null) {
                                handleScannedQrPayload(rawValue);
                            }
                        } else {
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(this, "No QR Code found in selected image.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .addOnFailureListener(e -> {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "Failed to read image file.", Toast.LENGTH_SHORT).show();
                    });
        } catch (IOException e) {
            Log.e(TAG, "Gallery image loading failed", e);
        }
    }

    private void handleScannedQrPayload(String encryptedPayload) {
        if (isProcessing) return;
        isProcessing = true;

        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            tvScanStatus.setText("Decrypting payload & connecting...");
        });

        // 1. Decrypt QR Code payload using AES-256
        String decryptedJson = EncryptionHelper.getInstance(this).decryptQrPayload(encryptedPayload);

        if (decryptedJson == null) {
            resetScanState("Invalid or corrupted HFM QR Code.");
            return;
        }

        try {
            // 2. Parse JSON Payload
            JSONObject wrapper = new JSONObject(decryptedJson);

            String type = wrapper.optString("type", ClientQrGenerateActivity.MODE_NETWORK);
            String firebaseConfigStr = wrapper.getString("firebaseConfig");
            String companyName = wrapper.getString("companyName");
            String projectId = wrapper.getString("projectId");

            // Extract drop fields safely
            String dropRequestId = wrapper.optString("dropRequestId", "");
            String secretNumber = wrapper.optString("secretNumber", "");

            // Save company/host name into local receiver username history
            if (companyName != null && !companyName.isEmpty()) {
                EncryptionHelper.getInstance(this).saveReceiverUsername(companyName);
            }

            // 3. Configure local secondary Firebase database
            boolean success = FirebaseManager.setConfiguration(this, firebaseConfigStr, companyName, projectId);

            if (success) {
                FirebaseManager.initialize(this);
                EncryptionHelper.getInstance(this).saveUserRole("receiver");

                runOnUiThread(() -> {
                    // Option A: Network Pairing Payload
                    if (ClientQrGenerateActivity.MODE_NETWORK.equals(type)) {
                        Toast.makeText(this, "Successfully connected to " + companyName + "!", Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(ClientQrScanActivity.this, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    } 
                    // Option B: Instant File Drop Payload
                    else if (ClientQrGenerateActivity.MODE_INSTANT_DROP.equals(type)) {
                        Toast.makeText(this, "Starting Instant File Drop download...", Toast.LENGTH_SHORT).show();

                        // Launch DownloadService directly with auto-extracted parameters
                        Intent serviceIntent = new Intent(ClientQrScanActivity.this, DownloadService.class);
                        serviceIntent.putExtra("drop_request_id", dropRequestId);
                        serviceIntent.putExtra("secret_number", secretNumber);
                        ContextCompat.startForegroundService(ClientQrScanActivity.this, serviceIntent);

                        // Open progress monitor screen
                        Intent progressIntent = new Intent(ClientQrScanActivity.this, DropProgressActivity.class);
                        progressIntent.putExtra("is_sender", false);
                        startActivity(progressIntent);
                        finish();
                    }
                });
            } else {
                resetScanState("Configuration error. Failed to mount secondary database.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing decrypted QR payload", e);
            resetScanState("Unsupported QR Code format.");
        }
    }

    private void resetScanState(String errorMsg) {
        runOnUiThread(() -> {
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
            progressBar.setVisibility(View.GONE);
            tvScanStatus.setText("Position the QR Code within the frame to connect.");
            isProcessing = false;
        });
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (hasCameraPermission()) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required for live QR scanning.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}