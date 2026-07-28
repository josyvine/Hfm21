package com.vineyard.hfm.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DriveViewerActivity extends Activity implements DriveViewerAdapter.OnDriveItemClickListener {

    private static final String TAG = "DriveViewerActivity";

    // UI Elements
    private ImageButton backButton;
    private TextView titleTextView;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView emptyViewText;

    // Drive & Networking
    private Drive driveService;
    private ExecutorService networkExecutor;
    private DriveViewerAdapter adapter;
    private List<File> currentFilesList = new ArrayList<>();

    // Navigation State
    private String currentFolderId = "root";
    private String currentFolderName = "My Google Drive";
    private final Stack<FolderState> navigationStack = new Stack<>();

    // Helper class for back navigation
    private static class FolderState {
        String id;
        String name;

        FolderState(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drive_viewer);

        initializeViews();
        setupRecyclerView();
        setupListeners();

        networkExecutor = Executors.newSingleThreadExecutor();

        if (initializeDriveService()) {
            loadFolder(currentFolderId, currentFolderName, false);
        } else {
            Toast.makeText(this, "Authentication error. Please log in again.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_drive_viewer);
        titleTextView = findViewById(R.id.title_drive_viewer);
        recyclerView = findViewById(R.id.drive_recycler_view);
        progressBar = findViewById(R.id.drive_progress_bar);
        emptyViewText = findViewById(R.id.empty_view_drive);
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DriveViewerAdapter(this, currentFilesList, this);
        recyclerView.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleBackNavigation();
            }
        });
    }

    private boolean initializeDriveService() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account != null) {
            // FIXED: Changed from DRIVE_FILE to DRIVE to match GoogleDriveManager & Google Cloud Console
            GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                    this, Collections.singleton(DriveScopes.DRIVE));
            credential.setSelectedAccount(account.getAccount());

            driveService = new Drive.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    new GsonFactory(),
                    credential)
                    .setApplicationName("HFM Drop Viewer")
                    .build();
            return true;
        }
        return false;
    }

    /**
     * Queries Google Drive for files inside a specific folder ID.
     * 
     * @param folderId The Drive folder ID to query ("root" for the main directory).
     * @param folderName The human-readable name of the folder for the UI.
     * @param addToStack Whether to push the current folder state to the back stack.
     */
    private void loadFolder(final String folderId, final String folderName, boolean addToStack) {
        if (addToStack) {
            navigationStack.push(new FolderState(currentFolderId, currentFolderName));
        }

        currentFolderId = folderId;
        currentFolderName = folderName;
        titleTextView.setText(folderName);

        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        emptyViewText.setVisibility(View.GONE);

        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String query = "'" + folderId + "' in parents and trashed = false";
                    FileList result = driveService.files().list()
                            .setQ(query)
                            .setFields("files(id, name, mimeType, size, modifiedTime)")
                            .setOrderBy("folder, name")
                            .execute();

                    final List<File> fetchedFiles = result.getFiles();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.GONE);
                            currentFilesList.clear();

                            if (fetchedFiles != null && !fetchedFiles.isEmpty()) {
                                currentFilesList.addAll(fetchedFiles);
                                adapter.notifyDataSetChanged();
                                recyclerView.setVisibility(View.VISIBLE);
                            } else {
                                emptyViewText.setVisibility(View.VISIBLE);
                                emptyViewText.setText("This folder is empty.");
                            }
                        }
                    });

                } catch (IOException e) {
                    Log.e(TAG, "Failed to load Drive folder", e);
                    final String exactError = e.getMessage() != null ? e.getMessage() : e.toString();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.GONE);
                            emptyViewText.setVisibility(View.VISIBLE);
                            // Prints exact server error instead of generic network error
                            emptyViewText.setText("Drive Error:\n" + exactError);
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onDriveItemClick(File driveFile) {
        // MIME type for Google Drive folders
        if ("application/vnd.google-apps.folder".equals(driveFile.getMimeType())) {
            loadFolder(driveFile.getId(), driveFile.getName(), true);
        } else {
            // It's a file (likely an encrypted shard or decoy). Show strict warning.
            Toast.makeText(this, "File: " + driveFile.getName() + "\nMorphed shards cannot be opened manually.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleBackNavigation() {
        if (!navigationStack.isEmpty()) {
            FolderState previousFolder = navigationStack.pop();
            loadFolder(previousFolder.id, previousFolder.name, false);
        } else {
            finish(); // Exit the activity if we are at the root
        }
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkExecutor != null && !networkExecutor.isShutdown()) {
            networkExecutor.shutdownNow();
        }
    }
}
