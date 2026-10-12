package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.view.View;
import dev.myflat.scanner.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Device integration tests, without a third-party test runner or network. */
public final class SmokeInstrumentation extends Instrumentation {
  private final JSONArray checks = new JSONArray();
  private Context context;
  private DocumentStore store;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    JSONObject json = new JSONObject();
    try {
      context = getTargetContext();
      Activity activity =
          startActivitySync(
              new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      store = MyFlatApp.of(context).store;
      runTests();
      runOnMainSync(activity::recreate);
      json.put("status", "passed")
          .put("checks", checks)
          .put("android", Build.VERSION.SDK_INT)
          .put("abi", Arrays.toString(Build.SUPPORTED_ABIS));
      writeReport(json);
      report.putString("stream", json.toString(2));
      finish(Activity.RESULT_OK, report);
    } catch (Throwable ex) {
      try {
        StringWriter trace = new StringWriter();
        ex.printStackTrace(new PrintWriter(trace));
        json.put("status", "failed").put("checks", checks).put("error", trace.toString());
        writeReport(json);
        report.putString("stream", json.toString(2));
      } catch (Exception ignored) {
      }
      finish(Activity.RESULT_CANCELED, report);
    }
  }

  private void runTests() throws Exception {
    runOnMainSync(
        () -> {
          Bitmap launcher = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888);
          android.graphics.drawable.Drawable icon =
              context.getApplicationInfo().loadIcon(context.getPackageManager());
          icon.setBounds(0, 0, 216, 216);
          icon.draw(new Canvas(launcher));
          require(launcher.getPixel(108, 20) == Color.BLACK, "桌面图标使用纯黑底色并留出边距");
          int top = 216, bottom = 0;
          for (int y = 0; y < 216; y++)
            for (int x = 0; x < 216; x++) {
              int pixel = launcher.getPixel(x, y);
              if (Color.alpha(pixel) > 0 && Color.red(pixel) > 100 && Color.green(pixel) > 100) {
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
              }
            }
          require(top > 22 && bottom < 194, "图标中的纸团完整保留且不会贴边裁切");
          try (OutputStream out =
              new FileOutputStream(
                  new File(context.getExternalFilesDir(null), "launcher-preview.png"))) {
            launcher.compress(Bitmap.CompressFormat.PNG, 100, out);
          } catch (IOException ex) {
            throw new AssertionError(ex);
          }
          launcher.recycle();
          Bitmap base = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
          base.eraseColor(Color.BLACK);
          PageCanvas view = new PageCanvas(context);
          view.layout(0, 0, 300, 300);
          view.image(base);
          view.mode(PageCanvas.Mode.ERASE);
          require(view.image() != base, "擦除编辑副本与原结果隔离");
          Bitmap display = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
          view.draw(new Canvas(display));
          long now = SystemClock.uptimeMillis();
          android.view.MotionEvent down =
              android.view.MotionEvent.obtain(
                  now, now, android.view.MotionEvent.ACTION_DOWN, 150, 150, 0);
          view.onTouchEvent(down);
          down.recycle();
          require(
              base.getPixel(50, 50) == Color.BLACK && view.image().getPixel(50, 50) == Color.WHITE,
              "擦除手势生效且取消不改变原结果");
          view.undo();
          require(view.image().getPixel(50, 50) == Color.BLACK, "擦除撤销恢复前一笔");
          base.recycle();
          display.recycle();
        });
    // Tests only operate on their own named documents; existing user documents remain intact.
    for (DocumentStore.Document d : store.documents(0, "MyFlat 集成验收", false))
      store.permanentlyDelete(d.id);
    for (DocumentStore.Document d : store.documents(0, "PDF 往返验收", false))
      store.permanentlyDelete(d.id);
    Bitmap fixture;
    try (InputStream in = getContext().getAssets().open("acceptance.jpg")) {
      fixture = BitmapFactory.decodeStream(in);
    }
    require(fixture != null && fixture.getWidth() == 1279, "固定验收图可解码");
    try (LivePageDetector detector = new LivePageDetector(context)) {
      Bitmap cameraFrame = Bitmap.createScaledBitmap(fixture, 144, 192, true);
      LivePageDetector.Result detected = detector.detect(cameraFrame, false, .5f);
      require(!detected.outlines.isEmpty(), "实时模型在验收书页上检测到曲面轮廓");
      float[] edge = detected.outlines.get(0);
      require(
          Math.abs(edge[0] - .205f) < .06f
              && Math.abs(edge[1] - .137f) < .06f
              && Math.abs(edge[96] - .743f) < .06f
              && Math.abs(edge[97] - .864f) < .06f,
          "取景轮廓与实际展平页角一致");
      require(detector.detect(cameraFrame, false, .5f).outlines.size() == 1, "实时解释器可连续复用");
      cameraFrame.eraseColor(Color.WHITE);
      require(
          detector.detect(cameraFrame, false, .5f).state == PageGeometry.State.PAGE_INVALID,
          "空白取景显示寻找目标，不伪造检测框");
      cameraFrame.eraseColor(Color.BLACK);
      require(detector.detect(cameraFrame, false, .5f).outlines.isEmpty(), "黑暗取景不触发自动拍摄");
      cameraFrame.recycle();
    }
    testCameraGuidance();
    long folder = store.createFolder("集成测试文件夹");
    String doc = store.createDocument(folder, "MyFlat 集成验收");
    ScanSettings raw = new ScanSettings();
    raw.dewarp = raw.enhance = raw.fingers = false;
    String first = store.addPage(doc, fixture, raw), second = store.addPage(doc, fixture, raw);
    require(store.pages(doc).size() == 2, "两页导入与原图落盘");
    DocumentStore.Page claimed = store.nextJob();
    require(claimed != null, "SQLite 队列领取任务");
    store.recoverQueue();
    require("queued".equals(store.page(claimed.id).status), "进程中断后任务恢复为待处理");
    runOnMainSync(() -> MyFlatApp.of(context).startProcessing());
    waitReady(doc, 45000);
    require(
        "ready".equals(store.page(first).status) && "ready".equals(store.page(second).status),
        "前台服务完成队列并保存真实结果");
    DocumentStore.Page page = store.page(first);
    String originalHash = hash(store.file(page.original));
    Bitmap processed = Images.decode(store.file(page.result), 2400);
    Bitmap cropped =
        ImageMath.crop(
            processed,
            new float[] {
              30,
              30,
              processed.getWidth() - 31,
              45,
              processed.getWidth() - 40,
              processed.getHeight() - 31,
              20,
              processed.getHeight() - 40
            });
    store.complete(page, cropped, 95);
    cropped.recycle();
    processed.recycle();
    require(originalHash.equals(hash(store.file(page.original))), "透视裁剪保留原图字节");
    store.reorder(doc, second, 0);
    require(store.pages(doc).get(0).id.equals(second), "调整页序持久化");
    List<DocumentStore.Page> pages = store.pages(doc);
    File zip = Exporter.create(context, "验收 ZIP", pages, Exporter.Format.ZIP, false, (a, b) -> {});
    try (ZipFile archive = new ZipFile(zip)) {
      require(
          archive.size() == 2
              && archive.getEntry("001.jpg") != null
              && archive.getEntry("002.jpg") != null,
          "ZIP 页数及编号正确");
      try (InputStream in = archive.getInputStream(archive.getEntry("001.jpg"))) {
        require(hash(in).equals(hash(store.file(pages.get(0).result))), "ZIP 顺序和当前页序一致");
      }
    }
    File jpeg =
        Exporter.create(
            context,
            "验收 JPEG",
            Collections.singletonList(pages.get(0)),
            Exporter.Format.JPEG,
            false,
            (a, b) -> {});
    require(hash(jpeg).equals(hash(store.file(pages.get(0).result))), "JPEG 导出保持扫描结果");
    File pdf = Exporter.create(context, "验收 PDF", pages, Exporter.Format.PDF, true, (a, b) -> {});
    try (ParcelFileDescriptor fd =
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(fd)) {
      require(renderer.getPageCount() == 2, "PDF 可重新打开且包含两页");
      try (PdfRenderer.Page rendered = renderer.openPage(0)) {
        require(rendered.getWidth() == 595 && rendered.getHeight() == 842, "PDF A4 尺寸正确");
        Bitmap preview = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888);
        rendered.render(preview, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        int[] px = ImageMath.pixels(preview);
        int dark = 0;
        for (int color : px) if (Color.red(color) < 100) dark++;
        require(dark > 3000, "PDF 包含真实页面图像");
        preview.recycle();
      }
    }
    Uri uri = ShareProvider.uri(context, pdf);
    require(
        "application/pdf".equals(context.getContentResolver().getType(uri)), "分享 URI 提供正确 MIME");
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      require(in != null && in.read() == '%', "分享 URI 可读 PDF");
    }
    try {
      Uri attack =
          new Uri.Builder()
              .scheme("content")
              .authority(context.getPackageName() + ".files")
              .appendPath("../myflat.db")
              .build();
      context.getContentResolver().openFileDescriptor(attack, "r");
      throw new AssertionError("Provider allowed traversal");
    } catch (FileNotFoundException expected) {
      pass("分享接口拒绝路径越界");
    }
    String imported = store.createDocument(1, "PDF 往返验收");
    int importedCount =
        Importer.importUris(context, imported, Collections.singletonList(uri), raw, s -> {});
    waitReady(imported, 45000);
    require(importedCount == 2 && store.pages(imported).size() == 2, "PDF 导出后可重新导入两页");
    ScanSettings dual = ScanSettings.parse(raw.json());
    dual.doublePage = true;
    dual.rightFirst = true;
    dual.split = .55f;
    String dualDoc = store.createDocument(1, "双页测试临时文档");
    require(Importer.add(context, store, dualDoc, fixture, dual) == 2, "双页切分生成两张独立页面");
    waitReady(dualDoc, 45000);
    require(store.page(store.pages(dualDoc).get(0).id).width < fixture.getWidth(), "双页结果宽度正确");
    store.permanentlyDelete(dualDoc);
    store.trash(doc, true);
    require(store.documents(folder, "MyFlat 集成验收", false).isEmpty(), "文档放入回收站后移出资料库");
    store.trash(doc, false);
    require(store.documents(folder, "MyFlat 集成验收", false).size() == 1, "回收站恢复保留页面");
    store.moveDocument(doc, 1);
    store.deleteFolder(folder);
    require(store.document(doc).folder == 1, "移动文档后删除空文件夹");
    // Exercise optional learned paths separately from the approved default pipeline.
    Bitmap tile = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888);
    tile.eraseColor(Color.WHITE);
    Canvas canvas = new Canvas(tile);
    Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    pen.setColor(Color.BLACK);
    pen.setTextSize(30);
    canvas.drawText("MYFLAT MODEL TEST", 25, 100, pen);
    pen.setColor(Color.BLUE);
    pen.setStrokeWidth(4);
    canvas.drawLine(40, 230, 400, 260, pen);
    ScanEngine.Options optional = new ScanEngine.Options();
    optional.dewarp = false;
    optional.enhance = false;
    optional.fingers = false;
    optional.straighten = true;
    ScanEngine.Result straight =
        new ScanEngine(context).process(tile, optional, (s, p) -> {}, null);
    require(straight.image.getWidth() == 480 && straight.image.getHeight() == 480, "文字拉直模型真实推理完成");
    if (straight.image != tile) straight.image.recycle();
    optional.straighten = false;
    optional.handwriting = true;
    ScanEngine.Result handwriting =
        new ScanEngine(context).process(tile, optional, (s, p) -> {}, null);
    require(
        handwriting.image.getWidth() == 480 && handwriting.timings.containsKey("handwriting"),
        "手写消除模型真实推理完成");
    if (handwriting.image != tile) handwriting.image.recycle();
    tile.recycle();
    // Keep one approved-model demo page on the device for manual UI inspection.
    String scanned = store.createDocument(1, "MyFlat 扫描样张");
    ScanSettings learned = new ScanSettings();
    String scanPage = store.addPage(scanned, fixture, learned);
    runOnMainSync(() -> MyFlatApp.of(context).startProcessing());
    waitReady(scanned, 90000);
    DocumentStore.Page result = store.page(scanPage);
    require(result.width > 500 && result.width < 900 && result.height > 900, "完整书页扫描输出尺寸正确");
    File stages = new File(context.getExternalFilesDir(null), "guide-stages");
    stages.mkdirs();
    ScanEngine.Result example =
        new ScanEngine(context)
            .process(
                fixture,
                new ScanEngine.Options(),
                (name, percent) -> {},
                (name, image) -> {
                  try (OutputStream out = new FileOutputStream(new File(stages, name + ".jpg"))) {
                    image.compress(Bitmap.CompressFormat.JPEG, 95, out);
                  }
                });
    require(
        example.image.getWidth() == result.width && example.image.getHeight() == result.height,
        "引导示例的每一步来自完整扫描流程");
    if (example.image != fixture) example.image.recycle();
    fixture.recycle();
  }

  private void testCameraGuidance() throws Exception {
    SharedPreferences prefs = context.getSharedPreferences("CameraActivity", Context.MODE_PRIVATE);
    boolean seen = prefs.getBoolean("scan_guide_seen", false);
    prefs.edit().putBoolean("scan_guide_seen", false).commit();
    CameraActivity camera =
        (CameraActivity)
            startActivitySync(
                new Intent(context, CameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    waitForIdleSync();
    java.lang.reflect.Field dialogField = CameraActivity.class.getDeclaredField("walkthrough");
    dialogField.setAccessible(true);
    runOnMainSync(
        () -> {
          try {
            ScanGuideDialog guide = (ScanGuideDialog) dialogField.get(camera);
            require(guide != null && guide.isShowing() && guide.step() == 0, "首次进入显示扫描引导");
            for (int step = 0; step < 4; step++) {
              clickText(guide.getWindow().getDecorView(), "下一步");
              require(guide.step() == step + 1, "引导进入第 " + (step + 2) + " 页");
            }
            clickText(guide.getWindow().getDecorView(), "开始扫描");
            require(prefs.getBoolean("scan_guide_seen", false), "完成引导记录首次进入状态");
            require(
                findText(camera.getWindow().getDecorView(), "正在寻找扫描目标…") != null,
                "手动拍摄模式也显示寻找扫描目标提示");
            clickDescription(camera.getWindow().getDecorView(), "更多");
            java.lang.reflect.Field menuField = CameraActivity.class.getDeclaredField("cameraMenu");
            menuField.setAccessible(true);
            Dialog menu = (Dialog) menuField.get(camera);
            require(
                findText(menu.getWindow().getDecorView(), "增强颜色") != null
                    && findText(menu.getWindow().getDecorView(), "去除手指") != null,
                "更多打开深色快捷开关面板");
            boolean enhanced = ScanSettings.defaults(context).enhance;
            clickDescription(menu.getWindow().getDecorView(), "增强颜色");
            require(ScanSettings.defaults(context).enhance != enhanced, "快捷开关立即保存处理设置");
            clickDescription(menu.getWindow().getDecorView(), "增强颜色");
            boolean audible = prefs.getBoolean("shutter_sound", false);
            clickDescription(menu.getWindow().getDecorView(), audible ? "快门声音开" : "快门声音关");
            require(prefs.getBoolean("shutter_sound", false) != audible, "快门声音开关保存并更新状态");
            clickDescription(menu.getWindow().getDecorView(), audible ? "快门声音关" : "快门声音开");
            clickDescription(menu.getWindow().getDecorView(), "扫描指南");
            guide = (ScanGuideDialog) dialogField.get(camera);
            require(guide != null && guide.isShowing() && guide.step() == 0, "更多中可重新查看引导");
            guide.dismiss();
            clickDescription(camera.getWindow().getDecorView(), "选择保存文件夹");
            menu = (Dialog) menuField.get(camera);
            require(findText(menu.getWindow().getDecorView(), "保存扫描到") != null, "保存位置打开文件夹卡片");
            clickDescription(menu.getWindow().getDecorView(), "查看全部文件夹");
            menu = (Dialog) menuField.get(camera);
            require(
                "＋ 新建文件夹"
                    .equals(
                        ((AlertDialog) menu)
                            .getListView()
                            .getAdapter()
                            .getItem(
                                ((AlertDialog) menu).getListView().getAdapter().getCount() - 1)),
                "文件夹卡片可进入完整列表并新建文件夹");
            menu.dismiss();
          } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
          }
        });
    runOnMainSync(camera::finish);
    waitForIdleSync();
    CameraActivity again =
        (CameraActivity)
            startActivitySync(
                new Intent(context, CameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    waitForIdleSync();
    runOnMainSync(
        () -> {
          try {
            require(dialogField.get(again) == null, "第二次进入不重复显示首次引导");
          } catch (IllegalAccessException ex) {
            throw new AssertionError(ex);
          }
          again.finish();
        });
    waitForIdleSync();
    prefs.edit().putBoolean("scan_guide_seen", seen).commit();
  }

  private View findText(View root, String text) {
    if (root instanceof android.widget.TextView
        && text.contentEquals(((android.widget.TextView) root).getText())) return root;
    if (root instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void clickText(View root, String text) {
    View target = findText(root, text);
    if (target == null || !target.performClick())
      throw new AssertionError("Missing action: " + text);
  }

  private boolean clickDescription(View root, String text) {
    if (text.equals(String.valueOf(root.getContentDescription())) && root.performClick())
      return true;
    if (root instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++)
        if (clickDescription(group.getChildAt(i), text)) return true;
    }
    return false;
  }

  private void waitReady(String document, long timeout) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < end) {
      boolean done = true;
      for (DocumentStore.Page p : store.pages(document)) {
        if ("error".equals(p.status)) throw new AssertionError(p.error);
        if (!"ready".equals(p.status)) done = false;
      }
      if (done) return;
      SystemClock.sleep(100);
    }
    throw new AssertionError("Queue timeout: " + document);
  }

  private void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    pass(message);
  }

  private void pass(String message) {
    checks.put(message);
    Bundle event = new Bundle();
    event.putString("stream", "PASS " + message + "\n");
    sendStatus(0, event);
  }

  private String hash(File file) throws Exception {
    try (InputStream in = new FileInputStream(file)) {
      return hash(in);
    }
  }

  private String hash(InputStream in) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] bytes = new byte[16384];
    int count;
    while ((count = in.read(bytes)) != -1) digest.update(bytes, 0, count);
    StringBuilder out = new StringBuilder();
    for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b));
    return out.toString();
  }

  private void writeReport(JSONObject report) throws Exception {
    File file = new File(context.getExternalFilesDir(null), "integration-report.json");
    try (Writer writer =
        new OutputStreamWriter(
            new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
      writer.write(report.toString(2));
    }
  }
}
