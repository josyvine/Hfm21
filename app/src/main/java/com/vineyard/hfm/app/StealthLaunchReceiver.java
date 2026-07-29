package com.vineyard.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

/**
 * Intercepts dialed numbers from the system phone dialer.
 * Matches raw PIN, *#PIN#, or #PIN# and aborts the broadcast call.
 */
public class StealthLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "HFM_StealthTrigger";
    private static final String STEALTH_CHANNEL_ID = "hfm_stealth_verified_channel";
    private static final int STEALTH_NOTIF_ID = 4004;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action != null && action.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
            String dialedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (dialedNumber == null) return;

            SharedPreferences prefs = context.getSharedPreferences("hfm_stealth_prefs", Context.MODE_PRIVATE);
            String savedPin = prefs.getString("stealth_pin", "");

            if (savedPin.isEmpty()) return;

            String cleanDialed = dialedNumber.trim();
            String cleanSaved = savedPin.trim();

            // Match PIN, *#PIN#, or #PIN#
            boolean isMatch = cleanDialed.equals(cleanSaved) ||
                              cleanDialed.equals("*#" + cleanSaved + "#") ||
                              cleanDialed.equals("#" + cleanSaved + "#");

            if (isMatch) {
                Log.i(TAG, "Stealth Code Matched for PIN: " + cleanSaved);

                // Cancel the outgoing cellular call instantly
                setResultData(null);
                abortBroadcast();

                Toast.makeText(context, "HFM: Identity Verified. Tap notification to manage visibility.", Toast.LENGTH_LONG).show();

                showStickyVerifiedNotification(context);
            }
        }
    }

    private void showStickyVerifiedNotification(Context context) {
        Intent popupIntent = new Intent(context, StealthUnlockActivity.class);
        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, popupIntent, flags);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    STEALTH_CHANNEL_ID,
                    "HFM Stealth Access",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, STEALTH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("HFM Security Portal")
                .setContentText("Tap here to HIDE or UNHIDE HFM File Manager")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        if (notificationManager != null) {
            notificationManager.notify(STEALTH_NOTIF_ID, builder.build());
        }
    }
}