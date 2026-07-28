package com.vineyard.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Service handling file encryption, polymorphic sharding, Google Drive upload,
 * and batch handshake creation on the Sender's private Client Firebase database.
 * Supports paired network direct transfer (no QR) and single-QR batch instant drop.
 */
public class SenderService extends Service {

    private static final String TAG = "SenderService";
    private static final String NOTIFICATION_CHANNEL_ID = "SenderServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START_SEND = "com.vineyard.hfm.app.action.START_SEND";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_PATHS = "file_paths";
    public static final String EXTRA_RECEIVER_USERNAME = "receiver_username";
    public static final String EXTRA_SECRET_NUMBER = "secret_number";

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private String dropRequestId;
    private ListenerRegistration requestListener;

    private static final String[] ADJECTIVES = {"Red", "Blue", "Green", "Silent", "Fast", "Brave", "Ancient", "Wandering", "Golden", "Iron"};
    private static final String[] NOUNS = {"Tiger", "Lion", "Eagle", "Fox", "Wolf", "River", "Mountain", "Star", "Comet", "Shadow"};

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            FirebaseApp clientApp = FirebaseApp.getInstance(FirebaseManager.CLIENT_APP_NAME);
            mAuth = FirebaseAuth.getInstance(clientApp);
            db = FirebaseFirestore.getInstance(clientApp);
            Log.d(TAG, "SenderService successfully bound to secondary Client Firestore.");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary client app not mounted. Falling back to default instance.", e);
            mAuth = FirebaseAuth.getInstance();
            db = FirebaseFirestore.getInstance();
        }

        currentUser = mAuth.getCurrentUser();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_SEND.equals(intent.getAction())) {
            final String receiverUsername = intent.getStringExtra(EXTRA_RECEIVER_USERNAME);
            final String secretNumber = intent.getStringExtra(EXTRA_SECRET_NUMBER);

            ArrayList<String> filePaths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS);
            if (filePaths == null || filePaths.isEmpty()) {
                String singlePath = intent.getStringExtra(EXTRA_FILE_PATH);
                if (singlePath != null && !singlePath.isEmpty()) {
                    filePaths = new ArrayList<>();
                    filePaths.add(singlePath);
                }
            }

            final ArrayList<String> finalFilePaths = filePaths;

            Notification notification = buildNotification("Initializing Secure Drop...", true);
            startForeground(NOTIFICATION_ID, notification);

            Intent progressIntent = new Intent(this, DropProgressActivity.class);
            progressIntent.putExtra("is_sender", true);
            progressIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(progressIntent);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    startSenderBatchProcess(finalFilePaths, receiverUsername, secretNumber);
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    private void startSenderBatchProcess(final ArrayList<String> filePaths, final String receiverUsername, final String secretNumber) {
        if (filePaths == null || filePaths.isEmpty()) {
            broadcastError("Error: No valid file paths provided for transfer.");
            stopServiceAndCleanup(null);
            return;
        }

        List<File> inputFiles = new ArrayList<>();
        long totalBatchSize = 0;
        for (String path : filePaths) {
            File f = new File(path);
            if (f.exists()) {
                inputFiles.add(f);
                totalBatchSize += f.length();
            }
        }

        if (inputFiles.isEmpty()) {
            broadcastError("Error: Selected files do not exist on disk.");
            stopServiceAndCleanup(null);
            return;
        }

        // Save target receiver username to preferences history
        if (receiverUsername != null && !receiverUsername.trim().isEmpty()) {
            EncryptionHelper.getInstance(this).saveReceiverUsername(receiverUsername.trim());
        }

        // Phase 1: Authentication & Smart Quota Check
        GoogleSignInAccount driveAccount = GoogleSignIn.getLastSignedInAccount(this);
        if (driveAccount == null) {
            broadcastError("Google Drive authentication failed. Please sign in again.");
            stopServiceAndCleanup(null);
            return;
        }

        updateNotification("Authenticating & Checking Quota...", true);
        broadcastStatus("Verifying Cloud Storage...", "Please wait...", -1, -1, -1);
        GoogleDriveManager driveManager = new GoogleDriveManager(this, driveAccount);

        if (!driveManager.hasEnoughQuota(totalBatchSize)) {
            broadcastError("Not enough Google Drive space. Required: " + Formatter.formatFileSize(this, (long) (totalBatchSize * 1.1)) + " + decoys.");
            stopServiceAndCleanup(null);
            return;
        }

        // Phase 2: Polymorphic Sharding & Encryption for Batch Items
        MorphedShardEngine shardEngine = new MorphedShardEngine(this, driveManager);
        List<Map<String, Object>> fileManifestList = new ArrayList<>();
        int totalFiles = inputFiles.size();

        for (int i = 0; i < totalFiles; i++) {
            final File currentFile = inputFiles.get(i);
            final int fileIndex = i + 1;

            updateNotification("Sharding (" + fileIndex + "/" + totalFiles + "): " + currentFile.getName(), true);
            broadcastStatus("Morphing Data (" + fileIndex + "/" + totalFiles + ")", currentFile.getName(), 0, 100, 0);

            String encryptedManifestId = shardEngine.executeShardingAndUpload(currentFile, secretNumber, new MorphedShardEngine.ProgressListener() {
                @Override
                public void onProgress(int progress, int max, long bytesProcessed) {
                    updateNotification("Encrypting (" + fileIndex + "/" + totalFiles + ") " + progress + "%", true);
                    broadcastStatus("Morphing Data (" + fileIndex + "/" + totalFiles + ")",
                            String.format(Locale.US, "%s / %s",
                                    Formatter.formatFileSize(getApplicationContext(), bytesProcessed),
                                    Formatter.formatFileSize(getApplicationContext(), currentFile.length())),
                            progress, max, bytesProcessed);
                }

                @Override
                public void onStatusUpdate(String minorStatus) {
                    updateNotification(minorStatus, true);
                    broadcastStatus("Morphing Data (" + fileIndex + "/" + totalFiles + ")", minorStatus, -1, -1, -1);
                }
            });

            if (encryptedManifestId == null) {
                broadcastError("Failed to shard and upload " + currentFile.getName() + " to Google Drive.");
                stopServiceAndCleanup(null);
                return;
            }

            Map<String, Object> fileMeta = new HashMap<>();
            fileMeta.put("originalFilename", currentFile.getName());
            fileMeta.put("filesize", currentFile.length());
            fileMeta.put("encryptedManifestId", encryptedManifestId);
            fileManifestList.add(fileMeta);
        }

        // Phase 3: Firebase Handshake on Client Database
        updateNotification("Creating secure handshake...", true);
        broadcastStatus("Creating Handshake...", "Contacting server...", -1, -1, -1);

        String currentUid = (currentUser != null) ? currentUser.getUid() : "anon_" + System.currentTimeMillis();
        String senderUsername = generateUsernameFromUid(currentUid);

        final String summaryFilename = (totalFiles == 1) ? inputFiles.get(0).getName() : totalFiles + " files batch";
        final long finalTotalBatchSize = totalBatchSize;
        String primaryManifestId = (String) fileManifestList.get(0).get("encryptedManifestId");

        Map<String, Object> dropRequest = new HashMap<>();
        dropRequest.put("senderId", currentUid);
        dropRequest.put("senderUsername", senderUsername);
        dropRequest.put("receiverUsername", receiverUsername != null ? receiverUsername.trim() : "ANY");
        dropRequest.put("originalFilename", summaryFilename);
        dropRequest.put("encryptedManifestId", primaryManifestId);
        dropRequest.put("filesize", finalTotalBatchSize);
        dropRequest.put("fileItems", fileManifestList); // Full batch items list
        dropRequest.put("status", "pending");
        dropRequest.put("timestamp", System.currentTimeMillis());

        db.collection("drop_requests").add(dropRequest)
            .addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                @Override
                public void onSuccess(DocumentReference documentReference) {
                    dropRequestId = documentReference.getId();
                    Log.d(TAG, "Batch Drop request created on Client Firestore with ID: " + dropRequestId);

                    // GLITCH 1 & 4 FIX: Check if receiver is paired via Permanent QR Code
                    boolean isPairedReceiver = EncryptionHelper.getInstance(SenderService.this).isReceiverPaired(receiverUsername);

                    if (!isPairedReceiver && (receiverUsername == null || receiverUsername.trim().isEmpty() || "ANY".equalsIgnoreCase(receiverUsername.trim()))) {
                        // Launch Instant Drop QR Code ONLY for unpaired/one-time instant drop receivers
                        launchInstantDropQrActivity(dropRequestId, secretNumber, summaryFilename, finalTotalBatchSize);
                    } else {
                        Log.d(TAG, "Target receiver '" + receiverUsername + "' is paired on Permanent Network. Bypassing QR Code display.");
                    }

                    updateNotification("Waiting for receiver...", true);
                    broadcastStatus("Waiting for Receiver...", "Request sent. Waiting for acceptance.", -1, -1, -1);
                    listenForStatusChange(dropRequestId);
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    Log.e(TAG, "Failed to create drop request on client database.", e);
                    broadcastError("Failed to create drop request on server.\n\n" + getStackTraceAsString(e));
                    stopServiceAndCleanup(null);
                }
            });
    }

    private void launchInstantDropQrActivity(String dropRequestId, String secretNumber, String fileName, long fileSize) {
        try {
            Intent qrIntent = new Intent(this, ClientQrGenerateActivity.class);
            qrIntent.putExtra(ClientQrGenerateActivity.EXTRA_MODE, ClientQrGenerateActivity.MODE_INSTANT_DROP);
            qrIntent.putExtra(ClientQrGenerateActivity.EXTRA_DROP_REQUEST_ID, dropRequestId);
            qrIntent.putExtra(ClientQrGenerateActivity.EXTRA_SECRET_NUMBER, secretNumber);
            qrIntent.putExtra(ClientQrGenerateActivity.EXTRA_FILE_NAME, fileName);
            qrIntent.putExtra(ClientQrGenerateActivity.EXTRA_FILE_SIZE, fileSize);
            qrIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(qrIntent);
        } catch (Exception e) {
            Log.e(TAG, "Could not launch Instant Drop QR Activity", e);
        }
    }

    private void broadcastStatus(String major, String minor, int progress, int max, long bytes) {
        Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
        intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, major);
        intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, minor);
        intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, progress);
        intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, max);
        intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, bytes);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastComplete() {
        Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastError(String message) {
        Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
        errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(errorIntent);
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
    }

    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    private void listenForStatusChange(String docId) {
        final DocumentReference docRef = db.collection("drop_requests").document(docId);
        requestListener = docRef.addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(DocumentSnapshot snapshot, FirebaseFirestoreException e) {
                if (e != null) {
                    Log.w(TAG, "Listen failed on client DB snapshot.", e);
                    return;
                }

                if (snapshot != null && snapshot.exists()) {
                    String status = snapshot.getString("status");
                    Log.d(TAG, "Drop request status changed to: " + status);

                    if ("accepted".equals(status)) {
                        updateNotification("Receiver connected. Monitoring download...", true);
                        broadcastStatus("Receiver Connected", "Monitoring secure download...", -1, -1, -1);
                    } else if ("declined".equals(status)) {
                        stopServiceAndCleanup("Receiver declined the transfer.");
                    } else if ("complete".equals(status)) {
                        broadcastComplete();
                        stopServiceAndCleanup(null);
                    } else if ("error".equals(status)) {
                        stopServiceAndCleanup("An error occurred on the receiver's end.");
                    }
                } else {
                    Log.d(TAG, "Drop request document deleted by receiver (likely on completion).");
                    stopServiceAndCleanup(null);
                }
            }
        });
    }

    private String generateUsernameFromUid(String uid) {
        long hash = uid.hashCode();
        int adjIndex = (int) (Math.abs(hash % ADJECTIVES.length));
        int nounIndex = (int) (Math.abs((hash / ADJECTIVES.length) % NOUNS.length));
        int number = (int) (Math.abs((hash / (ADJECTIVES.length * NOUNS.length)) % 100));
        return ADJECTIVES[adjIndex] + "-" + NOUNS[nounIndex] + "-" + number;
    }

    private void stopServiceAndCleanup(final String toastMessage) {
        if (toastMessage != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(SenderService.this, toastMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SenderService onDestroy.");
        if (requestListener != null) {
            requestListener.remove();
        }

        if (dropRequestId != null) {
            db.collection("drop_requests").document(dropRequestId).get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                @Override
                public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                    if (task.isSuccessful() && task.getResult() != null) {
                        DocumentSnapshot document = task.getResult();
                        if (document.exists()) {
                            String status = document.getString("status");
                            if (!"complete".equals(status)) {
                                document.getReference().delete()
                                        .addOnSuccessListener(aVoid -> Log.d(TAG, "Incomplete drop request document deleted."));
                            }
                        }
                    }
                }
            });
        }
        stopForeground(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "HFM Drop Sender Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, boolean ongoing) {
        Notification notification = buildNotification(text, ongoing);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text, boolean ongoing) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("HFM Drop Sender")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}