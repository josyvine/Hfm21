package com.vineyard.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VaultBrowserActivity extends Activity {

    private ImageButton backButton;
    private RecyclerView vaultRecyclerView;
    private TextView emptyVaultText;
    private VaultAdapter adapter;
    private List<File> vaultFiles;
    private SecureVaultManager secureVaultManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vault_browser);

        secureVaultManager = new SecureVaultManager(this);
        
        backButton = findViewById(R.id.back_button_vault);
        vaultRecyclerView = findViewById(R.id.vault_recycler_view);
        emptyVaultText = findViewById(R.id.empty_vault_text);

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        loadVaultFiles();
    }

    private void loadVaultFiles() {
        File vaultDir = secureVaultManager.getVaultDirectory();
        vaultFiles = new ArrayList<>();
        
        File[] files = vaultDir.listFiles();
        if (files != null) {
            for (File f : files) {
                // Ignore the .nomedia file used to hide the folder from gallery
                if (!f.getName().equals(".nomedia")) {
                    vaultFiles.add(f);
                }
            }
        }

        if (vaultFiles.isEmpty()) {
            emptyVaultText.setVisibility(View.VISIBLE);
            vaultRecyclerView.setVisibility(View.GONE);
        } else {
            emptyVaultText.setVisibility(View.GONE);
            vaultRecyclerView.setVisibility(View.VISIBLE);
            
            adapter = new VaultAdapter(this, vaultFiles, new VaultAdapter.OnItemClickListener() {
                @Override
                public void onItemClick(File file) {
                    // Logic: Parse the original filename.
                    // SecureVaultManager adds an 8-char UUID + "_" prefix (9 chars total).
                    String rawName = file.getName();
                    String originalName = rawName;
                    
                    if (rawName.length() > 9 && rawName.contains("_")) {
                        // Extract everything after the first underscore
                        originalName = rawName.substring(rawName.indexOf("_") + 1);
                    }
                    
                    String actionLabel = secureVaultManager.getActionLabel(originalName);
                    Toast.makeText(VaultBrowserActivity.this, "Executing: " + actionLabel + "...", Toast.LENGTH_SHORT).show();
                    
                    // Call the context-aware backend engine
                    secureVaultManager.playSecurely(file, originalName);
                }

                @Override
                public void onItemLongClick(final File file) {
                    // Extract clean original display name for the dialog
                    String rawName = file.getName();
                    String originalName = rawName;
                    if (rawName.length() > 9 && rawName.contains("_")) {
                        originalName = rawName.substring(rawName.indexOf("_") + 1);
                    }

                    showDeleteVaultFileDialog(file, originalName);
                }
            });
            
            vaultRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            vaultRecyclerView.setAdapter(adapter);
        }
    }

    /**
     * Prompts confirmation dialog to permanently remove a file from the Vault.
     */
    private void showDeleteVaultFileDialog(final File file, String displayName) {
        new AlertDialog.Builder(this)
                .setTitle("Delete from Vault")
                .setMessage("Permanently remove '" + displayName + "' from your Vault?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean deleted = secureVaultManager.deleteVaultFile(file);
                        if (deleted) {
                            Toast.makeText(VaultBrowserActivity.this, "File removed from Vault.", Toast.LENGTH_SHORT).show();
                            loadVaultFiles(); // Reload list to update UI
                        } else {
                            Toast.makeText(VaultBrowserActivity.this, "Failed to delete file from Vault.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
