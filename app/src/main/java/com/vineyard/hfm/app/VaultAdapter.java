package com.vineyard.hfm.app;

import android.content.Context;
import android.graphics.PorterDuff;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

public class VaultAdapter extends RecyclerView.Adapter<VaultAdapter.VaultViewHolder> {

    private final Context context;
    private final List<File> fileList;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(File file);
        void onItemLongClick(File file); // ADDED: Long-click listener for deletion
    }

    public VaultAdapter(Context context, List<File> fileList, OnItemClickListener listener) {
        this.context = context;
        this.fileList = fileList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VaultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_vault, parent, false);
        return new VaultViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VaultViewHolder holder, int position) {
        final File file = fileList.get(position);
        
        // Determine contrast color based on theme
        int contrastColor;
        String currentTheme = ThemeManager.getTheme(context);
        if (currentTheme.equals(ThemeManager.THEME_DARK) || currentTheme.equals(ThemeManager.THEME_AMOLED) || currentTheme.equals(ThemeManager.THEME_NORDIC)) {
            contrastColor = ContextCompat.getColor(context, android.R.color.white);
        } else {
            contrastColor = ContextCompat.getColor(context, R.color.lt_colorPrimary);
        }

        // DISPLAY LOGIC: Strip the security UUID prefix for the UI.
        // The prefix is the first 8 characters (UUID) plus the underscore.
        String rawName = file.getName();
        String displayName = rawName;
        if (rawName.length() > 9 && rawName.contains("_")) {
            displayName = rawName.substring(rawName.indexOf("_") + 1);
        }
        
        holder.fileName.setText(displayName);
        holder.fileSize.setText(Formatter.formatFileSize(context, file.length()));
        
        // ICON LOGIC: Set context-aware icon based on extension
        holder.fileIcon.setImageResource(getIconForFileType(displayName));
        
        // Apply theme-based tint for visibility
        holder.fileIcon.setColorFilter(contrastColor, PorterDuff.Mode.SRC_IN);

        // Tap to open/play file
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onItemClick(file);
                }
            }
        });

        // Long press to trigger options/deletion menu
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) {
                    listener.onItemLongClick(file);
                }
                return true; // Consume long-click event
            }
        });
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    private int getIconForFileType(String fileName) {
        if (fileName == null) return R.drawable.category_24px;
        String lower = fileName.toLowerCase();
        
        // Video Icons
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || 
            lower.endsWith(".webm") || lower.endsWith(".3gp")) {
            return R.drawable.video_file_24px;
        }
        
        // Image Icons
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || 
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
            return R.drawable.image_24px;
        }

        // Audio Icons
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") ||
            lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".flac")) {
            return R.drawable.audio_file_24px;
        }

        // Code / Script Icons
        if (lower.endsWith(".html") || lower.endsWith(".xml") || lower.endsWith(".js") ||
            lower.endsWith(".css") || lower.endsWith(".java") || lower.endsWith(".kt") ||
            lower.endsWith(".py") || lower.endsWith(".c") || lower.endsWith(".cpp") ||
            lower.endsWith(".php") || lower.endsWith(".json") || lower.endsWith(".gradle")) {
            return R.drawable.code_24px;
        }
        
        // Document Icons
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || 
            lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") ||
            lower.endsWith(".pptx") || lower.endsWith(".txt") || lower.endsWith(".log") ||
            lower.endsWith(".csv") || lower.endsWith(".rtf")) {
            return R.drawable.docs_24px;
        }
        
        // Default Fallback (Archives, Packages, Unknown)
        return R.drawable.category_24px;
    }

    public static class VaultViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName;
        TextView fileSize;

        public VaultViewHolder(@NonNull View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.vault_file_icon);
            fileName = itemView.findViewById(R.id.vault_file_name);
            fileSize = itemView.findViewById(R.id.vault_file_size);
        }
    }
}
