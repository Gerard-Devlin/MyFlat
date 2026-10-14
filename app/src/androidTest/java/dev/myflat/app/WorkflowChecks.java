package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Checks the local workflows added after the first scan implementation. */
@android.annotation.TargetApi(29)
final class WorkflowChecks {
  private final Instrumentation test;
  private final Context context;
  private final DocumentStore store;
  private final List<Activity> activities = new ArrayList<>();
  private final List<String> documents = new ArrayList<>();
  private final List<Long> folders = new ArrayList<>();
  private long folder;
  private String document, page;
  private final String unique = "Workflow test " + UUID.randomUUID();

  WorkflowChecks(Instrumentation test) {
    this.test = test;
    context = test.getTargetContext();
    store = MyFlatApp.of(context).store;
  }

  void resolutionChecksOnly() throws Exception {
    folder = store.createFolder(unique);
    document = store.createDocument(folder, unique);
    try {
      testPhotoResolutions();
    } finally {
      for (Activity activity : activities) if (!activity.isFinishing()) ui(activity::finish);
      store.permanentlyDelete(document);
      store.deleteFolder(folder);
    }
  }

  void run() throws Exception {
    SharedPreferences appearance = context.getSharedPreferences("appearance", 0),
        security = context.getSharedPreferences("folder_security", 0),
        settings = context.getSharedPreferences("settings", 0);
    Map<String, ?> oldAppearance = new HashMap<>(appearance.getAll()),
        oldSecurity = new HashMap<>(security.getAll()),
        oldSettings = new HashMap<>(settings.getAll());
    try {
      appearance.edit().putString("language", "zh-CN").putInt("night_mode", 1).commit();
      settings
          .edit()
          .remove("straighten_default_enabled")
          .putString("scan", "{\"straighten\":false}")
          .commit();
      check(
          ScanSettings.defaults(context).straighten,
          "Straightening is enabled on migration and on first use");
      ScanSettings off = ScanSettings.defaults(context);
      off.straighten = false;
      off.save(context);
      check(
          !ScanSettings.defaults(context).straighten,
          "An explicit straightening-off choice survives reload");
      restore(settings, oldSettings);
      folder = store.createFolder(unique);
      folders.add(folder);
      document = store.createDocument(folder, unique);
      documents.add(document);
      Bitmap image = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
      image.eraseColor(0xffeadbc4);
      new Canvas(image).drawText("Scan test", 30, 100, new Paint());
      page = store.addPage(document, image, new ScanSettings());
      store.complete(store.page(page), image, 100);
      store.setColorTag(page, 0xff81c784);
      check(store.page(page).colorTag == 0xff81c784, "Page color tags persist in the database");
      File original = store.file(store.page(page).original),
          result = store.file(store.page(page).result);
      byte[] raw = bytes(original), processed = bytes(result);
      store.trashPages(Collections.singletonList(page));
      check(
          store.pages(document).isEmpty() && original.isFile() && result.isFile(),
          "Deleting a page keeps its files in the recycle bin");
      store.restorePages(Collections.singletonList(page));
      check(
          store.pages(document).size() == 1 && Arrays.equals(processed, bytes(result)),
          "Restoring a page keeps the processed JPEG unchanged");
      Backup.Preview normal = Backup.inspect(context, false);
      store.setFolderLocked(folder, true);
      check(
          Backup.inspect(context, false).pages == normal.pages - 1,
          "Backups exclude secure folders by default");
      ByteArrayOutputStream archive = new ByteArrayOutputStream();
      Backup.write(context, archive, true);
      store.renameFolder(folder, unique + " source");
      Set<String> before = new HashSet<>();
      for (DocumentStore.Document doc : store.documents(0, "", false)) before.add(doc.id);
      Backup.restore(context, new ByteArrayInputStream(archive.toByteArray()));
      List<String> restored = new ArrayList<>();
      for (DocumentStore.Document doc : store.documents(0, "", false))
        if (!before.contains(doc.id)) restored.add(doc.id);
      documents.addAll(restored);
      String restoredPage = null;
      for (String id : restored)
        if (store.document(id).title.equals(unique)) {
          restoredPage = store.pages(id).get(0).id;
          long f = store.document(id).folder;
          if (!folders.contains(f)) folders.add(f);
          check(!store.folderLocked(f), "A restored new folder has no stale PIN lock");
        }
      check(
          restoredPage != null
              && store.page(restoredPage).colorTag == 0xff81c784
              && Arrays.equals(raw, bytes(store.file(store.page(restoredPage).original))),
          "Backup restores tags and original JPEG bytes");
      store.setFolderLocked(folder, false);
      File orphanOriginal = store.file(UUID.randomUUID() + "-original.jpg");
      File orphanResult =
          new File(
              orphanOriginal.getParentFile(),
              orphanOriginal.getName().replace("-original.jpg", "-result.jpg"));
      try (OutputStream out = new FileOutputStream(orphanOriginal)) {
        out.write(raw);
      }
      try (OutputStream out = new FileOutputStream(orphanResult)) {
        out.write(processed);
      }
      LostPages.Preview lost = LostPages.inspect(context);
      check(
          lost.files.stream().anyMatch(pair -> pair[0].equals(orphanOriginal)),
          "Lost-page recovery finds a valid orphan JPEG pair");
      int recovered = LostPages.restore(context, lost);
      check(
          recovered > 0
              && !LostPages.inspect(context).files.stream()
                  .anyMatch(pair -> pair[0].equals(orphanOriginal)),
          "Recovered files are registered once and not rediscovered");
      for (DocumentStore.Document doc : store.documents(0, "恢复的页面", false))
        if (store.pages(doc.id).stream().anyMatch(p -> p.original.equals(orphanOriginal.getName())))
          documents.add(doc.id);
      Bitmap paper = new dev.myflat.scanner.ScanEngine(context).paperBackground(image);
      check(
          paper.getWidth() == 320 && paper.getHeight() == 320,
          "Cleanup obtains the paper-color model field");
      paper.recycle();
      ui(
          () -> {
            PageCanvas canvas = new PageCanvas(context);
            canvas.layout(0, 0, 800, 1000);
            canvas.image(image);
            canvas.mode(PageCanvas.Mode.ERASE);
            Bitmap tan = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            tan.eraseColor(0xffccbb99);
            canvas.paperBackground(tan);
            long now = SystemClock.uptimeMillis();
            canvas.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 400, 500, 0));
            canvas.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 400, 500, 0));
            check(
                canvas.hasUndo()
                    && canvas.image().getPixel(160, 240) == 0xffccbb99
                    && canvas.image().getPixel(0, 0) == image.getPixel(0, 0),
                "Cleanup modifies only painted pixels using paper color");
            canvas.undo();
            check(
                canvas.hasRedo() && canvas.image().getPixel(160, 240) == image.getPixel(160, 240),
                "Cleanup undo restores the previous stroke");
            canvas.redo();
            check(
                canvas.image().getPixel(160, 240) == 0xffccbb99,
                "Cleanup redo reapplies the removed stroke");
            tan.recycle();
          });
      image.recycle();
      BitMatrix matrix =
          new MultiFormatWriter()
              .encode("https://example.com/myflat", BarcodeFormat.QR_CODE, 300, 300);
      Bitmap qr = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
      int[] pixels = new int[90000];
      for (int y = 0; y < 300; y++)
        for (int x = 0; x < 300; x++)
          pixels[y * 300 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
      qr.setPixels(pixels, 0, 300, 0, 0, 300, 300);
      CameraQrDetector.Detection decoded = CameraQrDetector.detect(qr);
      check(
          decoded != null
              && decoded.text.equals("https://example.com/myflat")
              && decoded.intent().getData().toString().equals(decoded.text),
          "QR decoding offers the correct URL action without opening it");
      qr.eraseColor(Color.WHITE);
      check(CameraQrDetector.detect(qr) == null, "A blank preview does not generate a QR result");
      qr.recycle();
      check(
          FilenamePresets.validate("../private.pdf") != null
              && FilenamePresets.validate("report.pdf") == null,
          "Filename entry rejects path characters and accepts a normal name");
      check(
          !FilenamePresets.render("{FOLDERNAME}_{YYYY}{MM}{DD}", unique).contains("{"),
          "Filename chips produce a complete folder/date name");
      testWelcome();
      testUi();
      testSecurity(security);
      testLegacyCamera();
      testPhotoResolutions();
      testHandwritingReview();
      testOriginalCleanup();
    } finally {
      ui(
          () -> {
            for (Activity activity : activities) if (!activity.isFinishing()) activity.finish();
            FolderSecurity.lockSession();
          });
      for (String id : new LinkedHashSet<>(documents))
        if (store.document(id) != null)
          try {
            store.permanentlyDelete(id);
          } catch (Exception ignored) {
          }
      for (Long id : new LinkedHashSet<>(folders))
        try {
          store.permanentlyDeleteFolder(id);
        } catch (Exception ignored) {
        }
      restore(appearance, oldAppearance);
      restore(security, oldSecurity);
      restore(settings, oldSettings);
    }
  }

  private void testWelcome() throws Exception {
    SharedPreferences prefs = context.getSharedPreferences("welcome", 0);
    Map<String, ?> previous = new HashMap<>(prefs.getAll());
    prefs.edit().clear().commit();
    Activity welcome = launch(WelcomeActivity.class, new Intent());
    check(
        find(welcome.getWindow().getDecorView(), "同意并开始") != null,
        "First entry presents terms and privacy before the scan guide");
    ui(() -> click(welcome.getWindow().getDecorView(), "查看更多"));
    check(find(top(), "使用条款") != null, "The entry screen opens the full terms page");
    ui(
        () -> {
          click(top(), "返回");
          welcome.finish();
        });
    restore(prefs, previous);
  }

  private void testUi() throws Exception {
    Activity main = launch(MainActivity.class, new Intent());
    Dialog[] search = {null};
    ui(() -> search[0] = LibrarySearchDialog.show(main, 0, id -> {}));
    waitFor(() -> findInput(top()) != null, 3000);
    ui(
        () -> {
          EditText input = findInput(top());
          input.setText(unique + " source");
        });
    test.waitForIdleSync();
    check(find(top(), unique + " source") != null, "Library search finds a folder by name");
    ui(() -> search[0].dismiss());
    Activity editor = launch(EditorActivity.class, new Intent().putExtra("page", page));
    waitFor(() -> find(editor.getWindow().getDecorView(), "颜色标签") != null, 10000);
    ui(() -> click(editor.getWindow().getDecorView(), "颜色标签"));
    waitFor(() -> find(top(), "红色标签") != null, 3000);
    check(
        find(top(), "红色标签") != null && find(top(), "绿色标签") != null,
        "The page-viewer tag button opens the color chooser");
    ui(() -> click(top(), "红色标签"));
    check(store.page(page).colorTag == 0xffe57373, "Selecting a color updates the page tag");
    ui(
        () -> {
          context.getSharedPreferences("appearance", 0).edit().putInt("night_mode", 2).commit();
          AppAppearance.apply(editor.getWindow().getDecorView());
        });
    check(AppAppearance.isDark(context), "Night mode applies to the actual activity");
    TextView[] userName = {null};
    ui(
        () -> {
          userName[0] = Ui.text(editor, "PDF 设置", 14, Ui.INK);
          AppAppearance.keepText(userName[0]);
          context.getSharedPreferences("appearance", 0).edit().putString("language", "en").commit();
          AppAppearance.apply(userName[0]);
        });
    check(
        userName[0].getText().toString().equals("PDF 设置")
            && AppAppearance.text(context, "PDF 设置").equals("PDF Settings"),
        "Language changes translate controls while keeping user names intact");
    context
        .getSharedPreferences("appearance", 0)
        .edit()
        .putString("language", "zh-CN")
        .putInt("night_mode", 1)
        .commit();
    ui(editor::finish);
  }

  private void testSecurity(SharedPreferences preferences) throws Exception {
    preferences.edit().clear().commit();
    FolderSecurity.lockSession();
    Activity settings = launch(SettingsActivity.class, new Intent());
    ui(() -> FolderSecurity.settings(settings));
    ui(() -> click(top(), "创建PIN码"));
    type("1234");
    check(find(top(), "确认PIN码") != null, "PIN creation requires confirmation");
    type("1234");
    waitFor(() -> FolderSecurity.hasPin(context), 8000);
    ui(() -> click(top(), "完成"));
    store.setFolderLocked(folder, true);
    AtomicBoolean allowed = new AtomicBoolean();
    ui(() -> FolderSecurity.require(settings, folder, () -> allowed.set(true), () -> {}));
    type("4321");
    waitFor(() -> find(top(), "PIN码不正确，请再试一次。") != null, 8000);
    check(!allowed.get(), "A wrong PIN does not unlock the folder");
    type("1234");
    waitFor(allowed::get, 8000);
    check(
        FolderSecurity.isAccessible(context, folder),
        "The correct PIN grants access to the folder");
    ui(FolderSecurity::lockSession);
    check(
        !FolderSecurity.isAccessible(context, folder),
        "Session locking revokes previous folder access");
    Activity locked = launch(EditorActivity.class, new Intent().putExtra("page", page));
    waitFor(() -> hasPrompt(locked), 8000);
    check(
        find(locked.getWindow().getDecorView(), "颜色标签") == null,
        "A direct editor entry cannot read a locked page before authentication");
    ui(locked::finish);
    store.setFolderLocked(folder, false);
  }

  private boolean hasPrompt(Activity activity) throws Exception {
    Field field = FolderSecurity.class.getDeclaredField("prompts");
    field.setAccessible(true);
    return ((Map<?, ?>) field.get(null)).containsKey(activity);
  }

  private void testLegacyCamera() throws Exception {
    SharedPreferences prefs = context.getSharedPreferences("preferences", 0),
        cameraPrefs = context.getSharedPreferences("CameraActivity", 0);
    Map<String, ?> old = new HashMap<>(prefs.getAll()),
        oldCamera = new HashMap<>(cameraPrefs.getAll());
    Activity camera = null;
    try {
      prefs.edit().putInt("camera_api", 1).commit();
      cameraPrefs
          .edit()
          .putBoolean("scan_guide_seen", true)
          .putInt("timer_mode", 0)
          .putBoolean("auto", false)
          .commit();
      camera =
          launch(
              CameraActivity.class,
              new Intent().putExtra("folder", folder).putExtra("document", document));
      Activity current = camera;
      waitFor(() -> field(current, "legacy") != null, 12000);
      check(
          field(current, "legacy") instanceof LegacyCapture,
          "Camera1 selection opens its real legacy preview backend");
      int count = store.pages(document).size();
      ui(() -> click(current.getWindow().getDecorView(), "快门"));
      waitFor(() -> store.pages(document).size() > count, 15000);
      DocumentStore.Page captured = store.pages(document).get(store.pages(document).size() - 1);
      android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(store.file(captured.original).getPath(), bounds);
      LegacyCapture legacy = (LegacyCapture) field(current, "legacy");
      check(
          (long) bounds.outWidth * bounds.outHeight
              == (long) legacy.photoSize.getWidth() * legacy.photoSize.getHeight(),
          "Camera1 capture retains all pixels in its largest supported JPEG");
    } finally {
      if (camera != null) {
        Activity current = camera;
        ui(current::finish);
      }
      restore(prefs, old);
      restore(cameraPrefs, oldCamera);
    }
  }

  private Object field(Object value, String name) throws Exception {
    Field field = value.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(value);
  }

  private void testPhotoResolutions() throws Exception {
    android.util.Size small = new android.util.Size(640, 480),
        large = new android.util.Size(4000, 3000),
        ultra = new android.util.Size(8000, 6000);
    List<PhotoResolution.Option> merged =
        PhotoResolution.sizes(
            new android.util.Size[] {small, large},
            new android.util.Size[] {large},
            new android.util.Size[] {large, ultra},
            null);
    check(
        merged.size() == 3
            && merged.get(0).size.equals(ultra)
            && merged.get(0).maximum
            && !merged.get(1).maximum,
        "Resolution choices deduplicate JPEG sizes and retain the correct sensor mode");
    SharedPreferences prefs = context.getSharedPreferences("preferences", 0),
        cameraPrefs = context.getSharedPreferences("CameraActivity", 0);
    Map<String, ?> old = new HashMap<>(prefs.getAll()),
        oldCamera = new HashMap<>(cameraPrefs.getAll());
    Activity camera = null;
    try {
      cameraPrefs
          .edit()
          .putBoolean("scan_guide_seen", true)
          .putInt("timer_mode", 0)
          .putBoolean("auto", false)
          .commit();
      for (int backend : new int[] {2, 1}) {
        prefs.edit().putInt("camera_api", backend).commit();
        camera =
            launch(
                CameraActivity.class,
                new Intent().putExtra("folder", folder).putExtra("document", document));
        Activity current = camera;
        waitFor(() -> cameraReady(current, backend), 12000);
        PhotoResolution.Configuration config =
            (PhotoResolution.Configuration) field(current, "photoResolutions");
        PhotoResolution.Option chosen = config.options.get(Math.min(1, config.options.size() - 1));
        ui(() -> click(current.getWindow().getDecorView(), "更多"));
        ui(() -> click(top(), "扫描分辨率"));
        waitFor(() -> find(top(), chosen.label()) != null, 5000);
        check(
            find(top(), chosen.label()) != null,
            "Camera" + backend + " exposes actual pixel dimensions in the capture menu");
        ui(
            () -> {
              View view = top();
              Bitmap shot =
                  Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
              view.draw(new Canvas(shot));
              try (OutputStream out =
                  new FileOutputStream(
                      new File(
                          context.getExternalFilesDir(null),
                          "resolution-camera" + backend + ".png"))) {
                shot.compress(Bitmap.CompressFormat.PNG, 100, out);
              } catch (IOException error) {
                throw new AssertionError(error);
              } finally {
                shot.recycle();
              }
              click(top(), chosen.label());
            });
        waitFor(
            () -> cameraReady(current, backend) && outputSize(current, backend).equals(chosen.size),
            12000);
        int count = store.pages(document).size();
        ui(() -> click(current.getWindow().getDecorView(), "快门"));
        waitFor(() -> store.pages(document).size() > count, 15000);
        DocumentStore.Page captured = store.pages(document).get(store.pages(document).size() - 1);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(store.file(captured.original).getPath(), bounds);
        check(
            (long) bounds.outWidth * bounds.outHeight
                == (long) chosen.size.getWidth() * chosen.size.getHeight(),
            "Camera" + backend + " saves the selected JPEG pixel count without resizing");
        ui(current::finish);
        camera =
            launch(
                CameraActivity.class,
                new Intent().putExtra("folder", folder).putExtra("document", document));
        Activity reopened = camera;
        waitFor(() -> cameraReady(reopened, backend), 12000);
        check(
            outputSize(reopened, backend).equals(chosen.size),
            "Camera" + backend + " remembers the selected resolution after reopening");
        ui(reopened::finish);
        camera = null;
      }
    } finally {
      if (camera != null) {
        Activity remaining = camera;
        ui(remaining::finish);
      }
      restore(prefs, old);
      restore(cameraPrefs, oldCamera);
    }
  }

  private boolean cameraReady(Activity activity, int backend) throws Exception {
    return backend == 1
        ? field(activity, "legacy") != null
        : field(activity, "session") != null && field(activity, "reader") != null;
  }

  private android.util.Size outputSize(Activity activity, int backend) throws Exception {
    if (backend == 1) return ((LegacyCapture) field(activity, "legacy")).photoSize;
    android.media.ImageReader reader = (android.media.ImageReader) field(activity, "reader");
    return new android.util.Size(reader.getWidth(), reader.getHeight());
  }

  private void testHandwritingReview() throws Exception {
    byte[] previous = bytes(store.file(store.page(page).result));
    Activity review = launch(HandwritingReviewActivity.class, new Intent().putExtra("page", page));
    waitFor(() -> field(review, "output") != null, 15000);
    check(
        Arrays.equals(previous, bytes(store.file(store.page(page).result))),
        "Handwriting preview preserves the existing scan until save is chosen");
    int count = store.pages(document).size();
    ui(() -> click(review.getWindow().getDecorView(), "保存"));
    check(
        find(top(), "保存为新页面") != null && find(top(), "覆盖") != null,
        "Handwriting preview offers both supported saving methods");
    ui(() -> click(top(), "保存为新页面"));
    waitFor(() -> store.pages(document).size() > count && review.isFinishing(), 15000);
    check(
        Arrays.equals(previous, bytes(store.file(store.page(page).result))),
        "Saving handwriting removal as a new page keeps the original page unchanged");
  }

  private void testOriginalCleanup() throws Exception {
    byte[] result = bytes(store.file(store.page(page).result));
    store.clearOriginals(Collections.singletonList(page));
    DocumentStore.Page p = store.page(page);
    check(
        p.original.equals(p.result) && Arrays.equals(result, bytes(store.file(p.result))),
        "Original-image cleanup preserves the current scan bytes");
  }

  private void type(String value) throws Exception {
    ui(
        () -> {
          for (char digit : value.toCharArray()) click(top(), "" + digit);
        });
    test.waitForIdleSync();
  }

  private Activity launch(Class<? extends Activity> type, Intent intent) {
    Activity a =
        test.startActivitySync(
            intent
                .setClass(context, type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    activities.add(a);
    test.waitForIdleSync();
    return a;
  }

  private View top() {
    List<View> views = WindowInspector.getGlobalWindowViews();
    for (int i = views.size() - 1; i >= 0; i--) if (views.get(i).isShown()) return views.get(i);
    throw new AssertionError("No active window");
  }

  private View find(View view, String text) {
    if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
        || view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private EditText findInput(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        EditText input = findInput(group.getChildAt(i));
        if (input != null) return input;
      }
    }
    return null;
  }

  private void click(View view, String text) {
    View found = find(view, text);
    if (found == null) throw new AssertionError("Missing " + text);
    while (found != null) {
      if (found.performClick()) return;
      if (found.getParent() instanceof AdapterView) {
        AdapterView<?> list = (AdapterView<?>) found.getParent();
        int position = list.getPositionForView(found);
        if (position >= 0
            && list.performItemClick(found, position, list.getItemIdAtPosition(position))) return;
      }
      found = found.getParent() instanceof View ? (View) found.getParent() : null;
    }
    throw new AssertionError("Not clickable " + text);
  }

  private void ui(Runnable action) {
    AtomicReference<Throwable> error = new AtomicReference<>();
    test.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable ex) {
            error.set(ex);
          }
        });
    if (error.get() != null) throw new AssertionError(error.get());
  }

  private interface Condition {
    boolean ready() throws Exception;
  }

  private void waitFor(Condition condition, long timeout) throws Exception {
    long deadline = SystemClock.uptimeMillis() + timeout;
    while (SystemClock.uptimeMillis() < deadline) {
      if (condition.ready()) return;
      SystemClock.sleep(50);
    }
    throw new AssertionError("Workflow did not reach the expected state");
  }

  private void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    Bundle result = new Bundle();
    result.putString("stream", "PASS " + message + "\n");
    test.sendStatus(0, result);
  }

  private static byte[] bytes(File file) throws IOException {
    try (InputStream input = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Exporter.copy(input, output);
      return output.toByteArray();
    }
  }

  private static void restore(SharedPreferences prefs, Map<String, ?> values) {
    SharedPreferences.Editor e = prefs.edit().clear();
    for (Map.Entry<String, ?> row : values.entrySet()) {
      Object v = row.getValue();
      String k = row.getKey();
      if (v instanceof String) e.putString(k, (String) v);
      else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
      else if (v instanceof Integer) e.putInt(k, (Integer) v);
      else if (v instanceof Long) e.putLong(k, (Long) v);
      else if (v instanceof Float) e.putFloat(k, (Float) v);
      else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
    }
    e.commit();
  }
}
