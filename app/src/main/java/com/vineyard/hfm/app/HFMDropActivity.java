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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity for listening to and accepting incoming single and batch HFM Drop requests.
 * INTEGRATED CONTROL CENTER:
 * - Redirects all Firestore queries and updates directly to secondary "client_hfm_app" instance.
 * - Authenticates via Email/Password Silent Auth Bridge.
 * - Registers local device presence to Firestore network_peers collection.
 * - Provides inline setup options (Upload JSON, My QR Code, Scan QR Code) directly inside the HFM Drop feature.
 */
public class HFMDropActivity extends Activity {

    private static final String TAG = "HFMDropActivity";

    // UI Elements
    private ImageButton backButton;
    private TextView usernameTextView;
    private Button regenerateIdButton;
    private Button openVaultButton;
    private RecyclerView requestsRecyclerView;
    private ProgressBar loadingRequestsProgress;
    private TextView emptyViewRequests;

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private ListenerRegistration dropRequestListener;

    // RecyclerView
    private DropRequestAdapter adapter;
    private List<DropRequest> requestList;

    private BroadcastReceiver downloadErrorReceiver;

    private static final String[] ADJECTIVES = {"Red", "Blue", "Green", "Silent", "Fast", "Brave", "Ancient", "Wandering", "Golden", "Iron"};
    private static final String[] NOUNS = {"Tiger", "Lion", "Eagle", "Fox", "Wolf", "River", "Mountain", "Star", "Comet", "Shadow"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hfm_drop);

        initializeViews();
        checkAndInitializeSetup();
        setupRecyclerView();
        setupListeners();
        setupBroadcastReceiver();
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkCurrentUser();
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadErrorReceiver, new IntentFilter(DownloadService.ACTION_DOWNLOAD_ERROR));
    }

    @Override
    protected void onStop() {
        super.onStop();
        removeListener();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadErrorReceiver);
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_hfm_drop);
        usernameTextView = findViewById(R.id.username_text_view);
        regenerateIdButton = findViewById(R.id.regenerate_id_button);
        openVaultButton = findViewById(R.id.open_vault_button);
        requestsRecyclerView = findViewById(R.id.requests_recycler_view);
        loadingRequestsProgress = findViewById(R.id.loading_requests_progress);
        emptyViewRequests = findViewById(R.id.empty_view_requests);
    }

    /**
     * Checks if Client Firebase setup is done.
     * If missing, prompts the user with options to configure as Sender or Scan QR as Receiver.
     */
    private void checkAndInitializeSetup() {
        EncryptionHelper encryptionHelper = EncryptionHelper.getInstance(this);
        if (!encryptionHelper.isSetupDone()) {
            showInlineSetupDialog();
        } else {
            initializeFirebase();
        }
    }

    private void showInlineSetupDialog() {
        new AlertDialog.Builder(this)
                .setTitle("HFM Drop Network Required")
                .setMessage("Choose how you want to connect to HFM Messenger Drop:")
                .setCancelable(true)
                .setPositiveButton("Setup Network (Sender)", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(HFMDropActivity.this, ClientSetupActivity.class);
                        startActivity(intent);
                    }
                })
                .setNegativeButton("Scan QR Code (Receiver)", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(HFMDropActivity.this, ClientQrScanActivity.class);
                        startActivity(intent);
                    }
                })
                .setNeutralButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    /**
     * Initializes Firebase to read/write directly from the Client Secondary App instance ("client_hfm_app").
     */
    private void initializeFirebase() {
        try {
            FirebaseApp clientApp = FirebaseApp.getInstance(FirebaseManager.CLIENT_APP_NAME);
            mAuth = FirebaseAuth.getInstance(clientApp);
            db = FirebaseFirestore.getInstance(clientApp);
            Log.d(TAG, "Connected successfully to secondary Client Firestore instance.");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary client app not initialized. Falling back to default instance.", e);
            mAuth = FirebaseAuth.getInstance();
            db = FirebaseFirestore.getInstance();
        }
    }

    private void setupRecyclerView() {
        requestList = new ArrayList<>();
        adapter = new DropRequestAdapter(this, requestList, new DropRequestAdapter.OnRequestInteractionListener() {
            @Override
            public void onAccept(DropRequest request) {
                handleAccept(request);
            }

            @Override
            public void onDecline(DropRequest request) {
                handleDecline(request);
            }
        });
        requestsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        requestsRecyclerView.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        regenerateIdButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNetworkOptionsMenu();
            }
        });

        openVaultButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(HFMDropActivity.this, VaultBrowserActivity.class);
                startActivity(intent);
            }
        });
    }

    /**
     * Shows full network options menu for Setup, My QR Code, and Scan QR Code.
     */
    private void showNetworkOptionsMenu() {
        final CharSequence[] options = {
                "Setup / Change Network (Upload JSON)",
                "Show My Network QR Code (Option A)",
                "Scan QR Code to Receive (Option B)",
                "Regenerate Session ID",
                "Cancel"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("HFM Drop Network Options");
        builder.setItems(options, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case 0:
                        startActivity(new Intent(HFMDropActivity.this, ClientSetupActivity.class));
                        break;
                    case 1:
                        if (EncryptionHelper.getInstance(HFMDropActivity.this).isSetupDone()) {
                            Intent intent = new Intent(HFMDropActivity.this, ClientQrGenerateActivity.class);
                            intent.putExtra(ClientQrGenerateActivity.EXTRA_MODE, ClientQrGenerateActivity.MODE_NETWORK);
                            startActivity(intent);
                        } else {
                            Toast.makeText(HFMDropActivity.this, "Please setup your network first.", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case 2:
                        startActivity(new Intent(HFMDropActivity.this, ClientQrScanActivity.class));
                        break;
                    case 3:
                        regenerateIdentity();
                        break;
                    default:
                        dialog.dismiss();
                        break;
                }
            }
        });
        builder.show();
    }

    private void setupBroadcastReceiver() {
        downloadErrorReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadService.ACTION_DOWNLOAD_ERROR.equals(intent.getAction())) {
                    String errorReport = intent.getStringExtra(DownloadService.EXTRA_ERROR_MESSAGE);
                    if (errorReport != null && !errorReport.isEmpty()) {
                        showErrorDialog(errorReport);
                    } else {
                        showErrorDialog("An unknown download error occurred.");
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

    private void checkCurrentUser() {
        if (mAuth == null) {
            initializeFirebase();
        }

        if (mAuth != null) {
            currentUser = mAuth.getCurrentUser();
            if (currentUser == null) {
                authenticateClientUser();
            } else {
                updateUiWithUser(currentUser);
            }
        }
    }

    /**
     * Authenticates Receiver on secondary Client Firebase instance using Email/Password Silent Auth Bridge
     */
    private void authenticateClientUser() {
        if (mAuth == null) return;

        usernameTextView.setText("Generating ID...");
        regenerateIdButton.setEnabled(false);

        FirebaseAuth centralAuth = FirebaseAuth.getInstance();
        FirebaseUser centralUser = centralAuth.getCurrentUser();

        if (centralUser != null && centralUser.getEmail() != null && !centralUser.getEmail().isEmpty()) {
            String email = centralUser.getEmail();
            String centralUid = centralUser.getUid();
            String derivedPassword = calculateSecurePassword(email, centralUid);

            mAuth.signInWithEmailAndPassword(email, derivedPassword)
                    .addOnCompleteListener(this, task -> {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "Silent Email/Password login successful on client DB.");
                            currentUser = mAuth.getCurrentUser();
                            updateUiWithUser(currentUser);
                        } else {
                            mAuth.createUserWithEmailAndPassword(email, derivedPassword)
                                    .addOnCompleteListener(this, createTask -> {
                                        if (createTask.isSuccessful()) {
                                            Log.d(TAG, "Created secondary account on client DB.");
                                            currentUser = mAuth.getCurrentUser();
                                            updateUiWithUser(currentUser);
                                        } else {
                                            signInAnonymously();
                                        }
                                    });
                        }
                    });
        } else {
            signInAnonymously();
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

    private void signInAnonymously() {
        if (mAuth == null) return;

        mAuth.signInAnonymously().addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
            @Override
            public void onComplete(@NonNull Task<AuthResult> task) {
                if (task.isSuccessful()) {
                    Log.d(TAG, "signInAnonymously on client DB: success");
                    currentUser = mAuth.getCurrentUser();
                    updateUiWithUser(currentUser);
                } else {
                    Log.w(TAG, "signInAnonymously on client DB: failure", task.getException());
                    Toast.makeText(HFMDropActivity.this, "Authentication failed on Client Database.", Toast.LENGTH_SHORT).show();
                    usernameTextView.setText("Authentication Failed");
                }
            }
        });
    }

    private void regenerateIdentity() {
        if (currentUser != null) {
            usernameTextView.setText("Regenerating...");
            regenerateIdButton.setEnabled(false);
            removeListener();
            currentUser.delete().addOnCompleteListener(new OnCompleteListener<Void>() {
                @Override
                public void onComplete(@NonNull Task<Void> task) {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "User session deleted from client DB.");
                        authenticateClientUser();
                    } else {
                        Log.w(TAG, "User session deletion failed.", task.getException());
                        Toast.makeText(HFMDropActivity.this, "Failed to regenerate ID.", Toast.LENGTH_SHORT).show();
                        updateUiWithUser(currentUser);
                    }
                }
            });
        }
    }

    private void updateUiWithUser(FirebaseUser user) {
        if (user != null) {
            String username = generateUsernameFromUid(user.getUid());
            usernameTextView.setText(username);
            regenerateIdButton.setEnabled(true);

            // Automatically save self username to local preferences
            EncryptionHelper.getInstance(this).saveReceiverUsername(username);

            // PUBLISH PRESENCE TO FIRESTORE network_peers COLLECTION SO SENDER SEES US IN DROPDOWN
            publishPresenceToCloud(username);

            listenForDropRequests(username);
        }
    }

    /**
     * Publishes presence document to network_peers collection on secondary Client Firestore.
     */
    private void publishPresenceToCloud(String username) {
        if (db == null) return;
        Map<String, Object> peerData = new HashMap<>();
        peerData.put("username", username);
        peerData.put("lastSeen", System.currentTimeMillis());
        peerData.put("deviceRole", "receiver");

        db.collection("network_peers").document(username)
                .set(peerData)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Registered self presence on network_peers: " + username))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to publish presence to network_peers", e));
    }

    private String generateUsernameFromUid(String uid) {
        long hash = uid.hashCode();
        int adjIndex = (int) (Math.abs(hash % ADJECTIVES.length));
        int nounIndex = (int) (Math.abs((hash / ADJECTIVES.length) % NOUNS.length));
        int number = (int) (Math.abs((hash / (ADJECTIVES.length * NOUNS.length)) % 100));
        return ADJECTIVES[adjIndex] + "-" + NOUNS[nounIndex] + "-" + number;
    }

    private void listenForDropRequests(String username) {
        removeListener();
        if (db == null) return;

        loadingRequestsProgress.setVisibility(View.VISIBLE);
        requestsRecyclerView.setVisibility(View.GONE);
        emptyViewRequests.setVisibility(View.GONE);

        Query query = db.collection("drop_requests")
                .whereEqualTo("receiverUsername", username)
                .whereEqualTo("status", "pending");

        dropRequestListener = query.addSnapshotListener(new EventListener<QuerySnapshot>() {
            @Override
            public void onEvent(QuerySnapshot snapshots, FirebaseFirestoreException e) {
                loadingRequestsProgress.setVisibility(View.GONE);
                if (e != null) {
                    Log.w(TAG, "Listen failed on client DB snapshot.", e);
                    Toast.makeText(HFMDropActivity.this, "Error listening for requests on secondary database.", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (snapshots != null) {
                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.ADDED) {
                            DropRequest request = dc.getDocument().toObject(DropRequest.class);
                            request.id = dc.getDocument().getId();
                            requestList.add(request);

                            // Save sender username to local history when a request arrives
                            if (request.senderUsername != null && !request.senderUsername.trim().isEmpty()) {
                                EncryptionHelper.getInstance(HFMDropActivity.this).saveReceiverUsername(request.senderUsername);
                            }
                        }
                    }
                }

                if (requestList.isEmpty()) {
                    emptyViewRequests.setVisibility(View.VISIBLE);
                    requestsRecyclerView.setVisibility(View.GONE);
                } else {
                    emptyViewRequests.setVisibility(View.GONE);
                    requestsRecyclerView.setVisibility(View.VISIBLE);
                }
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void removeListener() {
        if (dropRequestListener != null) {
            dropRequestListener.remove();
            dropRequestListener = null;
        }
    }

    private void handleAccept(final DropRequest request) {
        if (currentUser == null) {
            Toast.makeText(this, "Authentication error. Please restart the app.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Secret Number");
        builder.setMessage("Please enter the Secret Number provided by the sender to decrypt this transfer:");

        final EditText input = new EditText(this);
        input.setHint("16-character Secret Number");
        builder.setView(input);

        builder.setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String secretNumber = input.getText().toString().trim();
                if (secretNumber.isEmpty()) {
                    Toast.makeText(HFMDropActivity.this, "Secret Number cannot be empty.", Toast.LENGTH_SHORT).show();
                    return;
                }
                proceedWithAccept(request, secretNumber);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void proceedWithAccept(final DropRequest request, final String secretNumber) {
        if (db == null) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "accepted");
        updates.put("receiverId", currentUser.getUid());

        db.collection("drop_requests").document(request.id).update(updates)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, "Drop request accepted on client DB. Starting download service.");

                        Intent serviceIntent = new Intent(HFMDropActivity.this, DownloadService.class);
                        serviceIntent.putExtra("drop_request_id", request.id);
                        serviceIntent.putExtra("secret_number", secretNumber);
                        ContextCompat.startForegroundService(HFMDropActivity.this, serviceIntent);

                        Intent progressIntent = new Intent(HFMDropActivity.this, DropProgressActivity.class);
                        progressIntent.putExtra("is_sender", false);
                        startActivity(progressIntent);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.e(TAG, "Failed to accept drop request on client DB", e);
                        Toast.makeText(HFMDropActivity.this, "Failed to accept request: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });

        requestList.remove(request);
        adapter.notifyDataSetChanged();
        if (requestList.isEmpty()) {
            emptyViewRequests.setVisibility(View.VISIBLE);
        }
    }

    private void handleDecline(DropRequest request) {
        if (db == null) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "declined");
        db.collection("drop_requests").document(request.id).update(updates);

        requestList.remove(request);
        adapter.notifyDataSetChanged();
        if (requestList.isEmpty()) {
            emptyViewRequests.setVisibility(View.VISIBLE);
        }
    }

    public static class DropRequest {
        public String id;
        public String senderUsername;
        public String receiverUsername;
        public String originalFilename;
        public long filesize;
        public String status;
        public String encryptedManifestId;
        public List<Map<String, Object>> fileItems;

        public DropRequest() {}
    }

    public static class DropRequestAdapter extends RecyclerView.Adapter<DropRequestAdapter.ViewHolder> {
        private Context context;
        private List<DropRequest> requestList;
        private OnRequestInteractionListener listener;

        public interface OnRequestInteractionListener {
            void onAccept(DropRequest request);
            void onDecline(DropRequest request);
        }

        public DropRequestAdapter(Context context, List<DropRequest> requestList, OnRequestInteractionListener listener) {
            this.context = context;
            this.requestList = requestList;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_drop_request, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final DropRequest request = requestList.get(position);

            holder.filename.setText(request.originalFilename);
            holder.senderInfo.setText("From: " + request.senderUsername);
            holder.filesize.setText("Size: " + Formatter.formatFileSize(context, request.filesize));

            holder.acceptButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onAccept(request);
                    }
                }
            });

            holder.declineButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onDecline(request);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return requestList.size();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            TextView filename, senderInfo, filesize;
            Button acceptButton, declineButton;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                filename = itemView.findViewById(R.id.drop_request_filename);
                senderInfo = itemView.findViewById(R.id.drop_request_sender_info);
                filesize = itemView.findViewById(R.id.drop_request_filesize);
                acceptButton = itemView.findViewById(R.id.button_accept_drop);
                declineButton = itemView.findViewById(R.id.button_decline_drop);
            }
        }
    }
}
