package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.*;
import dev.myflat.scanner.*;
import java.util.concurrent.*;

/** Sequential, foreground CPU processing. Queue state survives process death. */
public final class ScanService extends Service {
  public static final String CHANGED = "dev.myflat.app.DOCUMENTS_CHANGED",
      CANCEL = "dev.myflat.app.CANCEL_PROCESSING";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private boolean running;
  private Future<?> task;
  private NotificationManager notifications;
  private volatile String message = "准备扫描";

  @Override
  public void onCreate() {
    super.onCreate();
    notifications = getSystemService(NotificationManager.class);
    notifications.createNotificationChannel(
        new NotificationChannel("processing", "扫描处理", NotificationManager.IMPORTANCE_LOW));
  }

  private Notification notification(String text, int progress) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent cancel =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, ScanService.class).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, "processing")
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("MyFlat · 本地扫描")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setProgress(100, progress, progress == 0)
        .addAction(new Notification.Action.Builder(null, "取消", cancel).build())
        .build();
  }

  @Override
  public synchronized int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && CANCEL.equals(intent.getAction())) {
      if (task != null) task.cancel(true);
      MyFlatApp.of(this).store.cancelPending();
      changed("处理已取消", 0);
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }
    Notification n = notification(message, 0);
    if (Build.VERSION.SDK_INT >= 35)
      startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
    else startForeground(7, n);
    if (!running) {
      running = true;
      task = worker.submit(this::drain);
    }
    return START_NOT_STICKY;
  }

  private void drain() {
    DocumentStore store = MyFlatApp.of(this).store;
    try {
      DocumentStore.Page page;
      while (!Thread.currentThread().isInterrupted() && (page = store.nextJob()) != null) {
        Bitmap source = null, result = null;
        try {
          ScanSettings options = ScanSettings.parse(page.settings);
          source = Images.decode(store.file(page.original), options.maxEdge);
          if (page.rotation != 0) {
            Bitmap rotated = ImageMath.rotate(source, page.rotation);
            if (rotated != source) source.recycle();
            source = rotated;
          }
          ScanEngine.Result scan =
              new ScanEngine(this).process(source, options.engine(), this::changed, null);
          result = scan.image;
          if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
          store.complete(page, result, options.quality);
          changed("页面已保存", 100);
        } catch (Throwable ex) {
          if (ex instanceof ThreadDeath) throw (ThreadDeath) ex;
          store.fail(
              page.id, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
          changed("处理未完成，可在页面重试", 0);
        } finally {
          if (result != null && result != source && !result.isRecycled()) result.recycle();
          if (source != null && !source.isRecycled()) source.recycle();
        }
      }
    } finally {
      synchronized (this) {
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
      }
      changed("", 100);
    }
  }

  private void changed(String stage, int percent) {
    message = stage;
    sendBroadcast(
        new Intent(CHANGED)
            .setPackage(getPackageName())
            .putExtra("stage", stage)
            .putExtra("progress", percent));
    if (!stage.isEmpty()) notifications.notify(7, notification(stage, percent));
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    if (task != null) task.cancel(true);
    MyFlatApp.of(this).store.cancelPending();
    stopSelf();
  }
}
