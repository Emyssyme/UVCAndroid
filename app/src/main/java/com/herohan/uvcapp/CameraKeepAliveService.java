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

    // keep the CPU awake even when the screen is off / device is dozing
    private android.os.PowerManager.WakeLock mWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        // acquire a partial wake lock so the camera thread continues running when
        // the display goes to sleep or the device enters a low‑power state.
        acquireWakeLock();

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

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        if (mWakeLock == null) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        getPackageName() + ":KeepAlive");
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire();
            }
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
        }
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
