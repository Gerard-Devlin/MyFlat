package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Screenshots and navigation checks for secondary panels and shared-file entry. */
@android.annotation.TargetApi(29)
final class UiPanelChecks {
  private final Instrumentation test;
  private final Context context;
  private final List<Activity> activities = new ArrayList<>();
  private int captures;
  private final Activity library;

  UiPanelChecks(Instrumentation test, Activity library) {
    this.library = library;
    this.test = test;
    context = test.getTargetContext();
  }

  void run() throws Exception {
    DocumentStore store = MyFlatApp.of(context).store;
    long folder = store.createFolder("UI panels " + UUID.randomUUID());
    String document = store.createDocument(folder, "UI panels");
    Bitmap bitmap = Bitmap.createBitmap(640, 880, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xffeadbc4);
    String page = store.addPage(document, bitmap, new ScanSettings());
    store.complete(store.page(page), bitmap, 100);
    bitmap.recycle();
    File shared = new File(context.getCacheDir(), "exports/ui-panel-test.jpg");
    shared.getParentFile().mkdirs();
    try (InputStream in = new FileInputStream(store.file(store.page(page).result));
        OutputStream out = new FileOutputStream(shared)) {
      Exporter.copy(in, out);
    }
    try {

      snap("library");
      ui(() -> ((MainActivity) library).onNewIntent(new Intent().putExtra("trash", true)));
      snap("trash");
      ui(() -> ((MainActivity) library).onNewIntent(new Intent()));
      dialog(() -> LibrarySearchDialog.show(library, 0, id -> {}), "search");
      panel(library, "createFolder", "new-folder");
      dialog(() -> FolderPicker.show(library, 0, id -> {}), "folder-picker");
      Activity settings = launch(SettingsActivity.class, new Intent());
      snap("settings");
      for (String method :
          new String[] {
            "nightMode",
            "language",
            "timer",
            "resolution",
            "twoPagesOrder",
            "storage",
            "backup",
            "advanced"
          }) panel(settings, method, "settings-" + method);
      dialog(() -> FolderSecurity.settings(settings), "security");
      dialog(() -> WelcomeActivity.policy(settings, "使用条款", WelcomeActivity.TERMS), "terms");
      dialog(() -> WelcomeActivity.policy(settings, "隐私政策", WelcomeActivity.PRIVACY), "privacy");
      Activity editor = launch(EditorActivity.class, new Intent().putExtra("page", page));
      SystemClock.sleep(600);
      snap("editor");
      panel(editor, "more", "editor-more");
      Activity export =
          launch(
              ExportActivity.class,
              new Intent()
                  .putExtra("title", "UI panels")
                  .putExtra("format", "PDF")
                  .putStringArrayListExtra("pages", new ArrayList<>(List.of(page))));
      snap("export");
      panel(export, "settings", "pdf-settings");
      panel(export, "rename", "filename");
      panel(export, "addPages", "export-pages");
      List<ActionMenus.Item> actions = new ArrayList<>();
      for (int i = 0; i < 13; i++)
        actions.add(new ActionMenus.Item("pdf", "Action " + i, () -> {}));
      ui(
          () -> {
            try {
              Method m =
                  ActionMenus.class.getDeclaredMethod(
                      "edit", Activity.class, String.class, List.class, Runnable.class);
              m.setAccessible(true);
              m.invoke(null, library, "ui_audit", actions, (Runnable) () -> {});
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      snap("action-settings");
      ui(
          () -> {
            View done = find(top(), "完成");
            Rect area = new Rect();
            check(
                done != null
                    && done.getGlobalVisibleRect(area)
                    && area.height() >= Ui.dp(context, 40),
                "Menu settings keeps Done visible");
            ScrollView scroll = scroll(top());
            check(scroll != null, "Menu settings scrolls all action rows");
            scroll.fullScroll(View.FOCUS_DOWN);
          });
      test.waitForIdleSync();
      snap("action-settings-bottom");
      back();
      Uri uri = ShareProvider.uri(context, shared);
      Intent send =
          new Intent(Intent.ACTION_SEND)
              .setType("image/jpeg")
              .putExtra(Intent.EXTRA_STREAM, uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      send.setClipData(ClipData.newRawUri("Photo", uri));
      check(
          SharedImports.collect(context, send).size() == 1,
          "Shared stream and ClipData are deduplicated");
      check(
          SharedImports.collect(
                  context,
                  new Intent(Intent.ACTION_SEND)
                      .setType("image/jpeg")
                      .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private.jpg")))
              .isEmpty(),
          "External file paths are rejected");
      send.setPackage(context.getPackageName());
      check(
          !context.getPackageManager().queryIntentActivities(send, 0).isEmpty(),
          "Image sharing resolves to MyFlat");
      Intent pdf =
          new Intent(Intent.ACTION_SEND_MULTIPLE)
              .setType("application/pdf")
              .setPackage(context.getPackageName());
      check(
          !context.getPackageManager().queryIntentActivities(pdf, 0).isEmpty(),
          "Multiple-PDF sharing resolves to MyFlat");
      Intent entry = new Intent();
      SharedImports.forward(entry, List.of(uri));
      Instrumentation.ActivityMonitor imported =
          test.addMonitor(ImportActivity.class.getName(), null, false);
      ui(() -> library.startActivity(entry.setClass(context, MainActivity.class)));
      Activity importing = test.waitForMonitorWithTimeout(imported, 10000);
      test.removeMonitor(imported);
      check(importing != null, "Sharing reaches the import activity");
      activities.add(importing);
      SystemClock.sleep(500);
      snap("shared-import");
      check(find(top(), "要应用的效果") != null, "Shared images open the existing import preview");
      back();
      check(
          AppAppearance.text(context, "灰度").equals("灰度")
              && AppAppearance.text(context, "重置").equals("重置"),
          "Simplified Chinese labels keep their source meaning");
      android.content.SharedPreferences ap = context.getSharedPreferences("appearance", 0);
      int oldMode = ap.getInt("night_mode", 1);
      ap.edit().putInt("night_mode", 2).commit();
      try {
        ui(
            () -> {
              TextView label = Ui.text(context, "排序", 14, 0xff212121);
              AppAppearance.apply(label);
              check(
                  Color.red(label.getCurrentTextColor()) > 180,
                  "Dark mode keeps secondary controls readable");
            });
      } finally {
        ap.edit().putInt("night_mode", oldMode).commit();
      }
      check(captures >= 22, "Secondary UI panels captured: " + captures);
    } finally {
      for (Activity a : activities) if (!a.isFinishing()) ui(a::finish);
      store.permanentlyDelete(document);
      store.deleteFolder(folder);
      shared.delete();
    }
  }

  private Activity launch(Class<? extends Activity> type, Intent intent) {
    Activity a =
        test.startActivitySync(
            intent.setClass(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    activities.add(a);
    test.waitForIdleSync();
    return a;
  }

  private void panel(Activity owner, String method, String name) throws Exception {
    dialog(
        () -> {
          try {
            Method m = owner.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(owner);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        },
        name);
  }

  private void dialog(Runnable open, String name) throws Exception {
    ui(open);
    snap(name);
    back();
  }

  private void back() {
    test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    test.waitForIdleSync();
  }

  private void ui(Runnable work) {
    java.util.concurrent.atomic.AtomicReference<Throwable> error =
        new java.util.concurrent.atomic.AtomicReference<>();
    test.runOnMainSync(
        () -> {
          try {
            work.run();
          } catch (Throwable e) {
            error.set(e);
          }
        });
    if (error.get() != null) throw new AssertionError(error.get());
    test.waitForIdleSync();
  }

  private View top() {
    List<View> roots = WindowInspector.getGlobalWindowViews();
    for (int i = roots.size() - 1; i >= 0; i--) if (roots.get(i).isShown()) return roots.get(i);
    throw new AssertionError("No visible window");
  }

  private void snap(String name) throws Exception {
    test.waitForIdleSync();
    SystemClock.sleep(180);
    ui(
        () -> {
          View v = top();
          check(v.getWidth() > 0 && v.getHeight() > 0, "Panel opens: " + name);
          Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
          v.draw(new Canvas(b));
          try (OutputStream out =
              new FileOutputStream(
                  new File(context.getExternalFilesDir(null), "audit-" + name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
          } catch (IOException e) {
            throw new AssertionError(e);
          } finally {
            b.recycle();
          }
        });
    captures++;
  }

  private static View find(View root, String text) {
    if (text.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())
        || root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View v = find(((ViewGroup) root).getChildAt(i), text);
        if (v != null) return v;
      }
    return null;
  }

  private static ScrollView scroll(View root) {
    if (root instanceof ScrollView) return (ScrollView) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        ScrollView v = scroll(((ViewGroup) root).getChildAt(i));
        if (v != null) return v;
      }
    return null;
  }

  private void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    Bundle b = new Bundle();
    b.putString("stream", "PASS " + message + "\n");
    test.sendStatus(0, b);
  }
}
