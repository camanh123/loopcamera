package com.loopcamera.loop20;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diagnostic foreground service so writer discovery keeps running when the
 * user switches to the OEM DVR to start a recording.
 */
public class WriterDiscoveryService extends Service {

    public static final String ACTION_START = "com.loopcamera.loop20.WRITER_DISCOVERY_START";
    public static final String ACTION_STOP = "com.loopcamera.loop20.WRITER_DISCOVERY_STOP";
    private static final String CHANNEL_ID = "mp4_writer_discovery";
    private static final int NOTIF_ID = 34;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private WriterDiscovery discovery;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent i = new Intent(context, WriterDiscoveryService.class);
        i.setAction(ACTION_START);
        context.startForegroundService(i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, WriterDiscoveryService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopInternal();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("LIVE MP4 WRITER DISCOVERY"));
        acquireWakeLock();
        startWorker();
        return START_STICKY;
    }

    private void startWorker() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        discovery = new WriterDiscovery(this);
        worker = new Thread(() -> {
            try {
                discovery.runLoop();
            } catch (Throwable t) {
                CrashLog.write(getApplicationContext(), t);
                LoopLog.get().e("Writer discovery crashed", t);
            } finally {
                running.set(false);
            }
        }, "mp4-writer-discovery");
        worker.start();
    }

    private void stopInternal() {
        running.set(false);
        if (discovery != null) {
            discovery.requestStop();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        releaseWakeLock();
    }

    @Override
    public void onDestroy() {
        stopInternal();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraLoop30:writer-discovery");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "MP4 writer discovery",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Watch USB DCIM/Camera MP4 create/growth — diagnostic only");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        createChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("LIVE MP4 WRITER DISCOVERY")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_loop)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
