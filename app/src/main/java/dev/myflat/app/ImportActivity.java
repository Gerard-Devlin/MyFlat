package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import dev.myflat.scanner.ImageMath;
import java.util.*;
import java.util.concurrent.Future;

/** Image preview, ordering, rotation and processing options before importing. */
public final class ImportActivity extends Activity {
  private final ArrayList<Uri> uris = new ArrayList<>();
  private final HashMap<String, Integer> rotations = new HashMap<>();
  private ScanSettings options;
  private int index;
  private ImageView image, previousImage, nextImage;
  private FrameLayout screen;
  private View progressOverlay;
  private ProgressBar importProgress;
  private TextView position;
  private LinearLayout effects;
  private Future<?> preview;
  private int generation;
  private float touchX;
  private boolean working;

  @Override
  public void onCreate(Bundle state) {
    AppAppearance.prepare(this);
    super.onCreate(state);
    if (state != null) {
      for (String value : state.getStringArrayList("uris")) uris.add(Uri.parse(value));
      index = state.getInt("index");
      options = ScanSettings.parse(state.getString("options"));
      for (Uri u : uris) rotations.put(u.toString(), state.getInt(u.toString()));
    } else {
      for (String value : getIntent().getStringArrayListExtra("uris")) uris.add(Uri.parse(value));
      options =
          ScanSettings.parse(
              getSharedPreferences("settings", 0).getString("import", "{\"dewarp\":false}"));
      options.doublePage = false;
    }
    if (uris.isEmpty()) {
      finish();
      return;
    }
    index = Math.max(0, Math.min(uris.size() - 1, index));
    build();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    ArrayList<String> list = new ArrayList<>();
    for (Uri u : uris) list.add(u.toString());
    out.putStringArrayList("uris", list);
    out.putInt("index", index);
    out.putString("options", options.json());
    for (Map.Entry<String, Integer> e : rotations.entrySet()) out.putInt(e.getKey(), e.getValue());
  }

  private void build() {
    LinearLayout root = Ui.root(this);
    LinearLayout header = Ui.row(this);
    header.addView(Ui.iconButton(this, "back", "返回", this::finish));
    TextView title = Ui.bold(this, "导入", 20);
    title.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
    titleParams.leftMargin = Ui.dp(this, 16);
    title.setTypeface(android.graphics.Typeface.DEFAULT);
    header.addView(title, titleParams);
    TextView done = Ui.bold(this, "完成", 14);
    done.setTextColor(Ui.GREEN);
    done.setGravity(Gravity.CENTER);
    header.addView(done, new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 56)));
    done.setOnClickListener(v -> chooseDestination());
    root.addView(header);
    header.setElevation(Ui.dp(this, 1));
    FrameLayout previewArea = new FrameLayout(this);
    previewArea.setBackgroundColor(0xfffafafa);
    image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int pageWidth =
        Math.max(
            1, Math.round(getResources().getDisplayMetrics().widthPixels * .9f) - Ui.dp(this, 40));
    FrameLayout.LayoutParams ip =
        new FrameLayout.LayoutParams(pageWidth, -1, Gravity.CENTER_HORIZONTAL);
    ip.setMargins(0, Ui.dp(this, 40), 0, Ui.dp(this, 56));
    previewArea.addView(image, ip);
    previousImage = adjacentImage(previewArea, pageWidth, -1);
    nextImage = adjacentImage(previewArea, pageWidth, 1);
    image.setOnTouchListener(
        (v, e) -> {
          if (e.getAction() == MotionEvent.ACTION_DOWN) {
            touchX = e.getX();
            return true;
          }
          if (e.getAction() == MotionEvent.ACTION_UP) {
            float dx = e.getX() - touchX;
            if (Math.abs(dx) > Ui.dp(this, 40)) {
              index = Math.max(0, Math.min(uris.size() - 1, index + (dx < 0 ? 1 : -1)));
              loadPreview();
            }
            v.performClick();
            return true;
          }
          return true;
        });
    position = Ui.bold(this, "", 14);
    position.setTextColor(Color.WHITE);
    position.setGravity(Gravity.CENTER);
    Ui.padding(position, 16, 4);
    position.setBackground(Ui.shape(0x8045484b, Ui.dp(this, 16)));
    FrameLayout.LayoutParams pp =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    pp.bottomMargin = Ui.dp(this, 72);
    previewArea.addView(position, pp);
    LinearLayout buttons = Ui.row(this);
    buttons.setGravity(Gravity.CENTER);
    buttons.addView(option("sort", "排序", this::sort));
    buttons.addView(new Space(this), new LinearLayout.LayoutParams(Ui.dp(this, 16), 1));
    buttons.addView(
        option(
            "rotate",
            "旋转",
            () -> {
              Uri u = uris.get(index);
              rotations.put(u.toString(), (rotations.getOrDefault(u.toString(), 0) + 270) % 360);
              loadPreview();
            }));
    FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, Ui.dp(this, 32), Gravity.BOTTOM);
    bp.bottomMargin = Ui.dp(this, 12);
    previewArea.addView(buttons, bp);
    root.addView(previewArea, new LinearLayout.LayoutParams(-1, 0, 1));
    effects = Ui.column(this);
    android.graphics.drawable.GradientDrawable effectBg = Ui.shape(Color.WHITE, 0);
    float effectRadius = Ui.dp(this, 16);
    effectBg.setCornerRadii(
        new float[] {effectRadius, effectRadius, effectRadius, effectRadius, 0, 0, 0, 0});
    effects.setBackground(effectBg);
    effects.setElevation(Ui.dp(this, 2));
    effects.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 32));
    root.addView(effects);
    renderEffects();
    screen = new FrameLayout(this);
    root.removeAllViews();
    LinearLayout body = Ui.column(this);
    body.addView(header);
    body.addView(previewArea, new LinearLayout.LayoutParams(-1, 0, 1));
    body.addView(effects);
    screen.addView(body, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout overlay = new FrameLayout(this);
    overlay.setBackgroundColor(Color.WHITE);
    overlay.setClickable(true);
    importProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    importProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
    importProgress.setMax(100);
    FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
    progressParams.leftMargin = progressParams.rightMargin = Ui.dp(this, 64);
    overlay.addView(importProgress, progressParams);
    overlay.setVisibility(View.GONE);
    progressOverlay = overlay;
    screen.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    root.addView(screen, new LinearLayout.LayoutParams(-1, -1));
    loadPreview();
  }

  private ImageView adjacentImage(FrameLayout parent, int width, int side) {
    ImageView adjacent = new ImageView(this);
    adjacent.setScaleType(ImageView.ScaleType.FIT_CENTER);
    adjacent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL);
    lp.setMargins(0, Ui.dp(this, 40), 0, Ui.dp(this, 56));
    parent.addView(adjacent, lp);
    adjacent.setTranslationX(side * (width + Ui.dp(this, 20)));
    return adjacent;
  }

  private View option(String icon, String label, Runnable action) {
    LinearLayout b = Ui.row(this);
    b.setGravity(Gravity.CENTER);
    b.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 22), Ui.dp(this, 6));
    android.graphics.drawable.GradientDrawable buttonBg = Ui.shape(Color.WHITE, Ui.dp(this, 16));
    buttonBg.setStroke(Ui.dp(this, 1), 0x80e4e5e6);
    b.setBackground(buttonBg);
    b.setElevation(Ui.dp(this, 1));
    b.addView(
        new Ui.Icon(this, icon), new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
    TextView labelView = Ui.text(this, label, 14, 0xff212121);
    LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
    labelParams.leftMargin = Ui.dp(this, 4);
    b.addView(labelView, labelParams);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void renderEffects() {
    effects.removeAllViews();
    TextView heading = Ui.bold(this, "要应用的效果", 16);
    heading.setPadding(0, 0, 0, Ui.dp(this, 4));
    effects.addView(heading);
    effects.addView(Ui.spacer(this, 10));
    effect(
        "自动裁剪",
        options.dewarp,
        true,
        () -> {
          options.dewarp = !options.dewarp;
          renderEffects();
        });
    effect(
        "拉直",
        options.straighten,
        options.dewarp,
        () -> {
          options.straighten = !options.straighten;
          renderEffects();
        });
    effect(
        "增强颜色",
        options.enhance,
        options.dewarp,
        () -> {
          options.enhance = !options.enhance;
          renderEffects();
        });
    effect(
        "去除手指",
        options.fingers,
        options.dewarp,
        () -> {
          options.fingers = !options.fingers;
          renderEffects();
        });
  }

  private void effect(String text, boolean checked, boolean enabled, Runnable action) {
    LinearLayout row = Ui.row(this);
    TextView title = Ui.bold(this, text, 14);
    row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView check = Ui.text(this, checked && enabled ? "✓" : "", 16, Color.WHITE);
    check.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable d =
        Ui.shape(checked && enabled ? Ui.GREEN : Color.TRANSPARENT, Ui.dp(this, 12));
    d.setStroke(Ui.dp(this, 1), checked && enabled ? Ui.GREEN : 0xffcccccc);
    check.setBackground(d);
    row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
    row.setAlpha(enabled ? 1 : .35f);
    row.setEnabled(enabled);
    row.setContentDescription(text + (checked && enabled ? "，已选中" : ""));
    row.setOnClickListener(v -> action.run());
    title.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
    LinearLayout.LayoutParams effectParams = new LinearLayout.LayoutParams(-1, -2);
    effectParams.bottomMargin = Ui.dp(this, 4);
    if (effects.getChildCount() > 2) effectParams.topMargin = Ui.dp(this, 4);
    effects.addView(row, effectParams);
  }

  private void loadPreview() {
    int token = ++generation;
    if (preview != null) preview.cancel(true);
    Uri uri = uris.get(index);
    position.setText((index + 1) + " / " + uris.size());
    image.setImageDrawable(null);
    previousImage.setVisibility(index > 0 ? View.VISIBLE : View.INVISIBLE);
    nextImage.setVisibility(index + 1 < uris.size() ? View.VISIBLE : View.INVISIBLE);
    if (index > 0) Ui.thumbnail(previousImage, uris.get(index - 1));
    if (index + 1 < uris.size()) Ui.thumbnail(nextImage, uris.get(index + 1));
    preview =
        MyFlatApp.of(this)
            .io
            .submit(
                () -> {
                  try {
                    Bitmap bitmap;
                    if ("application/pdf".equals(getContentResolver().getType(uri))) {
                      try (ParcelFileDescriptor fd =
                              getContentResolver().openFileDescriptor(uri, "r");
                          PdfRenderer pdf = new PdfRenderer(fd);
                          PdfRenderer.Page page = pdf.openPage(0)) {
                        float scale = 1000f / Math.max(page.getWidth(), page.getHeight());
                        bitmap =
                            Bitmap.createBitmap(
                                Math.max(1, Math.round(page.getWidth() * scale)),
                                Math.max(1, Math.round(page.getHeight() * scale)),
                                Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                      }
                    } else bitmap = Images.decode(this, uri, 1400);
                    int angle = rotations.getOrDefault(uri.toString(), 0);
                    Bitmap output = angle == 0 ? bitmap : ImageMath.rotate(bitmap, angle);
                    if (output != bitmap) bitmap.recycle();
                    runOnUiThread(
                        () -> {
                          if (token == generation && !isDestroyed()) image.setImageBitmap(output);
                          else output.recycle();
                        });
                  } catch (Exception ex) {
                    runOnUiThread(
                        () -> {
                          if (token == generation) Ui.error(this, ex);
                        });
                  }
                });
  }

  private void sort() {
    PageOrderDialog.show(
        this,
        "排序",
        uris,
        (image, uri) -> Ui.thumbnail(image, uri),
        order -> {
          Uri current = uris.get(index);
          uris.clear();
          uris.addAll(order);
          index = uris.indexOf(current);
          loadPreview();
        });
  }

  private void chooseDestination() {
    if (working) return;
    long folder = getIntent().getLongExtra("folder", 0);
    String document = getIntent().getStringExtra("document");
    if (document != null || folder > 0) importFiles(folder, document);
    else FolderPicker.show(this, 0, id -> importFiles(id, null));
  }

  private void importFiles(long folder, String document) {
    if (document != null)
      FolderSecurity.requireDocument(
          this, document, () -> importFilesAllowed(folder, document), () -> {});
    else FolderSecurity.require(this, folder, () -> importFilesAllowed(folder, null), () -> {});
  }

  private void importFilesAllowed(long folder, String document) {
    working = true;
    getSharedPreferences("settings", 0).edit().putString("import", options.json()).apply();
    if (document == null)
      document =
          MyFlatApp.of(this)
              .store
              .createDocument(
                  folder,
                  "扫描 "
                      + new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault())
                          .format(new Date()));
    String target = document;
    ScanSettings actual = ScanSettings.parse(options.json());
    if (!actual.dewarp) {
      actual.enhance = false;
      actual.fingers = false;
      actual.straighten = false;
    }
    progressOverlay.setVisibility(View.VISIBLE);
    progressOverlay.setContentDescription("正在导入");
    importProgress.setIndeterminate(true);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                int count =
                    Importer.importUris(
                        this,
                        target,
                        uris,
                        actual,
                        rotations,
                        message ->
                            runOnUiThread(() -> progressOverlay.setContentDescription(message)));
                runOnUiThread(
                    () -> {
                      progressOverlay.setVisibility(View.GONE);
                      setResult(
                          RESULT_OK,
                          new Intent().putExtra("folder", folder).putExtra("document", target));
                      finish();
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      working = false;
                      progressOverlay.setVisibility(View.GONE);
                      Ui.error(this, ex);
                    });
              }
            });
  }

  @Override
  protected void onDestroy() {
    generation++;
    if (preview != null) preview.cancel(true);
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (!working) super.onBackPressed();
  }
}
