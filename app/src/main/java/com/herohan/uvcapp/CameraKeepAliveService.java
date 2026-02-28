package com.herohan.uvcapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class CameraKeepAliveService extends Service {
    private static final int NOTIF_ID = 0x1234;
    private static final String CHANNEL_ID = "uvc_camera_keepalive";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("UVC Camera active")
                .setContentText("Keeping camera connection alive")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setOngoing(true)
                .build();
        // simple call is fine; manifest already declares a benign type
        startForeground(NOTIF_ID, notif);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // service should stay running until explicitly stopped
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Camera keep‑alive",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Notification shown while the UVC camera is open");
            NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (mgr != null) {
                mgr.createNotificationChannel(channel);
            }
        }
    }
}
