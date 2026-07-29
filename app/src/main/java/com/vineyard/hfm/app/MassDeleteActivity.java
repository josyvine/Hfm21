package com.vineyard.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MassDeleteActivity extends Activity implements MassDeleteAdapter.OnItemClickListener, MassDeleteAdapter.OnHeaderCheckedChangeListener, MassDeleteAdapter.OnHeaderClickListener, DragSelectTouchListener.OnDragSelectListener {

    private static final String TAG = "MassDeleteActivity";

    private ImageButton closeButton, filterButton, deleteButton;
    private AutoCompleteTextView searchInput;
    private RecyclerView searchResultsGrid;
    private MassDeleteAdapter adapter;
    private GridLayoutManager gridLayoutManager;

    private List<Object> masterList = new ArrayList<>();
    private List<Object> displayList = new ArrayList<>();

    private String currentFilterType = "all";
    private ScaleGestureDetector scaleGestureDetector;
    private int currentSpanCount = 3;
    private static final int MIN_SPAN_COUNT = 1;
    private static final int MAX_SPAN_COUNT = 8;

    private RelativeLayout deletionProgressLayout;
    private ProgressBar deletionProgressBar;
    private TextView deletionProgressText;
    private BroadcastReceiver deleteCompletionReceiver;
    private BroadcastReceiver compressionBroadcastReceiver;

    private static final int CATEGORY_IMAGES = 1;
    private static final int CATEGORY_VIDEOS = 2;
    private static final int CATEGORY_AUDIO = 3;
    private static final int CATEGORY_DOCS = 4;
    private static final int CATEGORY_OTHER = 5;

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private static final Pattern FILE_BASE_NAME_PATTERN = Pattern.compile("^(IMG|VID|PANO|DSC)_\\d{8}_\\d{6}");

    private List<MassDeleteAdapter.SearchResult> mResultsPendingPermission;
    private Runnable mPendingOperation;

    private ArrayList<String> mPendingFilePathsToDelete;
    private int mPendingBatchSize;

    public static class DateHeader {
        private final String dateString;
        private boolean isChecked;
        private boolean isExpanded;

        public DateHeader(String dateString) {
            this.dateString = dateString;
            this.isChecked = false;
            this.isExpanded = true;
        }

        public String getDateString() { return dateString; }
        public boolean isChecked() { return isChecked; }
        public void setChecked(boolean checked) { isChecked = checked; }
        public boolean isExpanded() { return isExpanded; }
        public void setExpanded(boolean expanded) { isExpanded = expanded; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mass_delete);

        initializeViews();
        setupListeners();
        setupRecyclerView();
        setupPinchToZoom();
        setupBroadcastReceivers();
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button);
        filterButton = findViewById(R.id.filter_button);
        deleteButton = findViewById(R.id.delete_button);
        searchInput = findViewById(R.id.search_input);
        searchResultsGrid = findViewById(R.id.search_results_grid);

        deletionProgressLayout = findViewById(R.id.deletion_progress_layout);
        deletionProgressBar = findViewById(R.id.deletion_progress_bar);
        deletionProgressText = findViewById(R.id.deletion_progress_text);
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        filterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterMenu(v);
            }
        });

        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFileOperationsDialog();
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                fetchFolderSuggestions(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        searchInput.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String suggestion = (String) parent.getItemAtPosition(position);
                String currentText = searchInput.getText().toString();
                int lastSpaceIndex = currentText.lastIndexOf(' ');
                String newText = (lastSpaceIndex != -1) ? currentText.substring(0, lastSpaceIndex + 1) + suggestion + " " : suggestion + " ";
                searchInput.setText(newText);
                searchInput.setSelection(newText.length());
            }
        });

        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                executeQuery(searchInput.getText().toString());
                InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
                return true;
            }
        });
    }

    private void setupRecyclerView() {
        gridLayoutManager = new GridLayoutManager(this, currentSpanCount);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position >= 0 && position < displayList.size()) {
                    if (displayList.get(position) instanceof DateHeader) {
                        return currentSpanCount;
                    }
                }
                return 1;
            }
        });

        searchResultsGrid.setLayoutManager(gridLayoutManager);
        adapter = new MassDeleteAdapter(this, displayList, this, this, this);
        searchResultsGrid.setAdapter(adapter);

        DragSelectTouchListener dragSelectTouchListener = new DragSelectTouchListener(this, this);
        searchResultsGrid.addOnItemTouchListener(dragSelectTouchListener);
    }

    private void setupPinchToZoom() {
        scaleGestureDetector = new ScaleGestureDetector(this, new PinchZoomListener());
        searchResultsGrid.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                scaleGestureDetector.onTouchEvent(event);
                return false;
            }
        });
    }

    private void executeQuery(final String query) {
        searchExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final QueryParameters params = parseQuery(query);
                    List<MassDeleteAdapter.SearchResult> mediaStoreResults = executeQueryWithMediaStore(params);

                    if (!mediaStoreResults.isEmpty()) {
                        updateUIWithResults(mediaStoreResults, params);
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MassDeleteActivity.this, "MediaStore found nothing. Starting deep scan...", Toast.LENGTH_SHORT).show();
                            }
                        });
                        List<MassDeleteAdapter.SearchResult> fileSystemResults = performFallbackFileSearch(params);
                        updateUIWithResults(fileSystemResults, params);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Search query execution failed, switching to deep scan fallback", e);
                    final QueryParameters params = parseQuery(query);
                    List<MassDeleteAdapter.SearchResult> fileSystemResults = performFallbackFileSearch(params);
                    updateUIWithResults(fileSystemResults, params);
                }
            }
        });
    }

    private void updateUIWithResults(final List<MassDeleteAdapter.SearchResult> results, final QueryParameters params) {
        final List<Object> groupedList = processAndGroupResults(results);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                masterList.clear();
                masterList.addAll(groupedList);

                if (params.startRange != -1 && params.endRange != -1) {
                    int fileCounter = 0;
                    int start = Math.max(0, params.startRange - 1);
                    int end = params.endRange - 1;

                    for (Object obj : masterList) {
                        if (obj instanceof MassDeleteAdapter.SearchResult) {
                            if (fileCounter >= start && fileCounter <= end) {
                                ((MassDeleteAdapter.SearchResult) obj).setExcluded(false);
                            }
                            fileCounter++;
                        }
                    }
                }

                rebuildDisplayList();
                if (results.isEmpty()) {
                    Toast.makeText(MassDeleteActivity.this, "No files found.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private List<Object> processAndGroupResults(List<MassDeleteAdapter.SearchResult> flatResults) {
        List<Object> groupedList = new ArrayList<>();
        if (flatResults.isEmpty()) {
            return groupedList;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
        String currentHeaderDate = "";

        for (MassDeleteAdapter.SearchResult result : flatResults) {
            String resultDate = sdf.format(new Date(result.getLastModifiedForGrouping()));
            if (!resultDate.equals(currentHeaderDate)) {
                currentHeaderDate = resultDate;
                groupedList.add(new DateHeader(currentHeaderDate));
            }
            groupedList.add(result);
        }

        return groupedList;
    }

    private void rebuildDisplayList() {
        displayList.clear();
        boolean isCurrentGroupExpanded = true;

        for (Object item : masterList) {
            if (item instanceof DateHeader) {
                DateHeader header = (DateHeader) item;
                displayList.add(header);
                isCurrentGroupExpanded = header.isExpanded();
            } else {
                if (isCurrentGroupExpanded) {
                    displayList.add(item);
                }
            }
        }
        adapter.updateData(displayList);
    }

    private List<MassDeleteAdapter.SearchResult> executeQueryWithMediaStore(QueryParameters params) {
        List<MassDeleteAdapter.SearchResult> results = new ArrayList<>();

        try {
            StringBuilder selection = new StringBuilder();
            List<String> selectionArgs = new ArrayList<>();
            Uri queryUri = MediaStore.Files.getContentUri("external");
            addFilterClauses(selection, selectionArgs);

            if (selection.length() > 0) selection.append(" AND ");
            selection.append(MediaStore.Files.FileColumns.DATA + " NOT LIKE ?");
            selectionArgs.add("%/HFMRecycleBin/%");

            if (params.folderPath != null && !params.folderPath.isEmpty()) {
                if (selection.length() > 0) selection.append(" AND ");
                selection.append(MediaStore.Files.FileColumns.DATA + " LIKE ?");
                selectionArgs.add("%" + params.folderPath + "%");
            }

            String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA
            };

            // FIX: Standard sort order without invalid LIMIT/OFFSET string syntax that breaks Vivo ContentResolver
            String sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

            Cursor cursor = getContentResolver().query(queryUri, projection, selection.toString(),
                    selectionArgs.toArray(new String[0]), sortOrder);

            if (cursor != null) {
                try {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                    int mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
                    int displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                    int dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                    int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        int mediaType = cursor.getInt(mediaTypeColumn);
                        String displayName = cursor.getString(displayNameColumn);
                        long dateModifiedMillis = cursor.getLong(dateModifiedColumn) * 1000;
                        String path = cursor.getString(dataColumn);

                        if (path != null) {
                            File f = new File(path);
                            if (!f.exists()) {
                                continue;
                            }
                            long fsDate = f.lastModified();
                            if (fsDate > dateModifiedMillis) {
                                dateModifiedMillis = fsDate;
                            }
                        }

                        Uri contentUri;
                        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                            contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        } else if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                            contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                        } else {
                            contentUri = ContentUris.withAppendedId(queryUri, id);
                        }

                        results.add(new MassDeleteAdapter.SearchResult(contentUri, id, dateModifiedMillis, displayName, path));
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error performing MediaStore query on Vivo device", e);
        }

        return results;
    }

    private List<MassDeleteAdapter.SearchResult> performFallbackFileSearch(QueryParameters params) {
        List<MassDeleteAdapter.SearchResult> results = new ArrayList<>();
        File externalStorage = Environment.getExternalStorageDirectory();

        List<File> rootsToScan = new ArrayList<>();
        rootsToScan.add(new File(externalStorage, "WhatsApp"));
        rootsToScan.add(new File(externalStorage, "Android/media/com.whatsapp/WhatsApp"));
        rootsToScan.add(new File(externalStorage, "Download"));
        rootsToScan.add(new File(externalStorage, "Telegram"));
        rootsToScan.add(new File(externalStorage, "DCIM"));
        rootsToScan.add(new File(externalStorage, "Pictures"));
        rootsToScan.add(new File(externalStorage, "DCIM/Camera"));

        File dualAppStorage = new File("/storage/emulated/999");
        if (dualAppStorage.exists() && dualAppStorage.canRead()) {
             rootsToScan.add(new File(dualAppStorage, "WhatsApp"));
             rootsToScan.add(new File(dualAppStorage, "Android/media/com.whatsapp/WhatsApp"));
             rootsToScan.add(new File(dualAppStorage, "DCIM"));
             rootsToScan.add(new File(dualAppStorage, "Download"));
        }
        
        File parallelAppStorage = new File("/storage/emulated/10");
        if (parallelAppStorage.exists() && parallelAppStorage.canRead()) {
             rootsToScan.add(new File(parallelAppStorage, "WhatsApp"));
             rootsToScan.add(new File(parallelAppStorage, "DCIM"));
        }

        for (File root : rootsToScan) {
            if (root.exists() && root.isDirectory()) {
                scanDirectory(root, params, results);
            }
        }

        Collections.sort(results, new Comparator<MassDeleteAdapter.SearchResult>() {
            @Override
            public int compare(MassDeleteAdapter.SearchResult r1, MassDeleteAdapter.SearchResult r2) {
                return Long.compare(r2.getLastModifiedForGrouping(), r1.getLastModifiedForGrouping());
            }
        });
        return results;
    }

    private void scanDirectory(File directory, QueryParameters params, List<MassDeleteAdapter.SearchResult> results) {
        if (directory.getName().equalsIgnoreCase("HFMRecycleBin")) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().equalsIgnoreCase("HFMRecycleBin")) {
                    if (params.folderPath == null || file.getAbsolutePath().toLowerCase().contains(params.folderPath.toLowerCase())) {
                        scanDirectory(file, params, results);
                    }
                }
            } else {
                boolean folderMatch = (params.folderPath == null) ||
                    (file.getAbsolutePath().toLowerCase().contains(params.folderPath.toLowerCase()));

                if (folderMatch) {
                    if (isFileTypeMatch(file.getName())) {
                        results.add(new MassDeleteAdapter.SearchResult(Uri.fromFile(file), file.lastModified(), file.lastModified(), file.getName(), file.getAbsolutePath()));
                    }
                }
            }
        }
    }

    private void addFilterClauses(StringBuilder selection, List<String> selectionArgs) {
        if ("images".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?");
            selectionArgs.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE));
        } else if ("videos".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?");
            selectionArgs.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO));
        } else if ("documents".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?, ?, ?)");
            selectionArgs.addAll(Arrays.asList("application/pdf", "application/msword",
                                               "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel",
                                               "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint",
                                               "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        } else if ("archives".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?)");
            selectionArgs.addAll(Arrays.asList("application/zip", "application/vnd.rar", "application/x-7z-compressed",
                                               "application/x-tar", "application/gzip"));
        } else if ("other".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " NOT IN (?, ?, ?)");
            selectionArgs.addAll(Arrays.asList(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                                               String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
                                               String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)));
        }
    }

    private boolean isFileTypeMatch(String fileName) {
        if (currentFilterType.equals("all")) return true;
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase();
        }
        switch (currentFilterType) {
            case "images": return Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(extension);
            case "videos": return Arrays.asList("mp4", "3gp", "mkv", "webm", "avi").contains(extension);
            case "documents": return Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt").contains(extension);
            case "archives": return Arrays.asList("zip", "rar", "7z", "tar", "gz").contains(extension);
            case "other": return !isFileTypeMatch(fileName, "images") && !isFileTypeMatch(fileName, "videos") && !isFileTypeMatch(fileName, "documents") && !isFileTypeMatch(fileName, "archives");
            default: return true;
        }
    }

    private boolean isFileTypeMatch(String fileName, String type) {
        String originalFilter = this.currentFilterType;
        this.currentFilterType = type;
        boolean match = isFileTypeMatch(fileName);
        this.currentFilterType = originalFilter;
        return match;
    }

    private QueryParameters parseQuery(String query) {
        QueryParameters params = new QueryParameters();
        String modifiedQuery = query;

        Pattern rangePattern = Pattern.compile("(\\d+)\\s+to\\s+(\\d+)");
        Matcher matcher = rangePattern.matcher(query);
        if (matcher.find()) {
            try {
                params.startRange = Integer.parseInt(matcher.group(1));
                params.endRange = Integer.parseInt(matcher.group(2));
                modifiedQuery = matcher.replaceAll("").trim();
            } catch (NumberFormatException e) {
            }
        }

        String[] parts = modifiedQuery.trim().split("\\s+");
        if (parts.length == 1 && parts[0].isEmpty()) {
            return params;
        }

        StringBuilder folderBuilder = new StringBuilder();
        for (String part : parts) {
            if (folderBuilder.length() > 0) folderBuilder.append(" ");
            folderBuilder.append(part);
        }

        String finalFolderPath = folderBuilder.toString().trim();
        if (!finalFolderPath.isEmpty()) {
            params.folderPath = finalFolderPath;
        }
        return params;
    }

    private void fetchFolderSuggestions(final String constraint) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String lastWord = constraint;
                int lastSpaceIndex = constraint.lastIndexOf(' ');
                if (lastSpaceIndex != -1) {
                    lastWord = constraint.substring(lastSpaceIndex + 1);
                }
                if (lastWord.isEmpty()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (searchInput != null) searchInput.dismissDropDown();
                        }
                    });
                    return;
                }
                Set<String> folderSet = new HashSet<>();
                Uri uri = MediaStore.Files.getContentUri("external");
                String[] projection = {MediaStore.Files.FileColumns.DATA};
                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(uri, projection, null, null, null);
                    if (cursor != null) {
                        int dataColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                        while (cursor.moveToNext()) {
                            String path = cursor.getString(dataColumn);
                            if (path != null) {
                                File parentFile = new File(path).getParentFile();
                                if (parentFile != null && parentFile.getName().toLowerCase().startsWith(lastWord.toLowerCase())) {
                                    folderSet.add(parentFile.getName());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching folder suggestions", e);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                final List<String> suggestions = new ArrayList<>(folderSet);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (searchInput == null) return;
                        ArrayAdapter<String> suggestionAdapter = new ArrayAdapter<>(MassDeleteActivity.this, android.R.layout.simple_dropdown_item_1line, suggestions);
                        searchInput.setAdapter(suggestionAdapter);
                        if (!suggestions.isEmpty() && searchInput.isFocused()) {
                            searchInput.showDropDown();
                        }
                    }
                });
            }
        }).start();
    }

    private void initiateDeletionProcess() {
        // Execute on Parallel Thread Pool to prevent global AsyncTask queue starvation
        new PreDeletionCheckTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private class PreDeletionCheckTask extends AsyncTask<Void, Void, PreDeletionResults> {
        private AlertDialog progressDialog;

        @Override
        protected void onPreExecute() {
            progressDialog = new AlertDialog.Builder(MassDeleteActivity.this)
                    .setMessage("Processing selection...")
                    .setCancelable(false)
                    .create();
            progressDialog.show();
        }

        @Override
        protected PreDeletionResults doInBackground(Void... voids) {
            List<MassDeleteAdapter.SearchResult> toDelete = new ArrayList<>();
            boolean requiresSdCardPermission = false;

            String sdCardPath = StorageUtils.getSdCardPath(MassDeleteActivity.this);
            boolean hasSdPermission = StorageUtils.hasSdCardPermission(MassDeleteActivity.this);

            for (Object obj : masterList) {
                if (obj instanceof MassDeleteAdapter.SearchResult) {
                    MassDeleteAdapter.SearchResult item = (MassDeleteAdapter.SearchResult) obj;
                    if (!item.isExcluded()) {
                        toDelete.add(item);
                        if (!requiresSdCardPermission && sdCardPath != null && !hasSdPermission) {
                            File file = getFileFromResult(item);
                            if (file != null && file.getAbsolutePath().startsWith(sdCardPath)) {
                                requiresSdCardPermission = true;
                            }
                        }
                    }
                }
            }

            if (toDelete.isEmpty()) return null;
            return new PreDeletionResults(toDelete, requiresSdCardPermission);
        }

        @Override
        protected void onPostExecute(PreDeletionResults results) {
            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();

            if (results == null) {
                Toast.makeText(MassDeleteActivity.this, "No files selected for deletion.", Toast.LENGTH_LONG).show();
                return;
            }

            if (results.requiresSdCardPermission) {
                mResultsPendingPermission = results.processedDeleteList;
                mPendingOperation = () -> confirmAndDelete(results.processedDeleteList);
                promptForSdCardPermission();
            } else {
                confirmAndDelete(results.processedDeleteList);
            }
        }
    }

    private static class PreDeletionResults {
        List<MassDeleteAdapter.SearchResult> processedDeleteList;
        boolean requiresSdCardPermission;

        PreDeletionResults(List<MassDeleteAdapter.SearchResult> processedDeleteList, boolean requiresSdCardPermission) {
            this.processedDeleteList = processedDeleteList;
            this.requiresSdCardPermission = requiresSdCardPermission;
        }
    }

    private void confirmAndDelete(final List<MassDeleteAdapter.SearchResult> toDelete) {
        new AlertDialog.Builder(this).setTitle("Confirm Action")
            .setMessage("Choose an action for the " + toDelete.size() + " selected file(s).")
            .setPositiveButton("Delete Permanently", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final String[] batchOptions = {"50", "100", "500", "1000", "Max (All at once)"};
                    final int[] batchValues = {50, 100, 500, 1000, 100000};

                    new AlertDialog.Builder(MassDeleteActivity.this)
                        .setTitle("Select Deletion Speed")
                        .setItems(batchOptions, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int index) {
                                performDelete(toDelete, batchValues[index]);
                            }
                        }).show();
                }
            })
            .setNeutralButton("Move to Recycle", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    AlertDialog.Builder binBuilder = new AlertDialog.Builder(MassDeleteActivity.this);
                    binBuilder.setTitle("Choose Recycle Bin");
                    binBuilder.setItems(new CharSequence[]{"Phone Recycle Bin", "SD Card Recycle Bin"}, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int whichBin) {
                            moveToRecycleBin(toDelete, whichBin == 1);
                        }
                    });
                    binBuilder.show();
                }
            })
            .setNegativeButton("Hide Files", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    hideFiles(toDelete);
                }
            })
            .show();
    }

    private void moveToRecycleBin(final List<MassDeleteAdapter.SearchResult> resultsToMove, boolean useSdCardBin) {
        List<File> filesToMove = getFilesFromResults(resultsToMove);
        RecycleManager.recycleFiles(this, filesToMove, useSdCardBin, new RecycleManager.RecycleCallback() {
            @Override
            public void onRecycleProgress(String currentFileName, int processed, int total) {
            }

            @Override
            public void onRecycleComplete(List<File> successfullyMovedFiles, int totalCount) {
                List<MassDeleteAdapter.SearchResult> movedResults = new ArrayList<>();
                for (MassDeleteAdapter.SearchResult result : resultsToMove) {
                    File f = getFileFromResult(result);
                    if (f != null && (successfullyMovedFiles.contains(f) || !f.exists())) {
                        movedResults.add(result);
                    }
                }
                if (!movedResults.isEmpty()) {
                    masterList.removeAll(movedResults);
                    rebuildDisplayList();
                }
            }
        });
    }

    private void hideFiles(List<MassDeleteAdapter.SearchResult> resultsToHide) {
        ArrayList<File> filesToHide = new ArrayList<>();
        for (MassDeleteAdapter.SearchResult result : resultsToHide) {
            File file = getFileFromResult(result);
            if (file != null) {
                filesToHide.add(file);
            }
        }

        if (filesToHide.isEmpty()) {
            Toast.makeText(this, "Could not resolve file paths to hide.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, FileHiderActivity.class);
        intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) filesToHide);
        startActivity(intent);
    }

    private void performDelete(final List<MassDeleteAdapter.SearchResult> toDelete, int batchSize) {
        ArrayList<String> filePathsToDelete = new ArrayList<>();

        for (MassDeleteAdapter.SearchResult item : toDelete) {
            File file = getFileFromResult(item);
            if (file != null) {
                filePathsToDelete.add(file.getAbsolutePath());
            }
        }

        if (filePathsToDelete.isEmpty()) {
            Toast.makeText(this, "Could not resolve file paths for deletion.", Toast.LENGTH_SHORT).show();
            return;
        }

        FileBridge.mFilesToDelete = filePathsToDelete;
        startDeleteService(batchSize);
    }

    private void startDeleteService(int batchSize) {
        deletionProgressLayout.setVisibility(View.VISIBLE);
        deletionProgressBar.setIndeterminate(true);
        deletionProgressText.setText("Starting deletion...");

        Intent intent = new Intent(this, DeleteService.class);
        intent.putExtra("batch_size", batchSize);
        ContextCompat.startForegroundService(this, intent);
    }

    private File getFileFromResult(MassDeleteAdapter.SearchResult result) {
        if ("file".equals(result.getUri().getScheme())) {
            return new File(result.getUri().getPath());
        }
        if (result.getPath() != null) {
            return new File(result.getPath());
        }

        String path = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(result.getUri(), new String[]{MediaStore.Files.FileColumns.DATA}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving file from result URI", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (path != null) {
            return new File(path);
        }

        return null;
    }

    private void promptForSdCardPermission() {
        new AlertDialog.Builder(this)
            .setTitle("SD Card Permission Needed")
            .setMessage("To delete files on your external SD card, you must grant this app access. Please tap 'Grant', then select the root of your SD card and tap 'Allow'.")
            .setPositiveButton("Grant", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    StorageUtils.requestSdCardPermission(MassDeleteActivity.this);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1001) {
            if (resultCode == Activity.RESULT_OK && mPendingFilePathsToDelete != null) {
                FileBridge.mFilesToDelete = mPendingFilePathsToDelete;
                startDeleteService(mPendingBatchSize);
            } else {
                Toast.makeText(this, "Deletion permission denied or cancelled.", Toast.LENGTH_SHORT).show();
            }
            mPendingFilePathsToDelete = null;
            return;
        }

        if (requestCode == StorageUtils.REQUEST_CODE_SDCARD_PERMISSION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    StorageUtils.saveSdCardUri(this, treeUri);

                    if (mPendingOperation != null) {
                        mPendingOperation.run();
                    } else if (mResultsPendingPermission != null && !mResultsPendingPermission.isEmpty()) {
                        confirmAndDelete(mResultsPendingPermission);
                    } else {
                        Toast.makeText(this, "SD card access granted. Please try the operation again.", Toast.LENGTH_LONG).show();
                    }
                }
            } else {
                Toast.makeText(this, "SD card permission was not granted.", Toast.LENGTH_SHORT).show();
            }
            mResultsPendingPermission = null;
            mPendingOperation = null;
        }
    }

    private void showFilterMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.filter_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.filter_all) currentFilterType = "all";
                else if (itemId == R.id.filter_images) currentFilterType = "images";
                else if (itemId == R.id.filter_videos) currentFilterType = "videos";
                else if (itemId == R.id.filter_documents) currentFilterType = "documents";
                else if (itemId == R.id.filter_archives) currentFilterType = "archives";
                else if (itemId == R.id.filter_other) currentFilterType = "other";
                executeQuery(searchInput.getText().toString());
                return true;
            }
        });
        popup.show();
    }

    @Override
    public void onItemClick(MassDeleteAdapter.SearchResult item) {
        item.setExcluded(!item.isExcluded());
        updateHeaderStateForItem(item);
        int index = displayList.indexOf(item);
        if (index != -1) {
            adapter.notifyItemChanged(index);
        }
    }

    @Override
    public void onItemLongClick(final MassDeleteAdapter.SearchResult item) {
        final CharSequence[] options = {"Open", "Details", "Compress"};
        new AlertDialog.Builder(this)
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        openFileViewer(item);
                    } else if (which == 1) {
                        List<File> files = getFilesFromResults(Collections.singletonList(item));
                        showDetailsDialog(files);
                    } else if (which == 2) {
                        List<File> files = getFilesFromResults(Collections.singletonList(item));
                        if (!files.isEmpty() && files.get(0).getParentFile() != null) {
                            ArchiveUtils.startCompression(MassDeleteActivity.this, files, files.get(0).getParentFile());
                            Toast.makeText(MassDeleteActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            })
            .show();
    }

    @Override
    public void onHeaderCheckedChanged(DateHeader header, boolean isChecked) {
        header.setChecked(isChecked);

        int masterIndex = masterList.indexOf(header);
        if (masterIndex == -1) return;

        for (int i = masterIndex + 1; i < masterList.size(); i++) {
            Object currentItem = masterList.get(i);
            if (currentItem instanceof MassDeleteAdapter.SearchResult) {
                ((MassDeleteAdapter.SearchResult) currentItem).setExcluded(!isChecked);
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }

        adapter.notifyDataSetChanged();
    }

    @Override
    public void onHeaderClick(DateHeader header) {
        header.setExpanded(!header.isExpanded());
        rebuildDisplayList();
    }

    private void updateHeaderStateForItem(MassDeleteAdapter.SearchResult item) {
        int itemIndex = masterList.indexOf(item);
        if (itemIndex == -1) return;

        DateHeader parentHeader = null;
        for (int i = itemIndex - 1; i >= 0; i--) {
            if (masterList.get(i) instanceof DateHeader) {
                parentHeader = (DateHeader) masterList.get(i);
                break;
            }
        }
        if (parentHeader == null) return;

        boolean allIncluded = true;
        int headerIndex = masterList.indexOf(parentHeader);
        for (int i = headerIndex + 1; i < masterList.size(); i++) {
            Object currentItem = masterList.get(i);
            if (currentItem instanceof MassDeleteAdapter.SearchResult) {
                if (((MassDeleteAdapter.SearchResult) currentItem).isExcluded()) {
                    allIncluded = false;
                    break;
                }
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        parentHeader.setChecked(allIncluded);

        int displayIndex = displayList.indexOf(parentHeader);
        if (displayIndex != -1) {
            adapter.notifyItemChanged(displayIndex);
        }
    }

    @Override
    public void onSelectChange(int start, int end, boolean isSelected) {
        int min = Math.min(start, end);
        int max = Math.max(start, end);
        if (min < 0 || max >= displayList.size()) return;

        for (int i = min; i <= max; i++) {
            Object obj = displayList.get(i);
            if (obj instanceof MassDeleteAdapter.SearchResult) {
                MassDeleteAdapter.SearchResult searchResult = (MassDeleteAdapter.SearchResult) obj;
                searchResult.setExcluded(!isSelected);
                updateHeaderStateForItem(searchResult);
            }
        }
        adapter.notifyItemRangeChanged(min, max - min + 1);
    }

    private void openFileViewer(final MassDeleteAdapter.SearchResult item) {
        File checkFile = getFileFromResult(item);
        if (checkFile == null || !checkFile.exists()) {
            Toast.makeText(this, "File no longer exists.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Execute on Parallel Thread Pool to prevent global AsyncTask queue starvation
        new AsyncTask<Void, Void, Intent>() {
            @Override
            protected Intent doInBackground(Void... voids) {
                File file = checkFile;
                String path = file.getAbsolutePath();
                String name = file.getName();
                int category = getFileCategory(name);
                Intent intent = null;

                if (category == CATEGORY_IMAGES || category == CATEGORY_VIDEOS || category == CATEGORY_AUDIO) {
                    ArrayList<String> fileList = getSiblingFilesForViewer(file, category);
                    int currentIndex = fileList.indexOf(path);
                    if (currentIndex == -1) {
                        return null;
                    }

                    if (category == CATEGORY_IMAGES) {
                        intent = new Intent(MassDeleteActivity.this, ImageViewerActivity.class);
                        intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_VIDEOS) {
                        intent = new Intent(MassDeleteActivity.this, VideoViewerActivity.class);
                        intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_AUDIO) {
                        intent = new Intent(MassDeleteActivity.this, AudioPlayerActivity.class);
                        intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                } else {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        intent = new Intent(MassDeleteActivity.this, PdfViewerActivity.class);
                    } else {
                        intent = new Intent(MassDeleteActivity.this, TextViewerActivity.class);
                    }
                    intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
                }
                return intent;
            }

            @Override
            protected void onPostExecute(Intent intent) {
                if (intent != null) {
                    startActivity(intent);
                } else {
                    Toast.makeText(MassDeleteActivity.this, "Error opening file.", Toast.LENGTH_SHORT).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private ArrayList<String> getSiblingFilesForViewer(File currentFile, final int category) {
        ArrayList<String> siblingFiles = new ArrayList<>();
        File parentDir = currentFile.getParentFile();
        if (parentDir == null || !parentDir.isDirectory()) {
            siblingFiles.add(currentFile.getAbsolutePath());
            return siblingFiles;
        }
        for (Object item : masterList) {
            if (item instanceof MassDeleteAdapter.SearchResult) {
                MassDeleteAdapter.SearchResult result = (MassDeleteAdapter.SearchResult) item;
                File file = getFileFromResult(result);
                if (file != null && file.getParentFile() != null && file.getParentFile().equals(parentDir)) {
                    if (getFileCategory(file.getName()) == category) {
                        siblingFiles.add(file.getAbsolutePath());
                    }
                }
            }
        }
        Collections.sort(siblingFiles);
        return siblingFiles;
    }

    private int getFileCategory(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase(Locale.ROOT);
        }

        List<String> imageExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
        List<String> videoExtensions = Arrays.asList("mp4", "3gp", "mkv", "webm", "avi");
        List<String> audioExtensions = Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac");
        List<String> docExtensions = Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "json", "xml", "html", "js", "css", "java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go", "swift", "sh", "bat", "ps1", "ini", "cfg", "conf", "md", "prop", "gradle", "pro", "sql");

        if (imageExtensions.contains(extension)) return CATEGORY_IMAGES;
        if (videoExtensions.contains(extension)) return CATEGORY_VIDEOS;
        if (audioExtensions.contains(extension)) return CATEGORY_AUDIO;
        if (docExtensions.contains(extension)) return CATEGORY_DOCS;
        return CATEGORY_OTHER;
    }

    private static class QueryParameters {
        String folderPath;
        int startRange = -1;
        int endRange = -1;
    }

    private class PinchZoomListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            int previousSpanCount = currentSpanCount;
            if (scaleFactor > 1.05f) currentSpanCount = Math.max(MIN_SPAN_COUNT, currentSpanCount - 1);
            else if (scaleFactor < 0.95f) currentSpanCount = Math.min(MAX_SPAN_COUNT, currentSpanCount + 1);

            if (previousSpanCount != currentSpanCount) {
                gridLayoutManager.setSpanCount(currentSpanCount);
                adapter.notifyDataSetChanged();
            }
            return true;
        }
    }

    private void setupBroadcastReceivers() {
        deleteCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int deletedCount = intent.getIntExtra(DeleteService.EXTRA_DELETED_COUNT, 0);
                Toast.makeText(MassDeleteActivity.this, "Deletion complete. " + deletedCount + " files removed.", Toast.LENGTH_LONG).show();

                deletionProgressLayout.setVisibility(View.GONE);

                List<Object> toRemove = new ArrayList<>();
                for (Object item : masterList) {
                    if (item instanceof MassDeleteAdapter.SearchResult && !((MassDeleteAdapter.SearchResult) item).isExcluded()) {
                        toRemove.add(item);
                    }
                }

                if (!toRemove.isEmpty()) {
                    masterList.removeAll(toRemove);
                    rebuildDisplayList();
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(deleteCompletionReceiver, new IntentFilter(DeleteService.ACTION_DELETE_COMPLETE));

        compressionBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean success = intent.getBooleanExtra(CompressionService.EXTRA_SUCCESS, false);
                if (success) {
                    executeQuery(searchInput.getText().toString());
                }
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

    private void showFileOperationsDialog() {
        final List<MassDeleteAdapter.SearchResult> selectedResults = new ArrayList<>();
        for (Object item : masterList) {
            if (item instanceof MassDeleteAdapter.SearchResult) {
                MassDeleteAdapter.SearchResult result = (MassDeleteAdapter.SearchResult) item;
                if (!result.isExcluded()) {
                    selectedResults.add(result);
                }
            }
        }

        if (selectedResults.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<File> selectedFiles = getFilesFromResults(selectedResults);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_file_operations, null);
        builder.setView(dialogView);
        final AlertDialog dialog = builder.create();

        Button detailsButton = dialogView.findViewById(R.id.button_details);
        Button sendToDropZoneButton = dialogView.findViewById(R.id.button_send_to_drop_zone);
        Button compressButton = dialogView.findViewById(R.id.button_compress);
        Button copyButton = dialogView.findViewById(R.id.button_copy);
        Button moveButton = dialogView.findViewById(R.id.button_move);
        Button hideButton = dialogView.findViewById(R.id.button_hide);
        Button deleteButton = dialogView.findViewById(R.id.button_delete_permanently);
        Button recycleButton = dialogView.findViewById(R.id.button_move_to_recycle);

        copyButton.setVisibility(View.GONE);
        moveButton.setVisibility(View.GONE);

        detailsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetailsDialog(selectedFiles);
                dialog.dismiss();
            }
        });

        sendToDropZoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSendToDropDialog(selectedFiles);
                dialog.dismiss();
            }
        });

        compressButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!selectedFiles.isEmpty() && selectedFiles.get(0).getParentFile() != null) {
                    ArchiveUtils.startCompression(MassDeleteActivity.this, selectedFiles, selectedFiles.get(0).getParentFile());
                    Toast.makeText(MassDeleteActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MassDeleteActivity.this, "Cannot determine destination for archive.", Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
            }
        });

        hideButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideFiles(selectedResults);
                dialog.dismiss();
            }
        });

        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                initiateDeletionProcess();
                dialog.dismiss();
            }
        });

        recycleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder binBuilder = new AlertDialog.Builder(MassDeleteActivity.this);
                binBuilder.setTitle("Choose Recycle Bin");
                binBuilder.setItems(new CharSequence[]{"Phone Recycle Bin", "SD Card Recycle Bin"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int whichBin) {
                        moveToRecycleBin(selectedResults, whichBin == 1);
                    }
                });
                binBuilder.show();
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private List<File> getFilesFromResults(List<MassDeleteAdapter.SearchResult> results) {
        List<File> files = new ArrayList<>();
        for (MassDeleteAdapter.SearchResult result : results) {
            File file = getFileFromResult(result);
            if (file != null) {
                files.add(file);
            }
        }
        return files;
    }

    private void showDetailsDialog(final List<File> files) {
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

        if (files.size() == 1) {
            File file = files.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("Name: ").append(file.getName()).append("\n");
            sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
            sb.append("Size: ").append(Formatter.formatFileSize(this, file.length())).append("\n");
            sb.append("Last Modified: ").append(new Date(file.lastModified()).toString());
            basicDetailsText.setText(sb.toString());
        } else {
            long totalSize = 0;
            for (File file : files) {
                totalSize += file.length();
            }
            basicDetailsText.setText("Items selected: " + files.size() + "\nTotal size: " + Formatter.formatFileSize(this, totalSize));
        }

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
                Toast.makeText(MassDeleteActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
            }
        });

        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private void showSendToDropDialog(final List<File> filesToSend) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
        final AutoCompleteTextView receiverUsernameInput = dialogView.findViewById(R.id.edit_text_receiver_username);

        EncryptionHelper.getInstance(this).setupAutoComplete(this, receiverUsernameInput);

        builder.setView(dialogView)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String receiverUsername = receiverUsernameInput.getText().toString().trim();
                        if (receiverUsername.isEmpty()) {
                            Toast.makeText(MassDeleteActivity.this, "Receiver username cannot be empty.", Toast.LENGTH_SHORT).show();
                        } else {
                            EncryptionHelper.getInstance(MassDeleteActivity.this).saveReceiverUsername(receiverUsername);
                            showSenderWarningDialog(receiverUsername, filesToSend);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        builder.create().show();
    }

    private void showSendToDropDialog(final File fileToSend) {
        List<File> singleFileList = new ArrayList<>();
        singleFileList.add(fileToSend);
        showSendToDropDialog(singleFileList);
    }

    private void showSenderWarningDialog(final String receiverUsername, final List<File> filesToSend) {
        showSenderWarningDialog(receiverUsername, null, filesToSend);
    }

    private void showSenderWarningDialog(final String receiverUsername, final String existingSecretNumber, final List<File> filesToSend) {
        final String secretNumber = (existingSecretNumber != null) ? existingSecretNumber : generateSecretNumber();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Important: Connection Stability")
                .setMessage("You are about to act as a temporary server for this file transfer.\n\n"
                        + "Please keep the app open and maintain a stable internet connection until the transfer is complete.\n\n"
                        + "Your Secret Number for this transfer is:\n" + secretNumber + "\n\nShare this number with the receiver.")
                .setPositiveButton("I Understand, Start Sending", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startSenderService(receiverUsername, secretNumber, filesToSend);
                    }
                })
                .setNeutralButton("Copy PIN", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            ClipData clip = ClipData.newPlainText("Secret PIN", secretNumber);
                            clipboard.setPrimaryClip(clip);
                            Toast.makeText(MassDeleteActivity.this, "Secret PIN copied to clipboard!", Toast.LENGTH_SHORT).show();
                        }
                        showSenderWarningDialog(receiverUsername, secretNumber, filesToSend);
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
                        showSenderWarningDialog(receiverUsername, secretNumber, filesToSend);
                    }
                });
        builder.create().show();
    }

    private void showSenderWarningDialog(final String receiverUsername, final File fileToSend) {
        List<File> singleFileList = new ArrayList<>();
        singleFileList.add(fileToSend);
        showSenderWarningDialog(receiverUsername, singleFileList);
    }

    private void startSenderService(String receiverUsername, String secretNumber, List<File> filesToSend) {
        if (filesToSend == null || filesToSend.isEmpty()) {
            Toast.makeText(this, "Error: No files selected to send.", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> filePaths = new ArrayList<>();
        for (File file : filesToSend) {
            if (file != null && file.exists()) {
                filePaths.add(file.getAbsolutePath());
            }
        }

        if (filePaths.isEmpty()) {
            Toast.makeText(this, "Error: Selected files do not exist on disk.", Toast.LENGTH_SHORT).show();
            return;
        }

        EncryptionHelper.getInstance(this).saveReceiverUsername(receiverUsername);

        Intent intent = new Intent(this, SenderService.class);
        intent.setAction(SenderService.ACTION_START_SEND);
        intent.putStringArrayListExtra(SenderService.EXTRA_FILE_PATHS, filePaths);
        intent.putExtra(SenderService.EXTRA_RECEIVER_USERNAME, receiverUsername);
        intent.putExtra(SenderService.EXTRA_SECRET_NUMBER, secretNumber);
        ContextCompat.startForegroundService(this, intent);
    }

    private void startSenderService(String receiverUsername, String secretNumber, File fileToSend) {
        List<File> singleFileList = new ArrayList<>();
        singleFileList.add(fileToSend);
        startSenderService(receiverUsername, secretNumber, singleFileList);
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
}