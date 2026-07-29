package com.vineyard.hfm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.api.services.drive.DriveScopes;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "HFM_MainActivity";
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 456;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 457;
    private static final int DROP_FILE_PICKER_REQUEST_CODE = 999;
    private static final int GOOGLE_DRIVE_SIGNIN_REQUEST_CODE = 1001;

    private WebView webView;
    private FirebaseAuth mCentralAuth;
    private ArrayList<String> filesToSendViaDrop;
    private GoogleSignInClient googleSignInClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Cold start check for Dashboard Activity (Daily File Dashboard)
        if (savedInstanceState == null) {
            startActivity(new Intent(this, DashboardActivity.class));
        }

        webView = findViewById(R.id.webView);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webView.setBackgroundColor(0x00000000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");

        // Central Auth Instance (Points to Developer's [DEFAULT] App)
        mCentralAuth = FirebaseAuth.getInstance();

        setupGoogleDriveAuth();
        requestFilePermissions();
        checkAndAuthenticateUser();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/webview-app.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Configures Google Sign-In pointing strictly to CentralConfig.WEB_CLIENT_ID
     * to ensure SHA-1 verification is handled on the Central Developer Project.
     */
    private void setupGoogleDriveAuth() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(CentralConfig.WEB_CLIENT_ID)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE))
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);
    }

    private void updateWebViewDriveStatus(final boolean isLoggedIn) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript("window.updateDriveAuthStatus(" + isLoggedIn + ");", null);
                }
            }
        });
    }

    /**
     * Verifies central authentication and executes the Silent Auth Bridge to the Client database.
     */
    private void checkAndAuthenticateUser() {
        FirebaseUser currentUser = mCentralAuth.getCurrentUser();
        if (currentUser != null) {
            authenticateSecondaryApp(currentUser);
        } else {
            Log.d(TAG, "Central Auth session empty. Waiting for user login or Drive sign-in.");
        }
    }

    /**
     * Silently authenticates the user on the secondary Client database ("client_hfm_app")
     * using a mathematically derived SHA-256 password hash.
     */
    private void authenticateSecondaryApp(FirebaseUser centralUser) {
        try {
            FirebaseApp clientApp = FirebaseApp.getInstance(FirebaseManager.CLIENT_APP_NAME);
            FirebaseAuth clientAuth = FirebaseAuth.getInstance(clientApp);

            String email = centralUser.getEmail();
            String centralUid = centralUser.getUid();

            if (email == null || email.isEmpty()) {
                Log.w(TAG, "User email missing from central Google identity.");
                return;
            }

            String derivedPassword = calculateSecurePassword(email, centralUid);

            // Attempt silent login
            clientAuth.signInWithEmailAndPassword(email, derivedPassword)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "Silent Email/Password sign-in successful on client database.");
                        } else {
                            // Register account silently if it doesn't exist on Client database yet
                            Log.d(TAG, "Client account does not exist. Creating silent secondary account.");
                            clientAuth.createUserWithEmailAndPassword(email, derivedPassword)
                                    .addOnCompleteListener(createTask -> {
                                        if (createTask.isSuccessful()) {
                                            Log.d(TAG, "Silent secondary user registration complete.");
                                        } else {
                                            Log.e(TAG, "Failed silent registration on client database.", createTask.getException());
                                        }
                                    });
                        }
                    });
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary client app not mounted yet.", e);
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

    private void requestFilePermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
                }
            } else {
                requestNotificationPermission();
            }
        } else {
            boolean readGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean writeGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

            if (!readGranted || !writeGranted) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                requestNotificationPermission();
            }
        }

        // REQUEST DIALER INTERCEPTION PERMISSIONS FOR STEALTH LAUNCH
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.PROCESS_OUTGOING_CALLS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), STORAGE_PERMISSION_REQUEST_CODE);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    requestNotificationPermission();
                } else {
                    Toast.makeText(this, "All Files Access permission is required.", Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == DROP_FILE_PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.hasExtra("picked_files")) {
                filesToSendViaDrop = data.getStringArrayListExtra("picked_files");
                if (filesToSendViaDrop != null && !filesToSendViaDrop.isEmpty()) {
                    showSendToDropDialog();
                }
            }
        } else if (requestCode == GOOGLE_DRIVE_SIGNIN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
                try {
                    GoogleSignInAccount account = task.getResult(ApiException.class);
                    if (account != null && account.getIdToken() != null) {
                        AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
                        mCentralAuth.signInWithCredential(credential).addOnCompleteListener(authTask -> {
                            if (authTask.isSuccessful() && mCentralAuth.getCurrentUser() != null) {
                                authenticateSecondaryApp(mCentralAuth.getCurrentUser());
                            }
                        });
                    }
                    if (account != null && GoogleSignIn.hasPermissions(account, new Scope(DriveScopes.DRIVE))) {
                        Toast.makeText(this, "Google Drive Connected!", Toast.LENGTH_SHORT).show();
                        updateWebViewDriveStatus(true);
                    } else {
                        GoogleSignIn.requestPermissions(this, GOOGLE_DRIVE_SIGNIN_REQUEST_CODE, account, new Scope(DriveScopes.DRIVE));
                    }
                } catch (ApiException e) {
                    Log.e(TAG, "Google Sign-In failed", e);
                    Toast.makeText(this, "Google Drive Sign-In Failed.", Toast.LENGTH_SHORT).show();
                    updateWebViewDriveStatus(false);
                }
            } else {
                Toast.makeText(this, "Google Drive Sign-In Cancelled.", Toast.LENGTH_SHORT).show();
                updateWebViewDriveStatus(false);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                requestNotificationPermission();
            } else {
                Toast.makeText(this, "Storage permission is required for the app to function.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications will be disabled.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String generateSecretNumber() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Displays the "Send File via Drop" dialog with an Auto-Complete Dropdown
     * populated with live cloud network peers + local preferences.
     */
    private void showSendToDropDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
        
        final AutoCompleteTextView receiverInputView = dialogView.findViewById(R.id.edit_text_receiver_username);

        // Bind Auto-Complete suggestion list from EncryptionHelper (Queries cloud network_peers + local history)
        EncryptionHelper.getInstance(this).setupAutoComplete(this, receiverInputView);

        builder.setView(dialogView)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String receiverUsername = receiverInputView.getText().toString().trim();
                        if (receiverUsername.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Receiver username cannot be empty.", Toast.LENGTH_SHORT).show();
                        } else {
                            // Save receiver username to persistent local history
                            EncryptionHelper.getInstance(MainActivity.this).saveReceiverUsername(receiverUsername);
                            showSenderWarningDialog(receiverUsername, null);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        builder.create().show();
    }

    private void showSenderWarningDialog(final String receiverUsername, final String existingSecretNumber) {
        final String secretNumber = (existingSecretNumber != null) ? existingSecretNumber : generateSecretNumber();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Important: Connection Stability")
            .setMessage("You are about to act as a temporary server for this file transfer.\n\n"
                    + "Please keep the app open and maintain a stable internet connection until the transfer is complete.\n\n"
                    + "Your Secret Number for this transfer is:\n" + secretNumber + "\n\nShare this number with the receiver.")
            .setPositiveButton("I Understand, Start Sending", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startSenderService(receiverUsername, secretNumber);
                }
            })
            .setNeutralButton("Copy PIN", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("Secret PIN", secretNumber);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(MainActivity.this, "Secret PIN copied to clipboard!", Toast.LENGTH_SHORT).show();
                    }
                    showSenderWarningDialog(receiverUsername, secretNumber);
                }
            })
            .setNegativeButton("Share PIN", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, "HFM Drop Secret PIN");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, "Here is the Secret PIN for our HFM Drop file transfer: " + secretNumber);
                    startActivity(Intent.createChooser(shareIntent, "Share Secret PIN via:"));
                    showSenderWarningDialog(receiverUsername, secretNumber);
                }
            });
        builder.create().show();
    }

    private void startSenderService(String receiverUsername, String secretNumber) {
        if (filesToSendViaDrop == null || filesToSendViaDrop.isEmpty()) {
            Toast.makeText(this, "Error: No files selected to send.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save username to persistent history
        EncryptionHelper.getInstance(this).saveReceiverUsername(receiverUsername);

        // Send all selected files in a single batch intent extra
        Intent intent = new Intent(this, SenderService.class);
        intent.setAction(SenderService.ACTION_START_SEND);
        intent.putStringArrayListExtra(SenderService.EXTRA_FILE_PATHS, filesToSendViaDrop);
        intent.putExtra(SenderService.EXTRA_RECEIVER_USERNAME, receiverUsername);
        intent.putExtra(SenderService.EXTRA_SECRET_NUMBER, secretNumber);
        ContextCompat.startForegroundService(this, intent);

        filesToSendViaDrop = null;
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d("HFMApp_WebView", message);
        }

        @JavascriptInterface
        public void openDashboard() {
            Intent intent = new Intent(mContext, DashboardActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openSearch() {
            Intent intent = new Intent(mContext, SearchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openMassDelete() {
            Intent intent = new Intent(mContext, MassDeleteActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openRecycleBin() {
            Intent intent = new Intent(mContext, RecycleBinActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openContactForm() {
            Intent intent = new Intent(mContext, ContactActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void clearCache() {
            Intent intent = new Intent(mContext, CacheCleanerActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openReader() {
            Intent intent = new Intent(mContext, ReaderActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openStorageMap() {
            Intent intent = new Intent(mContext, StorageMapActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void onHideIconTapped() {
            RitualManager ritualManager = new RitualManager();
            List<RitualManager.Ritual> rituals = ritualManager.loadRituals(mContext);

            if (rituals == null || rituals.isEmpty()) {
                Intent intent = new Intent(mContext, FileHiderActivity.class);
                mContext.startActivity(intent);
            } else {
                Intent intent = new Intent(mContext, RitualListActivity.class);
                mContext.startActivity(intent);
            }
        }

        @JavascriptInterface
        public void setTheme(final String themeName) {
            ThemeManager.setTheme(mContext, themeName);
            new android.os.Handler(mContext.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(mContext, "Theme changed. Please restart the app to see the full effect.", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void openShareHub() {
            Intent intent = new Intent(mContext, ShareHubActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openApiKeyDialog() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                    LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    View dialogView = inflater.inflate(R.layout.dialog_api_key, null);
                    final EditText apiKeyInput = dialogView.findViewById(R.id.edit_text_api_key);

                    String currentKey = ApiKeyManager.getApiKey(mContext);
                    if (currentKey != null) {
                        apiKeyInput.setText(currentKey);
                    }

                    builder.setView(dialogView)
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int id) {
                                String newKey = apiKeyInput.getText().toString().trim();
                                ApiKeyManager.saveApiKey(mContext, newKey);
                                Toast.makeText(mContext, newKey.isEmpty() ? "API Key cleared." : "API Key saved.", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null);
                    builder.create().show();
                }
            });
        }

        @JavascriptInterface
        public boolean isDriveLoggedIn() {
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(mContext);
            return account != null && GoogleSignIn.hasPermissions(account, new Scope(DriveScopes.DRIVE));
        }

        @JavascriptInterface
        public void toggleDriveAuth() {
            if (isDriveLoggedIn()) {
                googleSignInClient.signOut().addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        Toast.makeText(mContext, "Logged out of Google Drive", Toast.LENGTH_SHORT).show();
                        updateWebViewDriveStatus(false);
                    }
                });
            } else {
                Intent signInIntent = googleSignInClient.getSignInIntent();
                startActivityForResult(signInIntent, GOOGLE_DRIVE_SIGNIN_REQUEST_CODE);
            }
        }

        @JavascriptInterface
        public void openDriveViewer() {
            if (isDriveLoggedIn()) {
                Intent intent = new Intent(mContext, DriveViewerActivity.class);
                mContext.startActivity(intent);
            } else {
                runOnUiThread(() -> Toast.makeText(mContext, "Please sign in to Google Drive first.", Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void sendViaDrop() {
            Intent intent = new Intent(mContext, CategoryPickerActivity.class);
            startActivityForResult(intent, DROP_FILE_PICKER_REQUEST_CODE);
        }

        @JavascriptInterface
        public void receiveViaDrop() {
            Intent intent = new Intent(mContext, HFMDropActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openSetup() {
            Intent intent = new Intent(mContext, ClientSetupActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void generateNetworkQr() {
            Intent intent = new Intent(mContext, ClientQrGenerateActivity.class);
            intent.putExtra(ClientQrGenerateActivity.EXTRA_MODE, ClientQrGenerateActivity.MODE_NETWORK);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void scanQrCode() {
            Intent intent = new Intent(mContext, ClientQrScanActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void regenerateHFMId() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(mContext)
                        .setTitle("Regenerate HFM Session")
                        .setMessage("Regenerate session on client database?")
                        .setPositiveButton("Regenerate", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                FirebaseUser user = mCentralAuth.getCurrentUser();
                                if (user != null) {
                                    checkAndAuthenticateUser();
                                    Toast.makeText(mContext, "Session re-synchronized.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            });
        }

        @JavascriptInterface
        public boolean isStealthHidden() {
            SharedPreferences prefs = getSharedPreferences("hfm_stealth_prefs", Context.MODE_PRIVATE);
            return prefs.getBoolean("is_stealth_hidden", false);
        }

        @JavascriptInterface
        public void openStealthManager() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SharedPreferences prefs = getSharedPreferences("hfm_stealth_prefs", Context.MODE_PRIVATE);
                    String currentPin = prefs.getString("stealth_pin", "");

                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    LayoutInflater inflater = getLayoutInflater();
                    View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
                    final AutoCompleteTextView pinInput = dialogView.findViewById(R.id.edit_text_receiver_username);
                    pinInput.setHint("Set 4-digit Secret PIN (e.g. 1234)");
                    pinInput.setText(currentPin);

                    builder.setTitle("HFM Dialer Stealth Manager")
                        .setView(dialogView)
                        .setPositiveButton("Save PIN", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String newPin = pinInput.getText().toString().trim();
                                if (newPin.length() < 4) {
                                    Toast.makeText(MainActivity.this, "PIN must be at least 4 digits.", Toast.LENGTH_SHORT).show();
                                } else {
                                    prefs.edit().putString("stealth_pin", newPin).apply();
                                    Toast.makeText(MainActivity.this, "Stealth PIN Saved: " + newPin + "\nDial code on phone dialer to manage slider options.", Toast.LENGTH_LONG).show();
                                }
                            }
                        })
                        .setNeutralButton("Hide Slider Icons Now", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String newPin = pinInput.getText().toString().trim();
                                if (newPin.length() < 4) {
                                    Toast.makeText(MainActivity.this, "Set a 4-digit PIN first.", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                prefs.edit().putString("stealth_pin", newPin)
                                            .putBoolean("is_stealth_hidden", true).apply();

                                Toast.makeText(MainActivity.this, "Slider Icons Hidden!\nDial " + newPin + " or *#" + newPin + "# in phone dialer to manage.", Toast.LENGTH_LONG).show();

                                webView.loadUrl("file:///android_asset/webview-app.html");
                            }
                        })
                        .setNegativeButton("Cancel", null);
                    builder.create().show();
                }
            });
        }
    }
}