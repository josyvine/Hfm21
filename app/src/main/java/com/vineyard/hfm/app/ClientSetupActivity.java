package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Activity for the Sender/Admin to configure their private Firebase project.
 * Supports:
 * 1. Uploading google-services.json file via Android system file picker.
 * 2. Pasting raw JSON content directly into an input field.
 * 3. Binds the configuration to FirebaseManager and redirects to MainActivity.
 */
public class ClientSetupActivity extends ComponentActivity {

    private static final String TAG = "ClientSetupActivity";

    private EditText etCompanyName;
    private Button btnSelectJson;
    private TextView tvFileName;
    private EditText etPasteJson;
    private Button btnSaveContinue;
    private Button btnOpenQrScanner;
    private ProgressBar progressBar;

    private String jsonContent = null;
    private String parsedProjectId = null;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            processSelectedFile(uri);
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_setup);

        initializeViews();
        setupListeners();
    }

    private void initializeViews() {
        etCompanyName = findViewById(R.id.et_company_name);
        btnSelectJson = findViewById(R.id.btn_select_json);
        tvFileName = findViewById(R.id.tv_file_name);
        etPasteJson = findViewById(R.id.et_paste_json);
        btnSaveContinue = findViewById(R.id.btn_save_continue);
        btnOpenQrScanner = findViewById(R.id.btn_open_qr_scanner);
        progressBar = findViewById(R.id.progressBarSetup);
    }

    private void setupListeners() {
        btnSelectJson.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });

        btnSaveContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                validateAndSave();
            }
        });

        btnOpenQrScanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Redirect to Receiver QR Scanner if the user wants to join an existing network
                Intent intent = new Intent(ClientSetupActivity.this, ClientQrScanActivity.class);
                startActivity(intent);
                finish();
            }
        });
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimeTypes = {"application/json", "text/plain", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }

    private void processSelectedFile(Uri uri) {
        try {
            jsonContent = readTextFromUri(uri);
            if (jsonContent != null) {
                // Parse and validate structure
                JSONObject root = new JSONObject(jsonContent);
                JSONObject projectInfo = root.getJSONObject("project_info");
                parsedProjectId = projectInfo.getString("project_id");

                tvFileName.setText("Loaded: " + parsedProjectId);
                tvFileName.setVisibility(View.VISIBLE);
                Toast.makeText(this, "JSON Configuration Loaded", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing file content from URI", e);
            jsonContent = null;
            parsedProjectId = null;
            tvFileName.setText("Error: Invalid google-services.json file");
            tvFileName.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Invalid google-services.json file structure", Toast.LENGTH_LONG).show();
        }
    }

    private String readTextFromUri(Uri uri) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) return null;
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            stringBuilder.append(line);
        }
        inputStream.close();
        return stringBuilder.toString();
    }

    private void validateAndSave() {
        String companyName = etCompanyName.getText().toString().trim();

        if (TextUtils.isEmpty(companyName)) {
            etCompanyName.setError("Network or Company Name is required");
            return;
        }

        String pastedJson = etPasteJson.getText().toString().trim();
        String finalJson = null;
        String finalProjectId = null;

        // Check pasted content first
        if (!TextUtils.isEmpty(pastedJson)) {
            try {
                JSONObject root = new JSONObject(pastedJson);
                JSONObject projectInfo = root.getJSONObject("project_info");
                finalProjectId = projectInfo.getString("project_id");
                finalJson = pastedJson;
            } catch (Exception e) {
                Log.e(TAG, "Error parsing pasted JSON text", e);
                Toast.makeText(this, "Invalid Pasted JSON Structure", Toast.LENGTH_LONG).show();
                return;
            }
        } 
        // Fall back to uploaded file
        else if (jsonContent != null && parsedProjectId != null) {
            finalJson = jsonContent;
            finalProjectId = parsedProjectId;
        }

        if (finalJson == null || finalProjectId == null) {
            Toast.makeText(this, "Please upload a google-services.json file or paste its content", Toast.LENGTH_LONG).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        btnSaveContinue.setEnabled(false);

        // 1. Configure secondary Firebase instance
        boolean success = FirebaseManager.setConfiguration(this, finalJson, companyName, finalProjectId);

        if (success) {
            // 2. Initialize secondary app
            FirebaseManager.initialize(this);
            EncryptionHelper.getInstance(this).saveUserRole("sender");

            Toast.makeText(this, "Network Configuration Saved Successfully!", Toast.LENGTH_SHORT).show();

            // 3. Move to MainActivity
            Intent intent = new Intent(ClientSetupActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            progressBar.setVisibility(View.GONE);
            btnSaveContinue.setEnabled(true);
            Toast.makeText(this, "Failed to save Firebase configuration. Please check the JSON format.", Toast.LENGTH_LONG).show();
        }
    }
}
