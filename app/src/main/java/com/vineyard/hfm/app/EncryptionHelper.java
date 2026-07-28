package com.vineyard.hfm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Handles security operations for HFM:
 * 1. AES-256 encrypted local storage of dynamic Client Firebase configurations.
 * 2. Payload encryption and decryption for Option A (Network Pairing) and Option B (Instant File Drop) QR Codes.
 * 3. Extraction of OAuth client credentials from saved configurations.
 * 4. Persistent storage of paired/used receiver usernames for auto-complete dropdowns.
 * 5. Dynamic querying of cloud network_peers from Firestore to populate sender dropdowns automatically.
 */
public class EncryptionHelper {

    private static final String TAG = "EncryptionHelper";
    private static final String PREFS_FILENAME = "hfm_secure_app_prefs";

    // Keys for EncryptedSharedPreferences
    private static final String KEY_USER_ROLE = "key_user_role";
    private static final String KEY_FIREBASE_CONFIG = "key_firebase_config";
    private static final String KEY_COMPANY_NAME = "key_company_name";
    private static final String KEY_PROJECT_ID = "key_project_id";
    private static final String KEY_IS_SETUP_DONE = "key_is_setup_done";
    private static final String KEY_SAVED_USERNAMES = "key_saved_usernames";

    // Secret Key used for AES encryption/decryption of QR payloads between Sender and Receiver
    private static final String QR_ENCRYPTION_KEY = "HfmAppSuperSecretKey2026";
    private static final String AES_ALGORITHM = "AES";

    private final SharedPreferences sharedPreferences;
    private static EncryptionHelper instance;

    private EncryptionHelper(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    PREFS_FILENAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | java.io.IOException e) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences. Falling back to standard prefs.", e);
            throw new RuntimeException("Failed to initialize encrypted storage context", e);
        }
    }

    public static synchronized EncryptionHelper getInstance(Context context) {
        if (instance == null) {
            instance = new EncryptionHelper(context.getApplicationContext());
        }
        return instance;
    }

    public void saveUserRole(String role) {
        sharedPreferences.edit().putString(KEY_USER_ROLE, role).apply();
    }

    public String getUserRole() {
        return sharedPreferences.getString(KEY_USER_ROLE, null);
    }

    public void clearUserRole() {
        sharedPreferences.edit().remove(KEY_USER_ROLE).apply();
    }

    public void saveFirebaseConfig(String jsonConfig, String companyName, String projectId) {
        sharedPreferences.edit()
                .putString(KEY_FIREBASE_CONFIG, jsonConfig)
                .putString(KEY_COMPANY_NAME, companyName)
                .putString(KEY_PROJECT_ID, projectId)
                .putBoolean(KEY_IS_SETUP_DONE, true)
                .apply();
    }

    public String getFirebaseConfig() {
        return sharedPreferences.getString(KEY_FIREBASE_CONFIG, null);
    }

    public String getCompanyName() {
        return sharedPreferences.getString(KEY_COMPANY_NAME, "My HFM Network");
    }

    public String getProjectId() {
        return sharedPreferences.getString(KEY_PROJECT_ID, null);
    }

    public boolean isSetupDone() {
        return sharedPreferences.getBoolean(KEY_IS_SETUP_DONE, false);
    }

    /**
     * Saves a receiver username to persistent local storage for auto-complete suggestions.
     */
    public void saveReceiverUsername(String username) {
        if (username == null || username.trim().isEmpty() || "ANY".equalsIgnoreCase(username.trim())) {
            return;
        }

        Set<String> usernames = sharedPreferences.getStringSet(KEY_SAVED_USERNAMES, new HashSet<String>());
        Set<String> updatedUsernames = new HashSet<>(usernames);
        updatedUsernames.add(username.trim());

        sharedPreferences.edit().putStringSet(KEY_SAVED_USERNAMES, updatedUsernames).apply();
        Log.d(TAG, "Saved receiver username to history: " + username);
    }

    /**
     * Retrieves the list of all previously saved/paired receiver usernames.
     */
    public List<String> getSavedUsernames() {
        Set<String> usernames = sharedPreferences.getStringSet(KEY_SAVED_USERNAMES, new HashSet<String>());
        return new ArrayList<>(usernames);
    }

    /**
     * Checks if a given username belongs to an already paired network receiver.
     * Used by SenderService to suppress Instant QR Generation when sending to paired users.
     */
    public boolean isReceiverPaired(String username) {
        if (username == null || username.trim().isEmpty() || "ANY".equalsIgnoreCase(username.trim())) {
            return false;
        }
        List<String> savedUsernames = getSavedUsernames();
        for (String saved : savedUsernames) {
            if (saved.equalsIgnoreCase(username.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper method to configure an AutoCompleteTextView with saved receiver usernames
     * AND live network peers fetched from the Firestore network_peers collection.
     */
    public void setupAutoComplete(Context context, AutoCompleteTextView autoCompleteTextView) {
        if (autoCompleteTextView == null) return;

        List<String> localUsernames = getSavedUsernames();
        Set<String> masterSet = new HashSet<>(localUsernames);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(masterSet));
        autoCompleteTextView.setAdapter(adapter);
        autoCompleteTextView.setThreshold(1);

        autoCompleteTextView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && adapter.getCount() > 0) {
                autoCompleteTextView.showDropDown();
            }
        });

        autoCompleteTextView.setOnClickListener(v -> {
            if (adapter.getCount() > 0) {
                autoCompleteTextView.showDropDown();
            }
        });

        // Query cloud network_peers collection from secondary Client Firestore instance
        try {
            FirebaseApp clientApp = FirebaseApp.getInstance(FirebaseManager.CLIENT_APP_NAME);
            FirebaseFirestore clientDb = FirebaseFirestore.getInstance(clientApp);

            clientDb.collection("network_peers").get()
                    .addOnSuccessListener(queryDocumentSnapshots -> {
                        if (queryDocumentSnapshots != null && !queryDocumentSnapshots.isEmpty()) {
                            boolean changed = false;
                            for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                                String username = doc.getString("username");
                                if (username != null && !username.trim().isEmpty()) {
                                    saveReceiverUsername(username); // Save to local prefs
                                    if (masterSet.add(username.trim())) {
                                        changed = true;
                                    }
                                }
                            }
                            if (changed) {
                                autoCompleteTextView.post(() -> {
                                    adapter.clear();
                                    adapter.addAll(masterSet);
                                    adapter.notifyDataSetChanged();
                                    if (autoCompleteTextView.isFocused()) {
                                        autoCompleteTextView.showDropDown();
                                    }
                                });
                            }
                        }
                    })
                    .addOnFailureListener(e -> Log.d(TAG, "Failed to query cloud network_peers for auto-complete", e));
        } catch (Exception e) {
            Log.d(TAG, "Secondary client app not mounted yet for cloud peer query.", e);
        }
    }

    /**
     * Clears all saved receiver usernames.
     */
    public void clearSavedUsernames() {
        sharedPreferences.edit().remove(KEY_SAVED_USERNAMES).apply();
    }

    public void clearAllData() {
        sharedPreferences.edit().clear().apply();
    }

    private SecretKeySpec generateKey() throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = QR_ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8);
        digest.update(bytes, 0, bytes.length);
        byte[] key = digest.digest();
        return new SecretKeySpec(key, AES_ALGORITHM);
    }

    /**
     * Encrypts plain text JSON string into a Base64 string for QR Code generation.
     */
    public String encryptQrPayload(String plainText) {
        try {
            SecretKeySpec key = generateKey();
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encVal = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(encVal, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "QR Payload Encryption failed", e);
            return null;
        }
    }

    /**
     * Decrypts Base64 string from scanned QR Code back into plain text JSON string.
     */
    public String decryptQrPayload(String encryptedText) {
        try {
            SecretKeySpec key = generateKey();
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decodedValue = Base64.decode(encryptedText, Base64.NO_WRAP);
            byte[] decValue = cipher.doFinal(decodedValue);
            return new String(decValue, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "QR Payload Decryption failed", e);
            return null;
        }
    }

    /**
     * Extracts the Web Client ID (Type 3) from the saved Firebase JSON.
     */
    public String getWebClientId() {
        String jsonConfig = getFirebaseConfig();
        if (jsonConfig == null) return null;

        try {
            JSONObject root = new JSONObject(jsonConfig);
            JSONArray clientArray = root.getJSONArray("client");
            if (clientArray.length() > 0) {
                JSONObject client = clientArray.getJSONObject(0);
                JSONArray oauthClientArray = client.getJSONArray("oauth_client");

                for (int i = 0; i < oauthClientArray.length(); i++) {
                    JSONObject oauthClient = oauthClientArray.getJSONObject(i);
                    int clientType = oauthClient.getInt("client_type");
                    if (clientType == 3) {
                        return oauthClient.getString("client_id");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting Web Client ID from saved configuration", e);
        }
        return null;
    }
}
