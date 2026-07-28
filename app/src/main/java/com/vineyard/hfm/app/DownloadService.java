package com.vineyard.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Foreground Service responsible for fetching manifest details from the Client Firestore database,
 * downloading polymorphic encrypted shards from Google Drive, and reconstructing files into .vault.
 * Supports batch multi-file downloads and updates status to secondary Client Firestore ("client_hfm_app").
 */
public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final String NOTIFICATION_CHANNEL_ID = "DownloadServiceChannel";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_DOWNLOAD_ERROR = "com.vineyard.hfm.app.action.DOWNLOAD_ERROR";
    public static final String EXTRA_ERROR_MESSAGE = "com.vineyard.hfm.app.extra.ERROR_MESSAGE";

    private FirebaseFirestore db;

    @Override
    public void onCreate() {
        super.onCreate();
        
        try {
            FirebaseApp clientApp = FirebaseApp.getInstance(FirebaseManager.CLIENT_APP_NAME);
            db = FirebaseFirestore.getInstance(clientApp);
            Log.d(TAG, "DownloadService successfully bound to secondary Client Firestore.");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary client app not mounted. Falling back to default instance.", e);
            db = FirebaseFirestore.getInstance();
        }

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        if (intent != null) {
            final String currentDropRequestId = intent.getStringExtra("drop_request_id");
            String rawSecretNumber = intent.getStringExtra("secret_number"); 
            
            final String passedSecretNumber = (rawSecretNumber != null) ? rawSecretNumber.trim().replaceAll("\\s+", "") : null;

            Notification notification = buildNotification("Initializing Secure Drop...", true, 0, 0);
            startForeground(NOTIFICATION_ID, notification);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    startDownloadProcess(currentDropRequestId, passedSecretNumber, startId);
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    private void startDownloadProcess(final String docId, final String secretNumber, final int startId) {
        final DocumentReference docRef = db.collection("drop_requests").document(docId);
        final ListenerRegistration currentListener = listenForStatusChange(docRef, startId);

        docRef.get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
            @Override
            public void onSuccess(final DocumentSnapshot documentSnapshot) {
                if (!documentSnapshot.exists()) {
                    broadcastError("Error: Drop request not found on client database.");
                    stopServiceAndCleanup(null, startId, currentListener, docId);
                    return;
                }

                if (secretNumber == null || secretNumber.isEmpty()) {
                    broadcastError("Error: Secret Number missing from receiver session.");
                    stopServiceAndCleanup(null, startId, currentListener, docId);
                    return;
                }

                GoogleSignInAccount driveAccount = GoogleSignIn.getLastSignedInAccount(DownloadService.this);
                if (driveAccount == null) {
                    broadcastError("Google Drive authentication failed. Please sign in again.");
                    stopServiceAndCleanup(null, startId, currentListener, docId);
                    return;
                }

                final GoogleDriveManager driveManager = new GoogleDriveManager(DownloadService.this, driveAccount);
                final ReconstructionEngine reconstructionEngine = new ReconstructionEngine(DownloadService.this, driveManager);
                final SecureVaultManager vaultManager = new SecureVaultManager(DownloadService.this);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            List<Map<String, Object>> fileItems = (List<Map<String, Object>>) documentSnapshot.get("fileItems");
                            String lastDownloadedFileName = null;
                            String lastDownloadedVaultPath = null;

                            if (fileItems != null && !fileItems.isEmpty()) {
                                int totalBatchFiles = fileItems.size();

                                for (int i = 0; i < totalBatchFiles; i++) {
                                    Map<String, Object> itemMap = fileItems.get(i);
                                    final String manifestId = (String) itemMap.get("encryptedManifestId");
                                    final long filesize = ((Number) itemMap.get("filesize")).longValue();
                                    final String fileName = (String) itemMap.get("originalFilename");

                                    final int currentFileIndex = i + 1;
                                    final File vaultFile = vaultManager.createVaultFile(fileName);

                                    String reconstructedName = reconstructionEngine.executeReconstruction(manifestId, secretNumber, vaultFile, new ReconstructionEngine.ProgressListener() {
                                        @Override
                                        public void onProgress(int progress, int max, long bytesProcessed) {
                                            updateNotification("Reconstructing (" + currentFileIndex + "/" + totalBatchFiles + "): " + fileName + "... " + progress + "%", true, progress, max);
                                            broadcastStatus("Reconstructing (" + currentFileIndex + "/" + totalBatchFiles + ")",
                                                    String.format(Locale.US, "%s / %s",
                                                            Formatter.formatFileSize(getApplicationContext(), bytesProcessed),
                                                            Formatter.formatFileSize(getApplicationContext(), filesize)),
                                                    progress, max, bytesProcessed);
                                        }

                                        @Override
                                        public void onStatusUpdate(String minorStatus) {
                                            updateNotification(minorStatus, true, 0, 0);
                                            broadcastStatus("Reconstructing (" + currentFileIndex + "/" + totalBatchFiles + ")", minorStatus, -1, -1, -1);
                                        }
                                    });

                                    lastDownloadedFileName = reconstructedName;
                                    lastDownloadedVaultPath = vaultFile.getAbsolutePath();
                                }
                            } else {
                                // Single file fallback
                                final String encryptedManifestId = documentSnapshot.getString("encryptedManifestId");
                                final long originalFilesize = documentSnapshot.getLong("filesize");
                                final String fileNameFromServer = documentSnapshot.getString("originalFilename");

                                if (encryptedManifestId == null) {
                                    broadcastError("Error: Incomplete transfer details from server.");
                                    stopServiceAndCleanup(null, startId, currentListener, docId);
                                    return;
                                }

                                final File vaultFile = vaultManager.createVaultFile(fileNameFromServer);

                                lastDownloadedFileName = reconstructionEngine.executeReconstruction(encryptedManifestId, secretNumber, vaultFile, new ReconstructionEngine.ProgressListener() {
                                    @Override
                                    public void onProgress(int progress, int max, long bytesProcessed) {
                                        updateNotification("Reconstructing " + fileNameFromServer + "... " + progress + "%", true, progress, max);
                                        broadcastStatus("Reconstructing...",
                                                String.format(Locale.US, "%s / %s",
                                                        Formatter.formatFileSize(getApplicationContext(), bytesProcessed),
                                                        Formatter.formatFileSize(getApplicationContext(), originalFilesize)),
                                                progress, max, bytesProcessed);
                                    }

                                    @Override
                                    public void onStatusUpdate(String minorStatus) {
                                        updateNotification(minorStatus, true, 0, 0);
                                        broadcastStatus("Reconstructing...", minorStatus, -1, -1, -1);
                                    }
                                });

                                lastDownloadedVaultPath = vaultFile.getAbsolutePath();
                            }

                            // Update Firestore document status to complete
                            docRef.update("status", "complete");
                            updateNotification("Download Complete: " + lastDownloadedFileName, false, 100, 100);
                            
                            broadcastComplete(lastDownloadedFileName, lastDownloadedVaultPath);
                            stopServiceAndCleanup(null, startId, currentListener, docId);

                        } catch (Exception e) {
                            docRef.update("status", "error");
                            String exactErrorLog = getStackTraceAsString(e);
                            broadcastError("FATAL RECONSTRUCTION ERROR:\n\n" + exactErrorLog);
                            stopServiceAndCleanup(null, startId, currentListener, docId);
                        }
                    }
                }).start();
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                broadcastError("Could not retrieve transfer details from client server.\n\n" + getStackTraceAsString(e));
                stopServiceAndCleanup(null, startId, currentListener, docId);
            }
        });
    }

    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
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
    
    private void broadcastComplete(String originalFileName, String vaultFilePath) {
        Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
        intent.putExtra("original_file_name", originalFileName);
        intent.putExtra("vault_file_path", vaultFilePath);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastError(String message) {
        Intent errorIntent = new Intent(ACTION_DOWNLOAD_ERROR);
        errorIntent.putExtra(EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(errorIntent);
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
    }

    private ListenerRegistration listenForStatusChange(DocumentReference docRef, final int startId) {
        return docRef.addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(DocumentSnapshot snapshot, FirebaseFirestoreException e) {
                if (e != null) { return; }
                if (snapshot != null && snapshot.exists()) {
                    String status = snapshot.getString("status");
                    if ("error".equals(status) || "declined".equals(status) || "cancelled".equals(status)) {
                         stopServiceAndCleanup("Transfer was cancelled or encountered an error.", startId, null, null);
                    }
                } else {
                     stopServiceAndCleanup("Transfer was cancelled by the sender.", startId, null, null);
                }
            }
        });
    }

    private void stopServiceAndCleanup(final String toastMessage, final int startId, ListenerRegistration listener, String docId) {
        if (toastMessage != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(DownloadService.this, toastMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
        
        if (listener != null) {
            listener.remove();
        }

        if (docId != null && db != null) {
            db.collection("drop_requests").document(docId).delete();
        }

        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "DownloadService onDestroy.");
        stopForeground(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "HFM Drop Downloader",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, boolean ongoing, int progress, int max) {
        Notification notification = buildNotification(text, ongoing, progress, max);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text, boolean ongoing, int progress, int max) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("HFM Drop")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (max > 0) {
            builder.setProgress(max, progress, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}