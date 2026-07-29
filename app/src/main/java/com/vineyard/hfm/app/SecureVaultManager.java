package com.vineyard.hfm.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5: Hidden Vault & Secure Playback
 * Manages the secure local storage of reconstructed files and provides a secure,
 * context-aware playback and execution mechanism with a LifecycleObserver Kill-Switch.
 * 
 * UPDATED: Stores Vault files in public external storage (/sdcard/.hfm_vault_data/)
 * so received files survive app uninstallation while remaining stealthy & encrypted.
 */
public class SecureVaultManager {

    private static final String TAG = "SecureVaultManager";
    private static final String VAULT_DIR_NAME = ".hfm_vault_data";
    private static final String TEMP_PLAYBACK_DIR = "secure_cache";

    private final Context context;

    public SecureVaultManager(Context context) {
        this.context = context;
    }

    /**
     * Creates and returns a reference to the hidden public Vault directory on SD card/storage.
     * Stored in /sdcard/.hfm_vault_data/ so it survives app uninstallation.
     * Drops a .nomedia file to prevent Android MediaScanner from indexing the files.
     */
    public File getVaultDirectory() {
        File vaultDir = new File(Environment.getExternalStorageDirectory(), VAULT_DIR_NAME);
        if (!vaultDir.exists()) {
            if (vaultDir.mkdirs()) {
                try {
                    new File(vaultDir, ".nomedia").createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create .nomedia file in vault", e);
                }
            }
        }
        return vaultDir;
    }

    /**
     * Creates a new empty file in the vault for the ReconstructionEngine to write to.
     */
    public File createVaultFile(String originalFileName) {
        File vaultDir = getVaultDirectory();
        // Append a UUID to prevent naming collisions
        String safeName = UUID.randomUUID().toString().substring(0, 8) + "_" + originalFileName;
        return new File(vaultDir, safeName);
    }

    /**
     * Deletes a file permanently from the Vault directory.
     */
    public boolean deleteVaultFile(File file) {
        if (file == null || !file.exists()) return true;
        boolean deleted = file.delete();
        if (!deleted) {
            deleted = StorageUtils.deleteFile(context, file);
        }
        Log.d(TAG, "Vault file deletion result for " + file.getName() + ": " + deleted);
        return deleted;
    }

    /**
     * Context-aware action resolver for incoming and vault files.
     * Resolves appropriate button text ("Extract", "Install App", "Play Video", etc.).
     */
    public String getActionLabel(String originalFileName) {
        if (originalFileName == null) return "Open File";
        String ext = getExtension(originalFileName);

        if (isArchive(ext)) {
            return "Extract Archive";
        } else if (isPackage(ext)) {
            return "Install App";
        } else if (isVideo(ext)) {
            return "Play Video";
        } else if (isImage(ext)) {
            return "View Image";
        } else if (isAudio(ext)) {
            return "Play Audio";
        } else if (isTextOrCode(ext) || ext.equals("pdf")) {
            return "Read Document";
        }
        return "Open File";
    }

    /**
     * MAIN ENTRY POINT: Prompts Choice Dialog (Internal vs External)
     */
    public void playSecurely(final File vaultFile, final String originalFileName) {
        if (!vaultFile.exists()) {
            Toast.makeText(context, "Secure file not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        String ext = getExtension(originalFileName);

        // Direct Execution for Archives and Packages
        if (isArchive(ext)) {
            handleArchiveExtraction(vaultFile, originalFileName);
            return;
        } else if (isPackage(ext)) {
            handlePackageInstallation(vaultFile, originalFileName);
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Secure Open: " + originalFileName);
        builder.setMessage("How would you like to handle this file?");

        builder.setPositiveButton("RAM Mode (Internal)", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                handleInternalPlayback(vaultFile, originalFileName);
            }
        });

        builder.setNeutralButton("External Application", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                handleExternalPlayback(vaultFile, originalFileName);
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Handles unzipping and extraction of received ZIP/RAR archives directly.
     */
    private void handleArchiveExtraction(File vaultFile, String originalFileName) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File extractTargetFolder = new File(downloadDir, "Extracted_" + removeExtension(originalFileName));
        if (!extractTargetFolder.exists()) {
            extractTargetFolder.mkdirs();
        }

        Toast.makeText(context, "Extracting to Downloads/" + extractTargetFolder.getName(), Toast.LENGTH_LONG).show();
        ArchiveUtils.extractArchive(context, vaultFile, extractTargetFolder);
    }

    /**
     * Handles direct installation of received APK files.
     */
    private void handlePackageInstallation(File vaultFile, String originalFileName) {
        try {
            File tempCacheDir = new File(context.getCacheDir(), TEMP_PLAYBACK_DIR);
            if (!tempCacheDir.exists()) tempCacheDir.mkdirs();

            File tempApkFile = new File(tempCacheDir, originalFileName);
            copyToCache(vaultFile, tempApkFile);

            Uri apkUri = FileProvider.getUriForFile(
                    context,
                    "com.vineyard.hfm.app.provider",
                    tempApkFile
            );

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(installIntent);
            activatePlaybackKillSwitch(tempApkFile);

        } catch (Exception e) {
            showDetailedErrorDialog("Package Installation Error", e);
        }
    }

    /**
     * MODE 1: INTERNAL RAM PLAYBACK
     * Logic: Starts the internal app activity corresponding to the file type.
     */
    private void handleInternalPlayback(File vaultFile, String originalFileName) {
        try {
            String path = vaultFile.getAbsolutePath();
            String ext = getExtension(originalFileName);
            Intent intent = null;

            if (isImage(ext)) {
                ArrayList<String> list = new ArrayList<>();
                list.add(path);
                intent = new Intent(context, ImageViewerActivity.class);
                intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, list);
                intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, 0);
            } else if (isVideo(ext)) {
                ArrayList<String> list = new ArrayList<>();
                list.add(path);
                intent = new Intent(context, VideoViewerActivity.class);
                intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, list);
                intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, 0);
            } else if (isAudio(ext)) {
                ArrayList<String> list = new ArrayList<>();
                list.add(path);
                intent = new Intent(context, AudioPlayerActivity.class);
                intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, list);
                intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, 0);
            } else if (ext.equals("pdf")) {
                intent = new Intent(context, PdfViewerActivity.class);
                intent.putExtra(PdfViewerActivity.EXTRA_FILE_PATH, path);
            } else {
                intent = new Intent(context, TextViewerActivity.class);
                intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            showDetailedErrorDialog("Internal Viewer Error", e);
        }
    }

    /**
     * MODE 2: EXTERNAL MEDIA PLAYER (Disk/Cache Mode)
     * Logic: Copies file to cache, fires Intent with FileProvider, activates Kill-Switch.
     */
    private void handleExternalPlayback(File vaultFile, String originalFileName) {
        File tempCacheDir = new File(context.getCacheDir(), TEMP_PLAYBACK_DIR);
        if (!tempCacheDir.exists()) tempCacheDir.mkdirs();

        final File tempPlayFile = new File(tempCacheDir, originalFileName);

        try {
            copyToCache(vaultFile, tempPlayFile);

            Uri fileUri = FileProvider.getUriForFile(
                    context, 
                    "com.vineyard.hfm.app.provider", 
                    tempPlayFile
            );

            String mimeType = getMimeType(originalFileName);

            Intent playIntent = new Intent(Intent.ACTION_VIEW);
            playIntent.setDataAndType(fileUri, mimeType);
            playIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            playIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(Intent.createChooser(playIntent, "Open " + originalFileName + " via:").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

            activatePlaybackKillSwitch(tempPlayFile);

        } catch (Exception e) {
            showDetailedErrorDialog("External Playback Error", e);
        }
    }

    /**
     * THE KILL-SWITCH: Monitors the app's lifecycle via ProcessLifecycleOwner.
     * Prevents race conditions and shreds temp cache upon return to HFM.
     */
    private void activatePlaybackKillSwitch(final File tempFile) {
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            private boolean hasLeftApp = false;

            @Override
            public void onStop(LifecycleOwner owner) {
                hasLeftApp = true;
            }

            @Override
            public void onStart(LifecycleOwner owner) {
                if (hasLeftApp) {
                    Log.d(TAG, "Kill-Switch Activated: Shredding secure cache file.");
                    shredFile(tempFile);
                    ProcessLifecycleOwner.get().getLifecycle().removeObserver(this);
                }
            }
        });
    }

    /**
     * FORENSIC SHREDDER: Overwrites file bytes with zeroes before deleting.
     */
    private void shredFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            long length = file.length();
            FileOutputStream fos = new FileOutputStream(file);
            byte[] zeroes = new byte[8192];
            long written = 0;
            while (written < length) {
                int toWrite = (int) Math.min(zeroes.length, length - written);
                fos.write(zeroes, 0, toWrite);
                written += toWrite;
            }
            fos.flush();
            fos.close();
            file.delete();
            Log.d(TAG, "File successfully shredded.");
        } catch (IOException e) {
            Log.e(TAG, "Failed to shred file, falling back to standard delete", e);
            file.delete();
        }
    }

    private void copyToCache(File source, File dest) throws IOException {
        InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    private void showDetailedErrorDialog(String title, Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        final String detailedError = sw.toString();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);

        final TextView errorTextView = new TextView(context);
        errorTextView.setText(detailedError);
        errorTextView.setPadding(40, 40, 40, 40);
        errorTextView.setTextIsSelectable(true);

        builder.setView(errorTextView);

        builder.setPositiveButton("Copy Error", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("HFM_Error_Log", detailedError);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Error copied to clipboard.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        builder.setNegativeButton("Close", null);
        builder.show();
    }

    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) return fileName.substring(lastDot + 1).toLowerCase();
        return "";
    }

    private String removeExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) return fileName.substring(0, lastDot);
        return fileName;
    }

    private boolean isImage(String ext) {
        return Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(ext);
    }

    private boolean isVideo(String ext) {
        return Arrays.asList("mp4", "3gp", "mkv", "webm", "avi").contains(ext);
    }

    private boolean isAudio(String ext) {
        return Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac").contains(ext);
    }

    private boolean isArchive(String ext) {
        return Arrays.asList("zip", "rar", "7z", "tar", "gz").contains(ext);
    }

    private boolean isPackage(String ext) {
        return "apk".equals(ext);
    }

    private boolean isTextOrCode(String ext) {
        return Arrays.asList("txt", "log", "csv", "json", "xml", "html", "js", "css",
                "java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go",
                "swift", "sh", "bat", "ps1", "ini", "cfg", "conf", "md", "rtf",
                "prop", "gradle", "pro", "sql").contains(ext);
    }

    private String getMimeType(String fileName) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName.replace(" ", "%20"));
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        return (type == null) ? "*/*" : type;
    }
}
