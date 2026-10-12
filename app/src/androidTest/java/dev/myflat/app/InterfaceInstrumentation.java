package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inspector.WindowInspector;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Device checks for menus, full-resolution editing, import and portable backups. */
@android.annotation.TargetApi(29)
public final class InterfaceInstrumentation extends Instrumentation {
  private Context context;
  private DocumentStore store;
  private final List<Activity> activities = new ArrayList<>();
  private final JSONArray checks = new JSONArray();
  private String document;
  private long folder;
  private File photo;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int status = Activity.RESULT_OK;
    try {
      context = getTargetContext();
      launch(MainActivity.class, new Intent());
      store = MyFlatApp.of(context).store;
      for (DocumentStore.Document old : store.documents(0, "Interface test ", false))
        if (old.title.matches("Interface test [0-9a-f-]{36}( restored)?"))
          store.permanentlyDelete(old.id);
      for (DocumentStore.Folder old : store.folders())
        if (old.name.matches("Interface test [0-9a-f-]{36}( restored)?"))
          store.deleteFolder(old.id);
      String unique = "Interface test " + UUID.randomUUID();
      folder = store.createFolder(unique);
      document = store.createDocument(folder, unique);
      Bitmap fixture;
      try (InputStream in = getContext().getAssets().open("acceptance.jpg")) {
        fixture = BitmapFactory.decodeStream(in);
      }
      Bitmap full = Bitmap.createScaledBitmap(fixture, 3200, 4268, true);
      fixture.recycle();
      photo = new File(context.getCacheDir(), "interface-photo.jpg");
      DocumentStore.saveJpeg(photo, full, 100);
      String pageId = store.addPage(document, full, new ScanSettings());
      store.complete(store.page(pageId), full, 100);
      full.recycle();
      testMenus();
      testGuide();
      testEditor(pageId);
      testImport();
      testExport(pageId);
      testSettings();
      testCameraCapture();
      testBackup(pageId, unique);
      testPageManagement(pageId);
      result.putString(
          "stream", new JSONObject().put("status", "passed").put("checks", checks).toString(2));

    } catch (Throwable error) {
      result.putString("stream", android.util.Log.getStackTraceString(error));
      status = Activity.RESULT_CANCELED;
    } finally {
      runOnMainSync(
          () -> {
            for (Activity a : activities) if (!a.isFinishing()) a.finish();
          });
      if (document != null && store.document(document) != null)
        try {
          store.permanentlyDelete(document);
        } catch (Exception ignored) {
        }
      if (folder > 1) store.deleteFolder(folder);
      if (photo != null) photo.delete();
    }
    finish(status, result);
  }

  private Activity launch(Class<? extends Activity> type, Intent intent) {
    Activity a =
        startActivitySync(intent.setClass(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    activities.add(a);
    waitForIdleSync();
    return a;
  }

  private void testMenus() throws Exception {
    Activity main = activities.get(0);
    SharedPreferences prefs = context.getSharedPreferences("menus", 0);
    String previous = prefs.getString("library", null);
    try {
      prefs.edit().remove("library").commit();
      runOnMainSync(() -> click(main.getWindow().getDecorView(), "更多"));
      waitForIdleSync();
      SystemClock.sleep(250);
      capture("menu-probe", top());
      require(
          find(top(), "导入") != null && find(top(), "创建 ZIP") != null && find(top(), "创建文本") == null,
          "More sheet contains the five enabled library actions");
      capture("library-more", top());
      runOnMainSync(() -> click(top(), "编辑菜单"));
      waitForIdleSync();
      capture("menu-edit", top());
      require(find(top(), "动作设置") != null, "Menu customization opens as a full screen");
      runOnMainSync(
          () -> {
            click(top(), "改名");
            click(top(), "完成");
          });
      waitForIdleSync();
      require(
          !prefs.getString("library", "").contains("改名"),
          "Toolbar customization persists hidden actions");
    } finally {
      if (previous == null) prefs.edit().remove("library").commit();
      else prefs.edit().putString("library", previous).commit();
    }
  }

  private void testGuide() throws Exception {
    Activity owner = activities.get(0);
    ScanGuideDialog[] guide = new ScanGuideDialog[1];
    runOnMainSync(
        () -> {
          guide[0] = new ScanGuideDialog(owner, 0, () -> {});
          guide[0].show();
        });
    waitForIdleSync();
    for (int i = 0; i < 5; i++) {
      SystemClock.sleep(250);
      capture("guide-" + i, guide[0].getWindow().getDecorView());
      runOnMainSync(
          () -> click(guide[0].getWindow().getDecorView(), guide[0].step() == 4 ? "关闭" : "下一步"));
      waitForIdleSync();
    }
    require(
        !guide[0].isShowing(), "All five guide pages complete with the reference illustrations");
  }

  private void testEditor(String id) throws Exception {
    EditorActivity editor =
        (EditorActivity) launch(EditorActivity.class, new Intent().putExtra("page", id));
    waitUntil(() -> field(editor, "canvas") != null, 10000);
    PageCanvas view = (PageCanvas) field(editor, "canvas");
    runOnMainSync(
        () -> {
          try {
            Field scale = PageCanvas.class.getDeclaredField("scale");
            scale.setAccessible(true);
            scale.setFloat(view, 3);
            view.invalidate();
          } catch (Exception ex) {
            throw new AssertionError(ex);
          }
        });
    waitUntil(() -> field(view, "detail") != null, 15000);
    Bitmap detail = (Bitmap) field(view, "detail");
    require(detail.getWidth() > 500, "Zoom reads visible detail from the full-resolution JPEG");
    capture("editor-zoom", editor.getWindow().getDecorView());
    runOnMainSync(() -> click(editor.getWindow().getDecorView(), "裁剪"));
    waitForIdleSync();
    capture("crop", editor.getWindow().getDecorView());
    runOnMainSync(() -> click(editor.getWindow().getDecorView(), "比例：自定义  ⌄"));
    waitForIdleSync();
    capture("crop-ratios", editor.getWindow().getDecorView());
    runOnMainSync(
        () -> {
          click(editor.getWindow().getDecorView(), "A4比例");
          click(editor.getWindow().getDecorView(), "完成");
        });
    waitUntil(
        () ->
            store.page(id).height > 2400
                && Math.abs((float) store.page(id).width / store.page(id).height - 210f / 297)
                    < .003f,
        30000);
    require(
        store.page(id).height > 2400,
        "Crop saves at source resolution instead of preview resolution");
    SystemClock.sleep(400);
    waitForIdleSync();
    runOnMainSync(() -> click(editor.getWindow().getDecorView(), "更多"));
    waitForIdleSync();
    SystemClock.sleep(250);
    runOnMainSync(() -> click(top(), "擦除"));
    waitForIdleSync();
    PageCanvas erase = (PageCanvas) field(editor, "canvas");
    int width = store.page(id).width, height = store.page(id).height;
    String before = hash(store.file(store.page(id).result));
    runOnMainSync(
        () -> {
          long now = SystemClock.uptimeMillis();
          MotionEvent down =
              MotionEvent.obtain(
                  now,
                  now,
                  MotionEvent.ACTION_DOWN,
                  erase.getWidth() / 2f,
                  erase.getHeight() / 2f,
                  0);
          erase.onTouchEvent(down);
          down.recycle();
          click(editor.getWindow().getDecorView(), "完成");
        });
    waitUntil(() -> !hash(store.file(store.page(id).result)).equals(before), 30000);
    require(
        store.page(id).width == width && store.page(id).height == height,
        "Brush editing preserves the full-resolution dimensions");
    require(
        hash(photo).equals(hash(store.file(store.page(id).original))),
        "Crop and brush editing preserve the untouched original");
    runOnMainSync(editor::finish);
    waitForIdleSync();
  }

  private void testImport() throws Exception {
    ArrayList<String> uris = new ArrayList<>();
    uris.add(Uri.fromFile(photo).toString());
    uris.add(Uri.fromFile(photo).toString());
    Activity importer =
        launch(ImportActivity.class, new Intent().putStringArrayListExtra("uris", uris));
    SystemClock.sleep(500);
    waitForIdleSync();
    capture("import", importer.getWindow().getDecorView());
    require(
        find(importer.getWindow().getDecorView(), "自动裁剪") != null,
        "Import preview includes processing choices");
    runOnMainSync(() -> click(importer.getWindow().getDecorView(), "  排序"));
    waitForIdleSync();
    capture("import-order", top());
    require(find(top(), "第 1 页，长按移动") != null, "Import ordering uses draggable page thumbnails");
    runOnMainSync(() -> click(top(), "完成"));
    runOnMainSync(importer::finish);
    waitForIdleSync();
  }

  private void testExport(String id) throws Exception {
    ArrayList<String> ids = new ArrayList<>();
    ids.add(id);
    Activity export =
        launch(
            ExportActivity.class,
            new Intent()
                .putStringArrayListExtra("pages", ids)
                .putExtra("title", "Documents")
                .putExtra("format", "PDF"));
    waitForIdleSync();
    capture("pdf-prepare", export.getWindow().getDecorView());
    runOnMainSync(() -> click(export.getWindow().getDecorView(), "色彩模式  "));
    waitForIdleSync();
    capture("pdf-settings", top());
    require(find(top(), "最高") != null, "PDF defaults to the highest image quality");
    runOnMainSync(() -> click(top(), "关闭"));
    runOnMainSync(export::finish);
    waitForIdleSync();
  }

  private void testSettings() throws Exception {
    Activity settings = launch(SettingsActivity.class, new Intent());
    capture("settings", settings.getWindow().getDecorView());
    require(
        find(settings.getWindow().getDecorView(), "自定义定时器") != null,
        "Camera preferences are available in the settings hierarchy");
    runOnMainSync(settings::finish);
    waitForIdleSync();
  }

  private void testCameraCapture() throws Exception {
    SharedPreferences settings = context.getSharedPreferences("settings", 0),
        cameraPrefs = context.getSharedPreferences("CameraActivity", 0);
    String previous = settings.getString("scan", null);
    boolean seen = cameraPrefs.getBoolean("scan_guide_seen", false);
    ScanSettings raw = new ScanSettings();
    raw.dewarp = raw.enhance = raw.fingers = false;
    raw.save(context);
    cameraPrefs.edit().putBoolean("scan_guide_seen", true).commit();
    CameraActivity camera =
        (CameraActivity) launch(CameraActivity.class, new Intent().putExtra("folder", folder));
    String captured = null;
    try {
      waitUntil(() -> field(camera, "session") != null, 15000);
      android.media.ImageReader reader = (android.media.ImageReader) field(camera, "reader");
      long expected = (long) reader.getWidth() * reader.getHeight();
      runOnMainSync(
          () -> {
            try {
              java.lang.reflect.Method shoot =
                  CameraActivity.class.getDeclaredMethod("shootWithTimer");
              shoot.setAccessible(true);
              shoot.invoke(camera);
            } catch (Exception ex) {
              throw new AssertionError(ex);
            }
          });
      waitUntil(
          () ->
              field(camera, "documentId") != null
                  && !store.pages((String) field(camera, "documentId")).isEmpty(),
          30000);
      captured = (String) field(camera, "documentId");
      DocumentStore.Page page = store.pages(captured).get(0);
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(store.file(page.original).getPath(), bounds);
      require(
          (long) bounds.outWidth * bounds.outHeight == expected,
          "Actual camera capture retains every pixel in the selected JPEG output");
      capture("camera", camera.getWindow().getDecorView());
      String completed = captured;
      waitUntil(() -> "ready".equals(store.pages(completed).get(0).status), 30000);
    } finally {
      runOnMainSync(camera::finish);
      waitForIdleSync();
      if (captured != null) store.permanentlyDelete(captured);
      if (previous == null) settings.edit().remove("scan").commit();
      else settings.edit().putString("scan", previous).commit();
      cameraPrefs.edit().putBoolean("scan_guide_seen", seen).commit();
    }
  }

  private void testPageManagement(String id) throws Exception {
    Bitmap tile = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
    tile.eraseColor(Color.WHITE);
    ScanSettings raw = new ScanSettings();
    raw.dewarp = raw.enhance = raw.fingers = false;
    String second = store.addPage(document, tile, raw);
    store.complete(store.page(second), tile, 100);
    String third = store.addPage(document, tile, raw);
    store.complete(store.page(third), tile, 100);
    tile.recycle();
    long target = store.createFolder("Interface move " + UUID.randomUUID());
    try {
      store.movePages(Collections.singletonList(second), target);
      require(
          store.pages(document).size() == 2
              && store.page(second).document.equals(store.documents(target, "", false).get(0).id),
          "Move transfers only the selected pages");
      store.setPageOrder(Arrays.asList(third, id), folder, document);
      require(
          store.pages(document).get(0).id.equals(third)
              && store.pages(document).get(1).id.equals(id),
          "Page ordering persists in the document store");
    } finally {
      for (DocumentStore.Document moved : store.documents(target, "", false))
        store.permanentlyDelete(moved.id);
      store.deleteFolder(target);
    }
    Bitmap odd = Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888);
    int[] colors = new int[15];
    for (int i = 0; i < 15; i++) colors[i] = Color.rgb(i, 0, 0);
    odd.setPixels(colors, 0, 5, 0, 0, 5, 3);
    dev.myflat.scanner.ImageMath.rotate180InPlace(odd);
    require(
        odd.getPixel(0, 0) == colors[14]
            && odd.getPixel(4, 2) == colors[0]
            && odd.getPixel(2, 1) == colors[7],
        "In-place page orientation preserves odd-sized image pixels");
    odd.recycle();
    ScanSettings[] pair = Importer.spreadSettings(raw, true, 90, .5f, 180);
    require(
        pair[0].sourceTop == 0 && pair[1].sourceTop > 0 && pair[0].outputRotation == 180,
        "Reading orientation keeps logical left and right page order");
  }

  private void testBackup(String id, String title) throws Exception {
    File archive = new File(context.getCacheDir(), "interface-backup.zip");
    try (OutputStream out = new FileOutputStream(archive)) {
      Backup.write(context, out);
    }
    JSONObject manifest;
    byte[] original, result;
    try (ZipFile zip = new ZipFile(archive)) {
      try (InputStream in = zip.getInputStream(zip.getEntry("manifest.json"))) {
        manifest = new JSONObject(new String(read(in), java.nio.charset.StandardCharsets.UTF_8));
      }
      DocumentStore.Page p = store.page(id);
      try (InputStream in = zip.getInputStream(zip.getEntry("pages/" + p.original))) {
        original = read(in);
      }
      try (InputStream in = zip.getInputStream(zip.getEntry("pages/" + p.result))) {
        result = read(in);
      }
      require(
          Arrays.equals(original, read(new FileInputStream(store.file(p.original))))
              && Arrays.equals(result, read(new FileInputStream(store.file(p.result)))),
          "Backup retains source and result JPEG bytes");
      for (ZipEntry e : Collections.list(zip.entries()))
        if (e.getName().endsWith(".tflite")) throw new AssertionError("Backup contains a model");
    }
    DocumentStore.Page p = store.page(id);
    JSONObject row = null;
    JSONArray pages = manifest.getJSONArray("pages");
    for (int i = 0; i < pages.length(); i++)
      if (pages.getJSONObject(i).getString("id").equals(id)) row = pages.getJSONObject(i);
    JSONObject subset =
        new JSONObject()
            .put("version", 1)
            .put(
                "folders",
                new JSONArray()
                    .put(new JSONObject().put("id", folder).put("name", title + " restored")))
            .put(
                "documents",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("id", document)
                            .put("folder", folder)
                            .put("title", title + " restored")))
            .put("pages", new JSONArray().put(row));
    byte[] portable = portable(subset, p.original, original, p.result, result);
    Backup.restore(context, new ByteArrayInputStream(portable));
    List<DocumentStore.Document> restored = store.documents(0, title + " restored", false);
    require(restored.size() == 1, "Restore merges documents without overwriting the library");
    DocumentStore.Page copy = store.pages(restored.get(0).id).get(0);
    require(
        hash(store.file(copy.original)).equals(hash(store.file(p.original)))
            && hash(store.file(copy.result)).equals(hash(store.file(p.result))),
        "Restored scans retain both full-resolution images");
    long restoredFolder = restored.get(0).folder;
    store.permanentlyDelete(restored.get(0).id);
    store.deleteFolder(restoredFolder);
    int folders = store.folders().size();
    JSONObject bad = new JSONObject(row.toString()).put("original", "../escape.jpg");
    subset.put("pages", new JSONArray().put(row).put(bad));
    try {
      Backup.restore(
          context,
          new ByteArrayInputStream(portable(subset, p.original, original, p.result, result)));
      throw new AssertionError("Unsafe backup accepted");
    } catch (IOException expected) {
    }
    require(
        store.folders().size() == folders
            && store.documents(0, title + " restored", false).isEmpty(),
        "Invalid restore rolls back metadata and refuses unsafe filenames");
    archive.delete();
  }

  private byte[] portable(JSONObject manifest, String original, byte[] a, String result, byte[] b)
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("pages/" + original));
      zip.write(a);
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("pages/" + result));
      zip.write(b);
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private interface Condition {
    boolean ready() throws Exception;
  }

  private void waitUntil(Condition condition, long timeout) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < end) {
      if (condition.ready()) return;
      SystemClock.sleep(80);
      waitForIdleSync();
    }
    throw new AssertionError("Timed out waiting for UI or file update");
  }

  private Object field(Object object, String name) {
    try {
      Field f = object.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(object);
    } catch (Exception ex) {
      throw new AssertionError(ex);
    }
  }

  private View top() {
    List<View> views = WindowInspector.getGlobalWindowViews();
    for (View view : views) if (view.isShown() && view.hasWindowFocus()) return view;
    for (int i = views.size() - 1; i >= 0; i--) if (views.get(i).isShown()) return views.get(i);
    throw new AssertionError("No window");
  }

  private View find(View view, String label) {
    if (label.equals(String.valueOf(view.getContentDescription()))
        || view instanceof android.widget.TextView
            && label.contentEquals(((android.widget.TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = group.getChildCount() - 1; i >= 0; i--) {
        View found = find(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void click(View root, String label) {
    View view = find(root, label);
    if (view == null) throw new AssertionError("Missing action " + label);
    while (view != null) {
      if (view.performClick()) return;
      view = view.getParent() instanceof View ? (View) view.getParent() : null;
    }
    throw new AssertionError("Action is not clickable " + label);
  }

  private void capture(String label, View root) {
    runOnMainSync(
        () -> {
          Bitmap image =
              Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
          root.draw(new Canvas(image));
          try (OutputStream out =
              new FileOutputStream(
                  new File(context.getExternalFilesDir(null), "ui-" + label + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
          } catch (IOException ex) {
            throw new AssertionError(ex);
          }
          image.recycle();
        });
  }

  private String describe(View v) {
    String out =
        String.valueOf(v.getContentDescription())
            + (v instanceof android.widget.TextView
                ? " | " + ((android.widget.TextView) v).getText()
                : "")
            + "\n";
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) out += describe(g.getChildAt(i));
    }
    return out;
  }

  private void require(boolean value, String label) {
    if (!value) throw new AssertionError(label + "\n" + describe(top()));
    checks.put(label);
    Bundle b = new Bundle();
    b.putString("stream", "PASS " + label + "\n");
    sendStatus(0, b);
  }

  private byte[] read(InputStream in) throws IOException {
    try (in;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      Exporter.copy(in, bytes);
      return bytes.toByteArray();
    }
  }

  private String hash(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] b = new byte[16384];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    return Base64.getEncoder().encodeToString(digest.digest());
  }
}
