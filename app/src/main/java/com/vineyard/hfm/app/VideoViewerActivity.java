package com.vineyard.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class VideoViewerActivity extends Activity {

    public static final String EXTRA_FILE_PATH_LIST = "file_path_list";
    public static final String EXTRA_CURRENT_INDEX = "current_index";
    public static final String RESULT_FILE_DELETED = "file_deleted";

    private VideoView videoView;
    private TextView fileNameTextView;
    private ImageButton deleteButton, closeButton, prevButton, nextButton;

    // NEW: Linked to modern Google Font icons in the XML
    private ImageButton rewindButton, playPauseFooterButton, forwardButton;

    private ImageButton openWithButton;
    private ImageButton shareButton;

    private RelativeLayout deletionProgressLayout;
    private ProgressBar deletionProgressBar;
    private TextView deletionProgressText;

    private ArrayList<String> mFilePaths;
    private int mCurrentIndex;
    private boolean mFileDeleted = false;

    private BroadcastReceiver deleteCompletionReceiver;
    private BroadcastReceiver compressionBroadcastReceiver;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_viewer);

        initializeViews();

        mFilePaths = getIntent().getStringArrayListExtra(EXTRA_FILE_PATH_LIST);
        mCurrentIndex = getIntent().getIntExtra(EXTRA_CURRENT_INDEX, -1);

        if (mFilePaths == null || mFilePaths.isEmpty() || mCurrentIndex == -1) {
            Toast.makeText(this, "Error: No file path provided.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setupListeners();
        setupBroadcastReceivers();
        loadVideo(mCurrentIndex);
    }

    private void initializeViews() {
        videoView = findViewById(R.id.video_view_full);
        fileNameTextView = findViewById(R.id.file_name_video_viewer);
        deleteButton = findViewById(R.id.delete_button_video_viewer);
        closeButton = findViewById(R.id.close_button_video_viewer);

        // Center Navigation buttons (Skip file)
        prevButton = findViewById(R.id.prev_button_video_viewer);
        nextButton = findViewById(R.id.next_button_video_viewer);

        // New playback buttons (Seek inside current file)
        rewindButton = findViewById(R.id.rewind_button_video);
        playPauseFooterButton = findViewById(R.id.play_pause_button_video);
        forwardButton = findViewById(R.id.forward_button_video);

        openWithButton = findViewById(R.id.open_with_button_video);
        shareButton = findViewById(R.id.share_button_video);
        deletionProgressLayout = findViewById(R.id.deletion_progress_layout);
        deletionProgressBar = findViewById(R.id.deletion_progress_bar);
        deletionProgressText = findViewById(R.id.deletion_progress_text);
    }


    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });

        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFileActionDialog();
            }
        });

        // Skip to Previous Video File
        prevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentIndex > 0) {
                    loadVideo(mCurrentIndex - 1);
                }
            }
        });

        // Skip to Next Video File
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentIndex < mFilePaths.size() - 1) {
                    loadVideo(mCurrentIndex + 1);
                }
            }
        });

        // Play / Pause Toggle Fix
        playPauseFooterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayPause();
            }
        });

        // FAST REWIND Logic (10 seconds)
        rewindButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (videoView != null) {
                    int currentPos = videoView.getCurrentPosition();
                    videoView.seekTo(Math.max(0, currentPos - 10000));
                    Toast.makeText(VideoViewerActivity.this, "-10s", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // FAST FORWARD Logic (10 seconds)
        forwardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (videoView != null) {
                    int currentPos = videoView.getCurrentPosition();
                    int duration = videoView.getDuration();
                    videoView.seekTo(Math.min(duration, currentPos + 10000));
                    Toast.makeText(VideoViewerActivity.this, "+10s", Toast.LENGTH_SHORT).show();
                }
            }
        });

        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareFile();
            }
        });

        openWithButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWith();
            }
        });
    }

    private void togglePlayPause() {
        if (videoView != null) {
            if (videoView.isPlaying()) {
                videoView.pause();
                playPauseFooterButton.setImageResource(R.drawable.play_arrow_24px);
            } else {
                videoView.start();
                playPauseFooterButton.setImageResource(R.drawable.pause_24px);
            }
        }
    }

    private void loadVideo(int index) {
        if (index < 0 || index >= mFilePaths.size()) {
            return;
        }
        mCurrentIndex = index;
        String filePath = mFilePaths.get(mCurrentIndex);
        File videoFile = new File(filePath);

        fileNameTextView.setText(videoFile.getName());
        
        videoView.setVideoURI(Uri.fromFile(videoFile));
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setLooping(true);
                videoView.start();
                playPauseFooterButton.setImageResource(R.drawable.pause_24px);
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(VideoViewerActivity.this, "Error: Could not play this video file.", Toast.LENGTH_LONG).show();
                return true;
            }
        });

        updateNavigationButtons();
    }

    private void updateNavigationButtons() {
        prevButton.setEnabled(mCurrentIndex > 0);
        nextButton.setEnabled(mCurrentIndex < mFilePaths.size() - 1);

        prevButton.setAlpha(mCurrentIndex > 0 ? 1.0f : 0.3f);
        nextButton.setAlpha(mCurrentIndex < mFilePaths.size() - 1 ? 1.0f : 0.3f);
    }

    private void showFileActionDialog() {
        videoView.pause();
        playPauseFooterButton.setImageResource(R.drawable.play_arrow_24px);

        final CharSequence[] options = {"Details", "Send to Drop Zone", "Compress", "Hide", "Move to Recycle Bin", "Delete Permanently"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Choose an action");
        builder.setItems(options, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case 0:
                        showDetailsDialog();
                        break;
                    case 1:
                        showSendToDropDialog(new File(mFilePaths.get(mCurrentIndex)));
                        break;
                    case 2:
                        compressFile();
                        break;
                    case 3:
                        hideFile();
                        break;
                    case 4: // Move to Recycle Bin
                        AlertDialog.Builder binBuilder = new AlertDialog.Builder(VideoViewerActivity.this);
                        binBuilder.setTitle("Choose Recycle Bin");
                        binBuilder.setItems(new CharSequence[]{"Phone Recycle Bin", "SD Card Recycle Bin"}, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int whichBin) {
                                moveToRecycleBin(whichBin == 1);
                            }
                        });
                        binBuilder.show();
                        break;
                    case 5:
                        performFileDeletion();
                        break;
                }
            }
        });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                videoView.start();
                playPauseFooterButton.setImageResource(R.drawable.pause_24px);
            }
        });
        builder.show();
    }

    private void showDetailsDialog() {
        final List<File> files = new ArrayList<>();
        files.add(new File(mFilePaths.get(mCurrentIndex)));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_details, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        final TextView basicDetailsText = dialogView.findViewById(R.id.details_text_basic);
        final TextView aiDetailsText = dialogView.findViewById(R.id.details_text_ai);
        final ProgressBar progressBar = dialogView.findViewById(R.id.details_progress_bar);
        final Button moreButton = dialogView.findViewById(R.id.details_button_more);
        final Button copyButton = dialogView.findViewById(R.id.details_button_copy);
        final Button closeButton = dialogView.findViewById(R.id.details_button_close);

        final AlertDialog dialog = builder.create();

        File file = files.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(file.getName()).append("\n");
        sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
        sb.append("Size: ").append(Formatter.formatFileSize(this, file.length())).append("\n");
        sb.append("Last Modified: ").append(new Date(file.lastModified()).toString());
        basicDetailsText.setText(sb.toString());

        final GeminiAnalyzer analyzer = new GeminiAnalyzer(this, aiDetailsText, progressBar, copyButton);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        moreButton.setEnabled(ApiKeyManager.getApiKey(this) != null && isConnected);

        moreButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                analyzer.analyze(files);
            }
        });

        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("AI Summary", aiDetailsText.getText());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(VideoViewerActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
            }
        });

        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                videoView.start();
                playPauseFooterButton.setImageResource(R.drawable.pause_24px);
            }
        });

        dialog.show();
    }

    private void showSendToDropDialog(final File fileToSend) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
        final AutoCompleteTextView receiverUsernameInput = dialogView.findViewById(R.id.edit_text_receiver_username);

        // GLITCH 3 FIX: Bind Auto-Complete Dropdown using EncryptionHelper
        EncryptionHelper.getInstance(this).setupAutoComplete(this, receiverUsernameInput);

        builder.setView(dialogView)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String receiverUsername = receiverUsernameInput.getText().toString().trim();
                        if (receiverUsername.isEmpty()) {
                            Toast.makeText(VideoViewerActivity.this, "Receiver username cannot be empty.", Toast.LENGTH_SHORT).show();
                        } else {
                            // GLITCH 3 FIX: Save receiver username to local preferences
                            EncryptionHelper.getInstance(VideoViewerActivity.this).saveReceiverUsername(receiverUsername);
                            showSenderWarningDialog(receiverUsername, fileToSend);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        builder.create().show();
    }

    private void showSenderWarningDialog(final String receiverUsername, final File fileToSend) {
        showSenderWarningDialog(receiverUsername, null, fileToSend);
    }

    private void showSenderWarningDialog(final String receiverUsername, final String existingSecretNumber, final File fileToSend) {
        final String secretNumber = (existingSecretNumber != null) ? existingSecretNumber : generateSecretNumber();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Important: Connection Stability")
                .setMessage("You are about to act as a temporary server for this file transfer.\n\n"
                        + "Please keep the app open and maintain a stable internet connection until the transfer is complete.\n\n"
                        + "Your Secret Number for this transfer is:\n" + secretNumber + "\n\nShare this number with the receiver.")
                .setPositiveButton("I Understand, Start Sending", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startSenderService(receiverUsername, secretNumber, fileToSend);
                    }
                })
                .setNeutralButton("Copy PIN", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            ClipData clip = ClipData.newPlainText("Secret PIN", secretNumber);
                            clipboard.setPrimaryClip(clip);
                            Toast.makeText(VideoViewerActivity.this, "Secret PIN copied to clipboard!", Toast.LENGTH_SHORT).show();
                        }
                        showSenderWarningDialog(receiverUsername, secretNumber, fileToSend);
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
                        showSenderWarningDialog(receiverUsername, secretNumber, fileToSend);
                    }
                });
        builder.create().show();
    }

    private void startSenderService(String receiverUsername, String secretNumber, File fileToSend) {
        if (fileToSend == null || !fileToSend.exists()) {
            Toast.makeText(this, "Error: File to send does not exist.", Toast.LENGTH_SHORT).show();
            return;
        }

        // GLITCH 3 FIX: Save username to history
        EncryptionHelper.getInstance(this).saveReceiverUsername(receiverUsername);

        ArrayList<String> filePaths = new ArrayList<>();
        filePaths.add(fileToSend.getAbsolutePath());

        // GLITCH 4 FIX: Pass file paths via EXTRA_FILE_PATHS
        Intent intent = new Intent(this, SenderService.class);
        intent.setAction(SenderService.ACTION_START_SEND);
        intent.putStringArrayListExtra(SenderService.EXTRA_FILE_PATHS, filePaths);
        intent.putExtra(SenderService.EXTRA_RECEIVER_USERNAME, receiverUsername);
        intent.putExtra(SenderService.EXTRA_SECRET_NUMBER, secretNumber);
        ContextCompat.startForegroundService(this, intent);
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

    private void compressFile() {
        File currentFile = new File(mFilePaths.get(mCurrentIndex));
        File parentDir = currentFile.getParentFile();
        if (parentDir != null) {
            List<File> filesToCompress = new ArrayList<>();
            filesToCompress.add(currentFile);
            ArchiveUtils.startCompression(this, filesToCompress, parentDir);
            Toast.makeText(this, "Compression started in background.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Cannot determine destination for archive.", Toast.LENGTH_SHORT).show();
        }
        videoView.start();
    }

    private void hideFile() {
        String filePath = mFilePaths.get(mCurrentIndex);
        ArrayList<File> filesToHide = new ArrayList<>();
        filesToHide.add(new File(filePath));

        Intent intent = new Intent(this, FileHiderActivity.class);
        intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) filesToHide);
        startActivity(intent);

        mFileDeleted = true;
        mFilePaths.remove(mCurrentIndex);

        if (mFilePaths.isEmpty()) {
            onBackPressed();
        } else if (mCurrentIndex >= mFilePaths.size()) {
            loadVideo(mFilePaths.size() - 1);
        } else {
            loadVideo(mCurrentIndex);
        }
    }

    private void moveToRecycleBin(boolean useSdCardBin) {
        String filePath = mFilePaths.get(mCurrentIndex);
        File sourceFile = new File(filePath);
        if (!sourceFile.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean moveSuccess = false;
        File destFile = null;

        if (useSdCardBin && StorageUtils.isFileOnSdCard(this, sourceFile)) {
            if (StorageUtils.moveFileOnSdCardSafely(this, sourceFile)) {
                moveSuccess = true;
            } else {
                Toast.makeText(this, "SD Card move failed. Using fallback.", Toast.LENGTH_SHORT).show();
            }
        }

        if (!moveSuccess) {
            File recycleBinDir = new File(Environment.getExternalStorageDirectory(), "HFMRecycleBin");
            if (!recycleBinDir.exists()) {
                if (!recycleBinDir.mkdir()) {
                    Toast.makeText(this, "Failed to create Recycle Bin folder.", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            destFile = new File(recycleBinDir, sourceFile.getName());
            if (destFile.exists()) {
                String name = sourceFile.getName();
                String extension = "";
                int dotIndex = name.lastIndexOf(".");
                if (dotIndex > 0) {
                    extension = name.substring(dotIndex);
                    name = name.substring(0, dotIndex);
                }
                destFile = new File(recycleBinDir, name + "_" + System.currentTimeMillis() + extension);
            }

            if (sourceFile.renameTo(destFile)) {
                moveSuccess = true;
            } else {
                if (StorageUtils.copyFile(this, sourceFile, destFile)) {
                    if (StorageUtils.deleteFile(this, sourceFile)) {
                        moveSuccess = true;
                    } else {
                        destFile.delete();
                    }
                }
            }
        }

        if (moveSuccess) {
            Toast.makeText(this, "File moved to Recycle Bin.", Toast.LENGTH_SHORT).show();
            mFileDeleted = true;

            // Immediately purge source path from MediaStore DB to resolve Glitch 1
            MediaStoreUtils.purgePathFromMediaStore(this, sourceFile.getAbsolutePath());
            if (destFile != null) {
                MediaStoreUtils.scanNewPath(this, destFile);
            }

            mFilePaths.remove(mCurrentIndex);

            if (mFilePaths.isEmpty()) {
                onBackPressed();
            } else if (mCurrentIndex >= mFilePaths.size()) {
                loadVideo(mFilePaths.size() - 1);
            } else {
                loadVideo(mCurrentIndex);
            }
        } else {
            Toast.makeText(this, "Failed to move file.", Toast.LENGTH_SHORT).show();
            videoView.start();
        }
    }

    private void performFileDeletion() {
        String filePath = mFilePaths.get(mCurrentIndex);
        ArrayList<String> filesToDelete = new ArrayList<>();
        filesToDelete.add(filePath);

        deletionProgressLayout.setVisibility(View.VISIBLE);
        deletionProgressBar.setIndeterminate(true);
        deletionProgressText.setText("Deleting...");

        Intent intent = new Intent(this, DeleteService.class);
        intent.putStringArrayListExtra(DeleteService.EXTRA_FILES_TO_DELETE, filesToDelete);
        intent.putExtra("batch_size", 1);
        ContextCompat.startForegroundService(this, intent);
    }

    private void shareFile() {
        String filePath = mFilePaths.get(mCurrentIndex);
        File fileToShare = new File(filePath);
        if (!fileToShare.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri fileUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", fileToShare);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.setType(getMimeType(fileToShare.getAbsolutePath()));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share file via"));
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Error: Could not generate a sharable link for this file.", Toast.LENGTH_LONG).show();
        }
    }

    private void openWith() {
        String filePath = mFilePaths.get(mCurrentIndex);
        File fileToOpen = new File(filePath);
        if (!fileToOpen.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri fileUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", fileToOpen);
            Intent openIntent = new Intent(Intent.ACTION_VIEW);
            openIntent.setDataAndType(fileUri, getMimeType(fileToOpen.getAbsolutePath()));
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(openIntent, "Open with"));
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Error: Could not generate a link to open this file.", Toast.LENGTH_LONG).show();
        }
    }

    private String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    @Override
    public void onBackPressed() {
        if (mFileDeleted) {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(RESULT_FILE_DELETED, true);
            setResult(Activity.RESULT_OK, resultIntent);
        } else {
            setResult(Activity.RESULT_CANCELED);
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView.isPlaying()) {
            videoView.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!videoView.isPlaying()) {
            videoView.start();
        }
    }

    private void setupBroadcastReceivers() {
        deleteCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                deletionProgressLayout.setVisibility(View.GONE);
                int deletedCount = intent.getIntExtra(DeleteService.EXTRA_DELETED_COUNT, 0);
                if (deletedCount > 0) {
                    mFileDeleted = true;
                    mFilePaths.remove(mCurrentIndex);

                    if (mFilePaths.isEmpty()) {
                        onBackPressed();
                    } else if (mCurrentIndex >= mFilePaths.size()) {
                        loadVideo(mFilePaths.size() - 1);
                    } else {
                        loadVideo(mCurrentIndex);
                    }
                } else {
                    Toast.makeText(VideoViewerActivity.this, "Failed to delete the file.", Toast.LENGTH_SHORT).show();
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(deleteCompletionReceiver, new IntentFilter(DeleteService.ACTION_DELETE_COMPLETE));

        compressionBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Do nothing specific on compression complete in this screen
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(compressionBroadcastReceiver, new IntentFilter(CompressionService.ACTION_COMPRESSION_COMPLETE));
    }

    @Override
    protected void onDestroy() {
        if (deleteCompletionReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(deleteCompletionReceiver);
        }
        if (compressionBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(compressionBroadcastReceiver);
        }
        super.onDestroy();
    }
}