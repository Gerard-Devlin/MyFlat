package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import dev.myflat.scanner.ImageMath;
import java.io.*;
import java.util.*;

/** Non-destructive original comparison; edits replace only the processed JPEG. */
public final class EditorActivity extends Activity {
  private DocumentStore store;
  private DocumentStore.Page page;
  private PageCanvas canvas;
  private Bitmap processed, original;
  private boolean comparing = false, dirty = false, registered = false;
  private float ratio = 0, cropRotation = 0;
  private int quarterRotation = 0;
  private Bitmap cropBase;
  private String pageId;
  private long folderContext;
  private final BroadcastReceiver changes =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          if (i.getIntExtra("progress", 0) == 100) load();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = MyFlatApp.of(this).store;
    pageId = state != null ? state.getString("page") : getIntent().getStringExtra("page");
    folderContext = state != null ? state.getLong("folder") : getIntent().getLongExtra("folder", 0);
    load();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("page", pageId);
    out.putLong("folder", folderContext);
  }

  @Override
  protected void onStart() {
    super.onStart();
    androidx.core.content.ContextCompat.registerReceiver(
        this,
        changes,
        new IntentFilter(ScanService.CHANGED),
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    registered = true;
  }

  @Override
  protected void onStop() {
    if (registered) {
      unregisterReceiver(changes);
      registered = false;
    }
    super.onStop();
  }

  private void load() {
    page = store.page(pageId);
    if (page == null) {
      finish();
      return;
    }
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                Bitmap result =
                    Images.decode(
                        store.file("ready".equals(page.status) ? page.result : page.original),
                        1600);
                Bitmap source = Images.decode(store.file(page.original), 1600);
                runOnUiThread(
                    () -> {
                      if (isFinishing() || isDestroyed()) {
                        result.recycle();
                        source.recycle();
                        return;
                      }
                      Bitmap previousResult = processed, previousOriginal = original;
                      processed = result;
                      original = source;
                      comparing = false;
                      dirty = false;
                      build(PageCanvas.Mode.VIEW);
                      if (previousResult != null && previousResult != result)
                        previousResult.recycle();
                      if (previousOriginal != null && previousOriginal != source)
                        previousOriginal.recycle();
                    });
              } catch (Exception ex) {
                runOnUiThread(() -> Ui.error(this, ex));
              }
            });
  }

  private void build(PageCanvas.Mode mode) {
    LinearLayout root = Ui.root(this);
    LinearLayout header = Ui.row(this);
    Ui.padding(header, 0, 0);
    header.addView(Ui.iconButton(this, "back", "返回", this::back));
    List<DocumentStore.Page> pages = new ArrayList<>();
    if (folderContext > 0) {
      List<DocumentStore.Document> documents = store.documents(folderContext, "", false);
      Collections.reverse(documents);
      for (DocumentStore.Document document : documents) pages.addAll(store.pages(document.id));
    } else pages.addAll(store.pages(page.document));
    int index = 0;
    for (int i = 0; i < pages.size(); i++) if (pages.get(i).id.equals(pageId)) index = i;
    final int at = index;
    if (mode == PageCanvas.Mode.VIEW) {
      LinearLayout labels = Ui.column(this);
      labels.setGravity(Gravity.CENTER_VERTICAL);
      DocumentStore.Document document = store.document(page.document);
      String title = document == null ? "默认文件夹" : document.title;
      if (document != null)
        for (DocumentStore.Folder f : store.folders()) if (f.id == document.folder) title = f.name;
      TextView name = Ui.bold(this, title, 16);
      name.setSingleLine(true);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      labels.addView(name);
      labels.addView(Ui.text(this, (index + 1) + " / " + pages.size(), 14, Ui.INK));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
      lp.leftMargin = Ui.dp(this, 16);
      header.addView(labels, lp);
      header.addView(Ui.iconButton(this, "compare", "原图对比", this::compare));
    } else {
      header.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
      if (mode == PageCanvas.Mode.CROP)
        header.addView(
            headerText(
                "重置",
                () -> {
                  cropRotation = 0;
                  ratio = 0;
                  canvas.image(processed);
                  canvas.mode(PageCanvas.Mode.CROP);
                  build(PageCanvas.Mode.CROP);
                }));
      header.addView(headerText("完成", this::saveEdit));
    }
    header.setElevation(Ui.dp(this, 1));
    root.addView(header);
    canvas = new PageCanvas(this);
    canvas.image(processed);
    canvas.mode(mode);
    if (mode == PageCanvas.Mode.VIEW) loadDetail(false);
    canvas.onSwipe = direction -> navigate(pages, at + direction);
    root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
    if (mode == PageCanvas.Mode.CROP) {
      cropBase = processed;
      cropRotation = 0;
      quarterRotation = 0;
      LinearLayout controls = Ui.column(this);
      controls.setGravity(Gravity.CENTER_HORIZONTAL);
      controls.setBackgroundColor(Color.WHITE);
      controls.addView(Ui.spacer(this, 32));
      TextView paper = Ui.text(this, "比例：自定义  ⌄", 13, Ui.INK);
      paper.setGravity(Gravity.CENTER);
      android.graphics.drawable.GradientDrawable outline = Ui.shape(Color.WHITE, Ui.dp(this, 18));
      outline.setStroke(Ui.dp(this, 1), 0xffaaaaaa);
      paper.setBackground(outline);
      controls.addView(paper, new LinearLayout.LayoutParams(Ui.dp(this, 140), Ui.dp(this, 32)));
      LinearLayout ratios = Ui.row(this);
      HorizontalScrollView ratioScroll = new HorizontalScrollView(this);
      ratioScroll.setHorizontalScrollBarEnabled(false);
      ratioScroll.addView(ratios);
      ratioScroll.setVisibility(View.GONE);
      String[] names = {"自定义", "长方形", "原版的", "标准", "A4", "Letter"};
      String[] labels = {
        "", "", processed.getWidth() + ":" + processed.getHeight(), "152:225", "210:297", "8.5:11"
      };
      float[] values = {
        0,
        -1,
        (float) processed.getWidth() / processed.getHeight(),
        152f / 225,
        210f / 297,
        8.5f / 11
      };
      String[] icons = {
        "myflat_crop_ic_custom",
        "myflat_crop_ic_rectangle",
        "myflat_crop_ic_original",
        "myflat_crop_ic_standard",
        "myflat_crop_ic_a4",
        "myflat_crop_ic_letter"
      };
      for (int n = 0; n < names.length; n++) {
        int selected = n;
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        Ui.padding(item, 12, 4);
        ImageView icon = new ImageView(this);
        int resource = getResources().getIdentifier(icons[n], "drawable", getPackageName());
        if (resource != 0) icon.setImageResource(resource);
        else icon.setImageResource(R.drawable.myflat_ic_crop);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.INK));
        item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 32)));
        TextView label = Ui.bold(this, names[n], 12);
        label.setGravity(Gravity.CENTER);
        Ui.padding(label, 0, 4);
        item.addView(label);
        TextView aspect = Ui.text(this, labels[n], 10, Ui.INK);
        aspect.setGravity(Gravity.CENTER);
        item.addView(aspect);
        item.setContentDescription(names[n] + "比例");
        item.setOnClickListener(
            v -> {
              ratio = values[selected];
              canvas.ratio(ratio);
              paper.setText("比例：" + names[selected] + "  ⌄");
              for (int j = 0; j < ratios.getChildCount(); j++)
                ratios
                    .getChildAt(j)
                    .setBackgroundColor(j == selected ? 0xffd5f3ee : Color.TRANSPARENT);
            });
        ratios.addView(item, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 88)));
      }
      controls.addView(Ui.spacer(this, 30));
      LinearLayout ruler = Ui.row(this);
      Ui.padding(ruler, 8, 0);
      ruler.addView(Ui.iconButton(this, "rotate_left", "向左旋转90度", () -> cropRotate(-90)));
      AngleRuler angle = new AngleRuler(this);
      ruler.addView(angle, new LinearLayout.LayoutParams(0, Ui.dp(this, 68), 1));
      angle.changed =
          value -> {
            cropRotation = value;
            applyCropRotation();
          };
      ruler.addView(Ui.iconButton(this, "rotate_right", "向右旋转90度", () -> cropRotate(90)));
      controls.addView(ruler);
      controls.addView(ratioScroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 88)));
      paper.setOnClickListener(
          v -> {
            boolean open = ratioScroll.getVisibility() != View.VISIBLE;
            ratioScroll.setVisibility(open ? View.VISIBLE : View.GONE);
            ruler.setVisibility(open ? View.GONE : View.VISIBLE);
          });
      root.addView(controls, new LinearLayout.LayoutParams(-1, Ui.dp(this, 210)));
    } else if (mode == PageCanvas.Mode.ERASE) {
      SeekBar size = new SeekBar(this);
      size.setMax(54);
      size.setProgress(10);
      Ui.padding(size, 20, 0);
      size.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
              canvas.brush(Ui.dp(EditorActivity.this, p + 6));
            }

            public void onStartTrackingTouch(SeekBar s) {}

            public void onStopTrackingTouch(SeekBar s) {}
          });
      root.addView(size);
      LinearLayout bar = Ui.row(this);
      action(bar, "back", "取消", () -> build(PageCanvas.Mode.VIEW));
      action(bar, "rotate", "撤销", canvas::undo);
      action(bar, "check", "保存", this::saveEdit);
      root.addView(bar);
    } else {
      root.addView(Ui.line(this));
      LinearLayout bar = Ui.row(this);
      Ui.padding(bar, 8, 0);
      ActionMenus.toolbar(this, bar, "editor", menuItems(), () -> build(PageCanvas.Mode.VIEW));
      root.addView(bar);
    }
  }

  private TextView headerText(String title, Runnable click) {
    TextView text = Ui.text(this, title, 14, Ui.INK);
    text.setGravity(Gravity.CENTER);
    text.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 56)));
    text.setOnClickListener(v -> click.run());
    return text;
  }

  private void loadDetail(boolean source) {
    try {
      canvas.fullResolution(
          store.file(source || !"ready".equals(page.status) ? page.original : page.result));
    } catch (IOException ex) {
      Ui.error(this, ex);
    }
  }

  private void compare() {
    comparing = !comparing;
    canvas.image(comparing ? original : processed);
    loadDetail(comparing);
  }

  private void cropRotate(int degrees) {
    quarterRotation = (quarterRotation + degrees + 360) % 360;
    Bitmap before = cropBase;
    cropBase = ImageMath.rotate(cropBase, degrees);
    if (before != processed && before != cropBase) before.recycle();
    applyCropRotation();
  }

  private void applyCropRotation() {
    Matrix rotate = new Matrix();
    rotate.postRotate(cropRotation);
    Bitmap image =
        Bitmap.createBitmap(
            cropBase, 0, 0, cropBase.getWidth(), cropBase.getHeight(), rotate, true);
    canvas.image(image);
    canvas.mode(PageCanvas.Mode.CROP);
    canvas.ratio(ratio);
  }

  private static final class AngleRuler extends View {
    interface Changed {
      void accept(float value);
    }

    Changed changed;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    float value, startX, startValue;

    AngleRuler(Context c) {
      super(c);
      setContentDescription("旋转微调，左右拖动调整角度");
    }

    @Override
    protected void onDraw(Canvas c) {
      float density = getResources().getDisplayMetrics().density;
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(12 * density);
      paint.setColor(0xff212121);
      for (int i = -30; i <= 30; i += 15)
        if (i != 0)
          c.drawText(
              i + "°",
              18 * density + (getWidth() - 36 * density) * (i + 30) / 60f,
              22 * density,
              paint);
      paint.setColor(0xffaaaaaa);
      for (int i = 0; i <= 30; i++)
        c.drawCircle(
            18 * density + (getWidth() - 36 * density) * i / 30f, 32 * density, 1 * density, paint);
      paint.setColor(Color.WHITE);
      c.drawRect(
          getWidth() / 2f - 25 * density,
          23 * density,
          getWidth() / 2f + 25 * density,
          45 * density,
          paint);
      paint.setColor(0xff212121);
      paint.setTextSize(16 * density);
      c.drawText(String.format(Locale.ROOT, "%.1f°", value), getWidth() / 2f, 38 * density, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
        startX = e.getX();
        startValue = value;
        return true;
      }
      if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
        value = Math.max(-30, Math.min(30, startValue + (e.getX() - startX) / getWidth() * 60));
        invalidate();
        return true;
      }
      if (e.getActionMasked() == MotionEvent.ACTION_UP) {
        if (changed != null) changed.accept(value);
        performClick();
        return true;
      }
      return true;
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }
  }

  private void action(LinearLayout row, String icon, String label, Runnable run) {
    row.addView(
        Ui.action(this, icon, label, run), new LinearLayout.LayoutParams(0, Ui.dp(this, 72), 1));
  }

  private void navigate(List<DocumentStore.Page> pages, int index) {
    if (index >= 0 && index < pages.size() && "ready".equals(pages.get(index).status)) {
      pageId = pages.get(index).id;
      load();
    }
  }

  private void rotate() {
    ProgressDialog progress = ProgressDialog.show(this, "", "正在旋转", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                Bitmap full = Images.decode(store.file(page.result), 0);
                Bitmap rotated = ImageMath.rotate(full, 90);
                if (rotated != full) full.recycle();
                store.complete(page, rotated, 100);
                rotated.recycle();
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      load();
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void saveEdit() {
    if (canvas.mode() == PageCanvas.Mode.ERASE) {
      PageCanvas editing = canvas;
      ProgressDialog progress = ProgressDialog.show(this, "", "正在保存", true, false);
      MyFlatApp.of(this)
          .io
          .submit(
              () -> {
                try {
                  Bitmap full = Images.decode(store.file(page.result), 0);
                  editing.applyBrushes(full);
                  store.complete(page, full, 100);
                  full.recycle();
                  runOnUiThread(
                      () -> {
                        progress.dismiss();
                        load();
                      });
                } catch (Exception ex) {
                  runOnUiThread(
                      () -> {
                        progress.dismiss();
                        Ui.error(this, ex);
                      });
                }
              });
      return;
    }
    float[] corners = canvas.corners();
    if (!Quad.valid(corners, 2)) {
      Toast.makeText(this, "请调整裁剪角点", Toast.LENGTH_SHORT).show();
      return;
    }
    ProgressDialog progress = ProgressDialog.show(this, "", "正在裁剪", true, false);
    Bitmap source = canvas.image();
    float chosenRatio = ratio;
    float angle = cropRotation + quarterRotation;
    int previewWidth = source.getWidth(), previewHeight = source.getHeight();
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                Bitmap full = Images.decode(store.file(page.result), 0);
                if (angle != 0) {
                  Matrix turn = new Matrix();
                  turn.postRotate(angle);
                  Bitmap oriented =
                      Bitmap.createBitmap(
                          full, 0, 0, full.getWidth(), full.getHeight(), turn, true);
                  if (oriented != full) full.recycle();
                  full = oriented;
                }
                float[] fullCorners = corners.clone();
                for (int i = 0; i < 8; i += 2) {
                  fullCorners[i] *= (float) full.getWidth() / previewWidth;
                  fullCorners[i + 1] *= (float) full.getHeight() / previewHeight;
                }
                Bitmap result = ImageMath.crop(full, fullCorners);
                full.recycle();
                if (chosenRatio > 0) {
                  Bitmap scaled =
                      Bitmap.createScaledBitmap(
                          result,
                          result.getWidth(),
                          Math.max(2, Math.round(result.getWidth() / chosenRatio)),
                          true);
                  if (scaled != result) result.recycle();
                  result = scaled;
                }
                store.complete(page, result, ScanSettings.parse(page.settings).quality);
                Bitmap output = result;
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      output.recycle();
                      load();
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void effects() {
    EffectsForm form = new EffectsForm(this, ScanSettings.parse(page.settings), false);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form.view);
    new AlertDialog.Builder(this)
        .setTitle("从原图重新处理")
        .setMessage("新的效果将替换当前裁剪和擦除结果。")
        .setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "应用",
            (d, w) -> {
              store.queue(page.id, form.value(), page.rotation);
              MyFlatApp.of(this).startProcessing();
              Toast.makeText(this, "正在重新处理，完成后自动更新", Toast.LENGTH_LONG).show();
              finish();
            })
        .show();
  }

  private void share() {
    ProgressDialog progress = ProgressDialog.show(this, "", "正在准备图片", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                File file =
                    Exporter.create(
                        this,
                        "扫描第" + (page.position + 1) + "页",
                        Collections.singletonList(store.page(page.id)),
                        Exporter.Format.JPEG,
                        false,
                        (d, t) -> {});
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      Uri uri = ShareProvider.uri(this, file);
                      Intent intent =
                          new Intent(Intent.ACTION_SEND)
                              .setType("image/jpeg")
                              .putExtra(Intent.EXTRA_STREAM, uri)
                              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                      intent.setClipData(ClipData.newRawUri("MyFlat", uri));
                      startActivity(Intent.createChooser(intent, "分享扫描图片"));
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void exportPdf() {
    ExportActivity.open(
        this, "扫描页面", Collections.singletonList(store.page(page.id)), Exporter.Format.PDF);
  }

  private List<ActionMenus.Item> menuItems() {
    return Arrays.asList(
        new ActionMenus.Item(
            "crop",
            "裁剪",
            () -> {
              ratio = 0;
              build(PageCanvas.Mode.CROP);
            }),
        new ActionMenus.Item("share", "分享", this::share),
        new ActionMenus.Item("pdf", "创建 PDF", this::exportPdf),
        new ActionMenus.Item("trash", "删除", this::deletePage),
        new ActionMenus.Item("rotate", "旋转", this::rotate),
        new ActionMenus.Item("effects", "增强颜色", this::effects),
        new ActionMenus.Item("erase", "擦除", () -> build(PageCanvas.Mode.ERASE)),
        new ActionMenus.Item("compare", "原图", this::compare));
  }

  private void more() {
    ActionMenus.show(this, "editor", menuItems(), () -> build(PageCanvas.Mode.VIEW));
  }

  private void deletePage() {
    new AlertDialog.Builder(this)
        .setMessage("删除本页和原图？")
        .setPositiveButton(
            "删除",
            (a, b) ->
                MyFlatApp.of(this)
                    .io
                    .submit(
                        () -> {
                          try {
                            store.deletePage(pageId);
                            runOnUiThread(this::finish);
                          } catch (IOException ex) {
                            runOnUiThread(() -> Ui.error(this, ex));
                          }
                        }))
        .setNegativeButton("取消", null)
        .show();
  }

  private void back() {
    if (canvas != null && canvas.mode() != PageCanvas.Mode.VIEW) build(PageCanvas.Mode.VIEW);
    else finish();
  }

  @Override
  public void onBackPressed() {
    back();
  }
}
