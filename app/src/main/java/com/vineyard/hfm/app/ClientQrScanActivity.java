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

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity for the Receiver to scan the Sender's QR Code.
 * Handles both Option A (Network Pairing) and Option B (Instant File Drop) payloads automatically:
 * - Option A (NETWORK): Connects to Sender's Firebase database permanently, authenticates via Email/Password bridge, registers presence on network_peers, and saves host name.
 * - Option B (INSTANT_DROP): Connects to Sender's DB and immediately launches DownloadService.
 */
@androidx.camera.core.ExperimentalGetImage
public class ClientQrScanActivity extends ComponentActivity {

    private static final String TAG = "ClientQrScanActivity";
    private static final int PERMISSION_REQUEST_CAMERA = 2001;

    private static final String[] ADJECTIVES = {"Red", "Blue", "Green", "Silent", "Fast", "Brave", "Ancient", "Wandering", "Golden", "Iron"};
    private static final String[] NOUNS = {"Tiger", "Lion", "Eagle", "Fox", "Wolf", "River", "Mountain", "Star", "Comet", "Shadow"};

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
            String senderUsername = wrapper.optString("senderUsername", "");

            // Save company/host name and sender username into local receiver username history
            if (companyName != null && !companyName.trim().isEmpty()) {
                EncryptionHelper.getInstance(this).saveReceiverUsername(companyName.trim());
            }
            if (senderUsername != null && !senderUsername.trim().isEmpty()) {
                EncryptionHelper.getInstance(this).saveReceiverUsername(senderUsername.trim());
            }

            // 3. Configure local secondary Firebase database
            boolean success = FirebaseManager.setConfiguration(this, firebaseConfigStr, companyName, projectId);

            if (success) {
                FirebaseManager.initialize(this);
                EncryptionHelper.getInstance(this).saveUserRole("receiver");

                // REGISTER PRESENCE IN FIRESTORE network_peers COLLECTION SO SENDER SEES US IN DROPDOWN
                registerReceiverPresenceOnCloud(companyName);

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

    /**
     * Registers this receiver's presence in the shared Firestore network_peers collection.
     * Authenticates with Client Firebase via Email/Password Silent Auth Bridge.
     */
    private void registerReceiverPresenceOnCloud(String networkName) {
        try {
            FirebaseApp clientApp = FirebaseApp.getInstance(FirebaseManager.CLIENT_APP_NAME);
            FirebaseAuth clientAuth = FirebaseAuth.getInstance(clientApp);
            FirebaseFirestore clientDb = FirebaseFirestore.getInstance(clientApp);

            if (clientAuth.getCurrentUser() != null) {
                publishPresenceDocument(clientAuth.getCurrentUser().getUid(), networkName, clientDb);
            } else {
                // Check if Central Google User exists for Email/Password Silent Auth
                FirebaseAuth centralAuth = FirebaseAuth.getInstance();
                if (centralAuth.getCurrentUser() != null && centralAuth.getCurrentUser().getEmail() != null) {
                    String email = centralAuth.getCurrentUser().getEmail();
                    String centralUid = centralAuth.getCurrentUser().getUid();
                    String derivedPassword = calculateSecurePassword(email, centralUid);

                    clientAuth.signInWithEmailAndPassword(email, derivedPassword)
                            .addOnCompleteListener(task -> {
                                if (task.isSuccessful() && clientAuth.getCurrentUser() != null) {
                                    publishPresenceDocument(clientAuth.getCurrentUser().getUid(), networkName, clientDb);
                                } else {
                                    // Create account on Client Firebase if it doesn't exist
                                    clientAuth.createUserWithEmailAndPassword(email, derivedPassword)
                                            .addOnCompleteListener(createTask -> {
                                                if (createTask.isSuccessful() && clientAuth.getCurrentUser() != null) {
                                                    publishPresenceDocument(clientAuth.getCurrentUser().getUid(), networkName, clientDb);
                                                } else {
                                                    // Fallback to anonymous authentication
                                                    clientAuth.signInAnonymously().addOnCompleteListener(anonTask -> {
                                                        if (anonTask.isSuccessful() && clientAuth.getCurrentUser() != null) {
                                                            publishPresenceDocument(clientAuth.getCurrentUser().getUid(), networkName, clientDb);
                                                        }
                                                    });
                                                }
                                            });
                                }
                            });
                } else {
                    // Fallback to anonymous sign-in if no central account exists
                    clientAuth.signInAnonymously().addOnCompleteListener(task -> {
                        if (task.isSuccessful() && clientAuth.getCurrentUser() != null) {
                            publishPresenceDocument(clientAuth.getCurrentUser().getUid(), networkName, clientDb);
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering presence on cloud network_peers", e);
        }
    }

    private String calculateSecurePassword(String email, String centralUid) {
        try {
            String input = email + centralUid + "HfmSecurePasswordSalt2026";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16);
        } catch (Exception e) {
            Log.e(TAG, "Password hash calculation error", e);
            return "HfmFallbackPass123!";
        }
    }

    private void publishPresenceDocument(String uid, String networkName, FirebaseFirestore clientDb) {
        String myUsername = generateUsernameFromUid(uid);

        // Save self username locally
        EncryptionHelper.getInstance(this).saveReceiverUsername(myUsername);

        Map<String, Object> peerData = new HashMap<>();
        peerData.put("username", myUsername);
        peerData.put("networkName", networkName != null ? networkName : "");
        peerData.put("lastSeen", System.currentTimeMillis());
        peerData.put("deviceRole", "receiver");

        clientDb.collection("network_peers").document(myUsername)
                .set(peerData)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Registered presence on network_peers: " + myUsername))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to register presence on network_peers", e));
    }

    private String generateUsernameFromUid(String uid) {
        long hash = uid.hashCode();
        int adjIndex = (int) (Math.abs(hash % ADJECTIVES.length));
        int nounIndex = (int) (Math.abs((hash / ADJECTIVES.length) % NOUNS.length));
        int number = (int) (Math.abs((hash / (ADJECTIVES.length * NOUNS.length)) % 100));
        return ADJECTIVES[adjIndex] + "-" + NOUNS[nounIndex] + "-" + number;
    }

    private void resetScanState(String errorMsg) {
        runOnUiThread(() -> {
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
            progressBar.setVisibility(View.VISIBLE);
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
