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

public class LoopMonitorService extends Service {

    public static final String ACTION_START = "com.loopcamera.loop20.START";
    public static final String ACTION_STOP = "com.loopcamera.loop20.STOP";
    private static final String CHANNEL_ID = "camera_loop_10";
    private static final int NOTIF_ID = 10;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private LoopEngine engine;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent i = new Intent(context, LoopMonitorService.class);
        i.setAction(ACTION_START);
        context.startForegroundService(i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, LoopMonitorService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new LoopEngine(this);
        createChannel();
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
        startForeground(NOTIF_ID, buildNotification("Đang theo dõi DCIM"));
        acquireWakeLock();
        startWorker();
        return START_STICKY;
    }

    private void startWorker() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        LoopLog.get().i("Bắt đầu theo dõi DCIM (đọc lại trạng thái từ thư mục đã cấp quyền).");
        engine.resetTracking();
        worker = new Thread(() -> {
            while (running.get()) {
                try {
                    engine.tick();
                } catch (Throwable t) {
                    LoopLog.get().e("Lỗi vòng theo dõi", t);
                }
                try {
                    Thread.sleep(LoopEngine.POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "loop-monitor");
        worker.start();
    }

    private void stopInternal() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        releaseWakeLock();
        LoopLog.get().i("Đã dừng theo dõi.");
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraLoop10:watch");
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
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Theo dõi USB/DCIM cho loop 10 video");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_loop)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
