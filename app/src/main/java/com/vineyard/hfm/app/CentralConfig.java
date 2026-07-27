package com.vineyard.hfm.app;

/**
 * Configuration holder for the App Builder's Central Firebase Project.
 * This is used strictly for Google Authentication and identity verification.
 * 
 * The App Builder holds the master SHA-1 fingerprint on this central project,
 * allowing any client user to perform Google OAuth sign-in seamlessly.
 */
public final class CentralConfig {

    private CentralConfig() {
        // Prevent instantiation
    }

    /**
     * The Web Client ID (Client Type 3) from your central developer project's OAuth credentials.
     * Required to request the Google ID Token during Google Sign-In.
     * Replace with your active central OAuth Web Client ID.
     */
    public static final String WEB_CLIENT_ID = "YOUR_CENTRAL_WEB_CLIENT_ID_HERE.apps.googleusercontent.com";

    /**
     * The API Key from your central developer Firebase project.
     */
    public static final String API_KEY = "AIzaSyCKtutIzHMOI2CjIEm5d0EfKxa91UaPf48";

    /**
     * The Application ID (mobilesdk_app_id) for your Android app in your central developer project.
     */
    public static final String APPLICATION_ID = "1:370752218415:android:534c6cdc4ac0ade9802dda";

    /**
     * The Project ID of your central developer Firebase project.
     */
    public static final String PROJECT_ID = "hfm-app-backend";

    /**
     * The Storage Bucket URL of your central developer Firebase project.
     */
    public static final String STORAGE_BUCKET = "hfm-app-backend.firebasestorage.app";
}