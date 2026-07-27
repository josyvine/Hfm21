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
     */
    public static final String WEB_CLIENT_ID = "602777795603-f570hm855ke9pps6vb625rafoem1lr64.apps.googleusercontent.com";

    /**
     * The API Key from your central developer Firebase project.
     */
    public static final String API_KEY = "AIzaSyBuN7PETp08_rCGQSDs1aKsT3VB1wZd8sk";

    /**
     * The Application ID (mobilesdk_app_id) for your Android app in your central developer project.
     */
    public static final String APPLICATION_ID = "1:602777795603:android:f37bfecba647205f4713e6";

    /**
     * The Project ID of your central developer Firebase project.
     */
    public static final String PROJECT_ID = "hfm-hybrid-file-manager";

    /**
     * The Storage Bucket URL of your central developer Firebase project.
     */
    public static final String STORAGE_BUCKET = "hfm-hybrid-file-manager.firebasestorage.app";
}
