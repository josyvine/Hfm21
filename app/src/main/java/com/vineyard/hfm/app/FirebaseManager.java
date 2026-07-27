package com.vineyard.hfm.app;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Manages dynamic runtime initialization of Firebase projects for HFM.
 * 
 * 1. Default [DEFAULT] App: Points permanently to the App Builder's Central Firebase project
 *    (defined in CentralConfig.java) for Google Sign-In identity verification.
 * 2. Secondary Named App ("client_hfm_app"): Points dynamically to the Client/Sender's custom
 *    Firebase project loaded from google-services.json or scanned QR code.
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";
    public static final String CLIENT_APP_NAME = "client_hfm_app";

    /**
     * Initializes both Central [DEFAULT] Firebase and Client secondary Firebase apps.
     * Called automatically by HfmApplication on app boot.
     */
    public static void initialize(Context context) {
        // 1. Initialize Developer's Central Project as the default [DEFAULT] app
        try {
            FirebaseOptions defaultOptions = new FirebaseOptions.Builder()
                    .setApiKey(CentralConfig.API_KEY)
                    .setApplicationId(CentralConfig.APPLICATION_ID)
                    .setProjectId(CentralConfig.PROJECT_ID)
                    .setStorageBucket(CentralConfig.STORAGE_BUCKET)
                    .build();

            boolean defaultAppExists = false;
            List<FirebaseApp> apps = FirebaseApp.getApps(context);
            for (FirebaseApp app : apps) {
                if (FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())) {
                    defaultAppExists = true;
                    break;
                }
            }

            if (!defaultAppExists) {
                FirebaseApp.initializeApp(context, defaultOptions);
                Log.d(TAG, "Central Firebase [DEFAULT] initialized successfully.");
            } else {
                Log.d(TAG, "Central Firebase [DEFAULT] already exists.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize central default Firebase app.", e);
        }

        // 2. Initialize the Client's Project dynamically as secondary named "client_hfm_app"
        String jsonConfig = EncryptionHelper.getInstance(context).getFirebaseConfig();

        if (jsonConfig != null && !jsonConfig.isEmpty()) {
            try {
                FirebaseOptions options = buildOptionsFromJson(jsonConfig);
                
                boolean clientAppExists = false;
                List<FirebaseApp> apps = FirebaseApp.getApps(context);
                for (FirebaseApp app : apps) {
                    if (CLIENT_APP_NAME.equals(app.getName())) {
                        clientAppExists = true;
                        break;
                    }
                }

                if (clientAppExists) {
                    FirebaseApp app = FirebaseApp.getInstance(CLIENT_APP_NAME);
                    // Check if project ID changed; if so, re-mount instance
                    if (!app.getOptions().getProjectId().equals(options.getProjectId())) {
                         Log.w(TAG, "client_hfm_app project ID mismatch. Re-initializing secondary app.");
                         app.delete();
                         FirebaseApp.initializeApp(context, options, CLIENT_APP_NAME);
                    } else {
                         Log.d(TAG, "client_hfm_app already initialized and matches current configuration.");
                    }
                } else {
                    FirebaseApp.initializeApp(context, options, CLIENT_APP_NAME);
                    Log.d(TAG, "Secondary Firebase '" + CLIENT_APP_NAME + "' initialized successfully.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse or initialize secondary dynamic Firebase config.", e);
            }
        } else {
            Log.d(TAG, "No Client Firebase config found. App waiting for Setup or QR Scan.");
        }
    }

    /**
     * Dynamically updates and mounts a new Client Firebase project configuration.
     * Used when Sender uploads JSON or Receiver scans a QR Code.
     */
    public static boolean setConfiguration(Context context, String jsonConfig, String companyName, String projectId) {
        try {
            // Validate JSON configuration structure first
            FirebaseOptions options = buildOptionsFromJson(jsonConfig);

            // Save configuration securely
            EncryptionHelper.getInstance(context).saveFirebaseConfig(jsonConfig, companyName, projectId);
            
            // Mount or update the secondary named app instance immediately
            boolean clientAppExists = false;
            List<FirebaseApp> apps = FirebaseApp.getApps(context);
            for (FirebaseApp app : apps) {
                if (CLIENT_APP_NAME.equals(app.getName())) {
                    clientAppExists = true;
                    break;
                }
            }

            if (clientAppExists) {
                FirebaseApp app = FirebaseApp.getInstance(CLIENT_APP_NAME);
                app.delete();
            }
            
            FirebaseApp.initializeApp(context, options, CLIENT_APP_NAME);
            Log.d(TAG, "New Client Firebase configuration saved and secondary app mounted.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Invalid Firebase JSON configuration provided.", e);
            return false;
        }
    }

    /**
     * Parses raw google-services.json text and converts it into FirebaseOptions.
     */
    private static FirebaseOptions buildOptionsFromJson(String jsonString) throws Exception {
        JSONObject root = new JSONObject(jsonString);
        
        // Extract project_info
        JSONObject projectInfo = root.getJSONObject("project_info");
        String projectId = projectInfo.getString("project_id");
        String storageBucket = projectInfo.has("storage_bucket") ? projectInfo.getString("storage_bucket") : projectId + ".appspot.com";

        // Extract client_info
        JSONArray clientArray = root.getJSONArray("client");
        JSONObject client = clientArray.getJSONObject(0);
        JSONObject clientInfo = client.getJSONObject("client_info");
        String applicationId = clientInfo.getString("mobilesdk_app_id");

        // Extract api_key
        JSONArray apiKeyArray = client.getJSONArray("api_key");
        JSONObject apiKeyObject = apiKeyArray.getJSONObject(0);
        String apiKey = apiKeyObject.getString("current_key");

        return new FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setApplicationId(applicationId)
                .setProjectId(projectId)
                .setStorageBucket(storageBucket)
                .build();
    }
}