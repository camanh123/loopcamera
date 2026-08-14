package com.loopcamera.loop20;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicBoolean;

public class LoopMonitorService extends Service {

    public static final String ACTION_START = "com.loopcamera.loop20.START";
    public static final String ACTION_STOP = "com.loopcamera.loop20.STOP";
    private static final String CHANNEL_ID = "camera_loop_30";
    private static final int NOTIF_ID = 30;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private LoopEngine engine;
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver usbReceiver;

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
        registerUsbReceiver();
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
        startForeground(NOTIF_ID, buildNotification("Waiting for USB"));
        acquireWakeLock();
        startWorker();
        return START_STICKY;
    }

    private void startWorker() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        LoopLog.get().i("Service: BOOT/start → chờ USB nếu cần, rồi monitor DCIM/Camera.");
        engine.resetTracking();
        worker = new Thread(() -> {
            while (running.get()) {
                try {
                    engine.tick();
                    LoopUiState s = LoopStateBus.get().latest();
                    String text = s.phase + (s.folderLabel != null && s.connected ? " · " + s.folderLabel : "");
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) {
                        nm.notify(NOTIF_ID, buildNotification(text));
                    }
                } catch (Throwable t) {
                    LoopLog.get().e("Lỗi vòng theo dõi (USB có thể chưa sẵn sàng)", t);
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

    private void registerUsbReceiver() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent != null ? intent.getAction() : "";
                LoopLog.get().i("USB/media event: " + a);
                engine.onUsbChanged();
            }
        };
        IntentFilter media = new IntentFilter();
        media.addAction(Intent.ACTION_MEDIA_MOUNTED);
        media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        media.addAction(Intent.ACTION_MEDIA_REMOVED);
        media.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        media.addAction(Intent.ACTION_MEDIA_EJECT);
        media.addDataScheme("file");
        registerReceiver(usbReceiver, media);

        IntentFilter usb = new IntentFilter();
        usb.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, usb);
    }

    private void stopInternal() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (usbReceiver != null) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Exception ignored) {
            }
            usbReceiver = null;
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraLoop30:watch");
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
        ch.setDescription("Theo dõi USB/DCIM/Camera — loop 30 video");
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
