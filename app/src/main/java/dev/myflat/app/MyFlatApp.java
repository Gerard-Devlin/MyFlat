package dev.myflat.app;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.*;

public final class MyFlatApp extends Application {
  public DocumentStore store;
  public final ExecutorService io = Executors.newSingleThreadExecutor();
  private volatile int visibleActivities;

  @Override
  public void onCreate() {
    super.onCreate();
    store = new DocumentStore(this);
    store.recoverQueue();
    registerActivityLifecycleCallbacks(
        new ActivityLifecycleCallbacks() {
          public void onActivityStarted(Activity a) {
            visibleActivities++;
          }

          public void onActivityStopped(Activity a) {
            visibleActivities = Math.max(0, visibleActivities - 1);
          }

          public void onActivityCreated(Activity a, Bundle b) {}

          public void onActivityResumed(Activity a) {}

          public void onActivityPaused(Activity a) {}

          public void onActivitySaveInstanceState(Activity a, Bundle b) {}

          public void onActivityDestroyed(Activity a) {}
        });
  }

  public static MyFlatApp of(android.content.Context c) {
    return (MyFlatApp) c.getApplicationContext();
  }

  public void startProcessing() {
    if (visibleActivities > 0) startForegroundService(new Intent(this, ScanService.class));
    // Otherwise work stays in SQLite and resumes when the user returns.
  }
}
