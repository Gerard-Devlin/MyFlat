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
  private Bitmap processed, original, paperBackground;
  private TextView editDone, editReset;
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
    AppAppearance.prepare(this);
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
    FolderSecurity.requirePage(this, pageId, this::loadAllowed, this::finish);
  }

  private void loadAllowed() {
    page = store.page(pageId);
    if (page == null || page.deleted != 0) {
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
      AppAppearance.keepText(name);
      name.setSingleLine(true);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      labels.addView(name);
      labels.addView(Ui.text(this, (index + 1) + " / " + pages.size(), 14, Ui.INK));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
      lp.leftMargin = Ui.dp(this, 16);
      header.addView(labels, lp);
      FrameLayout tag = new FrameLayout(this);
      ImageView tagImage = new ImageView(this);
      tagImage.setImageResource(tagResource(page.colorTag));
      tag.addView(
          tagImage, new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
      tag.setContentDescription("颜色标签");
      tag.setBackgroundResource(android.R.drawable.list_selector_background);
      tag.setOnClickListener(v -> colorTags(tag));
      header.addView(tag, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
    } else {
      if (mode == PageCanvas.Mode.ERASE) {
        TextView title = Ui.text(this, "擦除", 20, Ui.INK);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
        lp.leftMargin = Ui.dp(this, 16);
        header.addView(title, lp);
      } else header.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
      editReset = headerText("重置", () -> build(mode));
      header.addView(editReset);
      editDone = headerText("完成", this::saveEdit);
      header.addView(editDone);
      dirty = false;
      updateEditActions();
    }
    header.setElevation(Ui.dp(this, 1));
    root.addView(header);
    canvas = new PageCanvas(this);
    canvas.image(processed);
    canvas.mode(mode);
    canvas.onChanged =
        () -> {
          dirty = true;
          updateEditActions();
        };
    if (mode == PageCanvas.Mode.ERASE) canvas.paperBackground(paperBackground);
    if (mode == PageCanvas.Mode.VIEW) loadDetail(false);
    canvas.onSwipe = direction -> navigate(pages, at + direction);
    root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
    if (mode == PageCanvas.Mode.CROP) {
      cropBase = processed;
      cropRotation = 0;
      quarterRotation = 0;
      ratio = 0;
      FrameLayout controls = new FrameLayout(this);
      controls.setBackgroundColor(Color.WHITE);
      LinearLayout paper = Ui.row(this);
      paper.setGravity(Gravity.CENTER);
      paper.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), 0);
      TextView paperLabel = Ui.bold(this, "比例：自订", 13);
      paperLabel.setIncludeFontPadding(false);
      paper.addView(paperLabel);
      ImageView dropdown = new ImageView(this);
      dropdown.setImageResource(R.drawable.myflat_crop_arrow_down);
      LinearLayout.LayoutParams arrowParams =
          new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
      arrowParams.leftMargin = Ui.dp(this, 4);
      paper.addView(dropdown, arrowParams);
      android.graphics.drawable.GradientDrawable outline = Ui.shape(Color.WHITE, Ui.dp(this, 32));
      outline.setStroke(Ui.dp(this, 1), 0xff9e9e9e);
      paper.setBackground(outline);
      FrameLayout.LayoutParams paperParams =
          new FrameLayout.LayoutParams(
              -2, Ui.dp(this, 32), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
      paperParams.topMargin = Ui.dp(this, 24);
      controls.addView(paper, paperParams);
      LinearLayout ratios = Ui.row(this);
      ratios.setGravity(Gravity.CENTER_VERTICAL);
      HorizontalScrollView ratioScroll = new HorizontalScrollView(this);
      ratioScroll.setHorizontalScrollBarEnabled(false);
      ratioScroll.addView(ratios, new HorizontalScrollView.LayoutParams(-2, -1));
      ratioScroll.setVisibility(View.GONE);
      String[] names = {"自订", "长方形", "原版的", "标准", "A4", "Letter"};
      String[] labels = {"", "", "", "152:225", "210:297", "8.5:11"};
      float[] values = {
        0,
        -1,
        (float) processed.getWidth() / processed.getHeight(),
        152f / 225,
        210f / 297,
        8.5f / 11
      };
      int[] icons = {
        R.drawable.myflat_crop_ic_custom,
        R.drawable.myflat_crop_ic_rectangle,
        R.drawable.myflat_crop_ic_original,
        R.drawable.myflat_crop_ic_standard,
        R.drawable.myflat_crop_ic_a4,
        R.drawable.myflat_crop_ic_letter
      };
      List<ImageView> ratioIcons = new ArrayList<>();
      List<TextView> ratioLabels = new ArrayList<>(), ratioValues = new ArrayList<>();
      for (int n = 0; n < names.length; n++) {
        int selected = n;
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        Ui.padding(item, 12, 4);
        ImageView icon = new ImageView(this);
        icon.setImageResource(icons[n]);
        icon.setImageTintList(
            android.content.res.ColorStateList.valueOf(n == 0 ? Ui.GREEN : Ui.INK));
        item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 32)));
        TextView label = Ui.bold(this, names[n], 12);
        label.setTextColor(n == 0 ? Ui.GREEN : Ui.INK);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.topMargin = Ui.dp(this, 6);
        item.addView(label, labelParams);
        TextView aspect = Ui.text(this, labels[n], 10, n == 0 ? Ui.GREEN : 0xff757575);
        aspect.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aspectParams = new LinearLayout.LayoutParams(-2, -2);
        aspectParams.topMargin = Ui.dp(this, 2);
        item.addView(aspect, aspectParams);
        ratioIcons.add(icon);
        ratioLabels.add(label);
        ratioValues.add(aspect);
        item.setContentDescription(names[n] + "比例");
        item.setBackgroundResource(android.R.drawable.list_selector_background);
        item.setOnClickListener(
            v -> {
              ratio = values[selected];
              canvas.ratio(ratio);
              dirty = true;
              updateEditActions();
              paperLabel.setText("比例：" + names[selected]);
              for (int j = 0; j < ratioIcons.size(); j++) {
                int color = j == selected ? Ui.GREEN : Ui.INK;
                ratioIcons
                    .get(j)
                    .setImageTintList(android.content.res.ColorStateList.valueOf(color));
                ratioLabels.get(j).setTextColor(color);
                ratioValues.get(j).setTextColor(j == selected ? Ui.GREEN : 0xff757575);
              }
            });
        ratios.addView(item, new LinearLayout.LayoutParams(Ui.dp(this, 88), -1));
      }
      LinearLayout ruler = Ui.row(this);
      Ui.padding(ruler, 8, 0);
      ruler.addView(
          resourceButton(R.drawable.myflat_crop_rotate_right, "向右旋转90度", () -> cropRotate(90)));
      AngleRuler angle = new AngleRuler(this);
      ruler.addView(angle, new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1));
      angle.changed =
          value -> {
            cropRotation = value;
            applyCropRotation();
          };
      ruler.addView(
          resourceButton(R.drawable.myflat_crop_rotate_left, "向左旋转90度", () -> cropRotate(270)));
      FrameLayout.LayoutParams rulerParams =
          new FrameLayout.LayoutParams(-1, Ui.dp(this, 56), Gravity.BOTTOM);
      rulerParams.bottomMargin = Ui.dp(this, 24);
      controls.addView(ruler, rulerParams);
      FrameLayout.LayoutParams ratioParams = new FrameLayout.LayoutParams(-1, -1);
      ratioParams.topMargin = Ui.dp(this, 68);
      controls.addView(ratioScroll, ratioParams);
      paper.setOnClickListener(
          v -> {
            boolean open = ratioScroll.getVisibility() != View.VISIBLE;
            ratioScroll.setVisibility(open ? View.VISIBLE : View.GONE);
            ruler.setVisibility(open ? View.GONE : View.VISIBLE);
            dropdown.setRotation(open ? 180 : 0);
          });
      root.addView(controls, new LinearLayout.LayoutParams(-1, Ui.dp(this, 210)));
      root.post(
          () -> {
            android.view.ViewGroup.LayoutParams lp = controls.getLayoutParams();
            lp.height =
                Math.max(
                    Ui.dp(this, 168),
                    Math.round(
                        (root.getHeight() - root.getPaddingTop() - root.getPaddingBottom())
                            * .25f));
            controls.setLayoutParams(lp);
          });
    } else if (mode == PageCanvas.Mode.ERASE) {
      LinearLayout controls = Ui.column(this);
      controls.setGravity(Gravity.CENTER_HORIZONTAL);
      controls.setBackgroundColor(Color.WHITE);
      SeekBar size = new SeekBar(this);
      size.setMax(1000);
      float thickness = getSharedPreferences("preferences", 0).getFloat("cleanup_thickness", 24);
      size.setProgress(Math.round((thickness - 12) / 36 * 1000));
      size.setProgressDrawable(getDrawable(R.drawable.myflat_cleanup_track));
      size.setThumb(getDrawable(R.drawable.myflat_cleanup_thumb));
      size.setThumbTintList(null);
      canvas.brush(Ui.dp(this, thickness / 2));
      LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
      seekParams.leftMargin = seekParams.rightMargin = Ui.dp(this, 46);
      controls.addView(size, seekParams);
      LinearLayout undoBar = Ui.row(this);
      undoBar.setGravity(Gravity.CENTER);
      View undo = resourceButton(R.drawable.myflat_cleanup_undo, "撤销", canvas::undo);
      View redo = resourceButton(R.drawable.myflat_cleanup_redo, "重做", canvas::redo);
      undoBar.addView(undo);
      undoBar.addView(new Space(this), new LinearLayout.LayoutParams(Ui.dp(this, 16), 1));
      undoBar.addView(redo);
      controls.addView(undoBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
      root.addView(controls, new LinearLayout.LayoutParams(-1, Ui.dp(this, 100)));
      Runnable refresh =
          () -> {
            dirty = canvas.hasUndo();
            undo.setEnabled(canvas.hasUndo());
            undo.setAlpha(canvas.hasUndo() ? 1 : .3f);
            redo.setEnabled(canvas.hasRedo());
            redo.setAlpha(canvas.hasRedo() ? 1 : .3f);
            updateEditActions();
          };
      canvas.onChanged = refresh;
      refresh.run();
      size.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seek, int p, boolean user) {
              float value = 12 + p * 36 / 1000f;
              canvas.brush(Ui.dp(EditorActivity.this, value / 2));
              if (user)
                getSharedPreferences("preferences", 0)
                    .edit()
                    .putFloat("cleanup_thickness", value)
                    .apply();
            }

            public void onStartTrackingTouch(SeekBar seek) {
              canvas.showBrushPreview(true);
            }

            public void onStopTrackingTouch(SeekBar seek) {
              canvas.showBrushPreview(false);
            }
          });
    } else {
      root.addView(Ui.line(this));
      LinearLayout bar = Ui.row(this);
      Ui.padding(bar, 8, 0);
      ActionMenus.toolbar(this, bar, "editor", menuItems(), () -> build(PageCanvas.Mode.VIEW));
      root.addView(bar);
    }
  }

  private void updateEditActions() {
    if (editDone != null) {
      editDone.setEnabled(dirty);
      editDone.setTextColor(dirty ? Ui.GREEN : 0xff9e9e9e);
    }
    if (editReset != null) {
      editReset.setEnabled(dirty);
      editReset.setTextColor(dirty ? Ui.INK : 0xff9e9e9e);
    }
  }

  private View resourceButton(int resource, String label, Runnable action) {
    FrameLayout frame = new FrameLayout(this);
    ImageView icon = new ImageView(this);
    icon.setImageResource(resource);
    frame.addView(
        icon, new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
    frame.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
    frame.setContentDescription(label);
    frame.setBackgroundResource(android.R.drawable.list_selector_background);
    frame.setOnClickListener(v -> action.run());
    return frame;
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
    dirty = true;
    updateEditActions();
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

  private void rotate(int degrees) {
    ProgressDialog progress = ProgressDialog.show(this, "", "正在旋转", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                Bitmap full = Images.decode(store.file(page.result), 0);
                Bitmap rotated = ImageMath.rotate(full, degrees);
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
    Ui.alert(this)
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
        new ActionMenus.Item("crop", "裁剪", () -> build(PageCanvas.Mode.CROP)),
        new ActionMenus.Item("share", "分享", this::share),
        new ActionMenus.Item("pdf", "创建 PDF", this::exportPdf),
        new ActionMenus.Item("trash", "删除", this::deletePage),
        new ActionMenus.Item("move", "移动", this::movePage),
        new ActionMenus.Item("gallery", "保存到图库", this::saveGallery),
        new ActionMenus.Item("rescan", "重新扫描", this::rescan),
        new ActionMenus.Item("erase", "擦除", this::beginErase),
        new ActionMenus.Item(
            "zip",
            "创建 ZIP",
            () ->
                ExportActivity.open(
                    this,
                    "扫描页面",
                    Collections.singletonList(store.page(page.id)),
                    Exporter.Format.ZIP)),
        new ActionMenus.Item("handwriting", "擦除笔记", this::removeHandwriting),
        new ActionMenus.Item("rotate_left", "向左旋转", () -> rotate(-90)),
        new ActionMenus.Item("rotate_right", "向右旋转", () -> rotate(90)));
  }

  private int tagResource(int color) {
    return switch (color) {
      case 0xffe57373 -> R.drawable.myflat_tag_red;
      case 0xffffd54f -> R.drawable.myflat_tag_yellow;
      case 0xff81c784 -> R.drawable.myflat_tag_green;
      case 0xff90caf9 -> R.drawable.myflat_tag_blue;
      case 0xffce93d8 -> R.drawable.myflat_tag_purple;
      default -> R.drawable.myflat_tag_off;
    };
  }

  private void colorTags(View anchor) {
    int[] colors = {0, 0xffe57373, 0xffffd54f, 0xff81c784, 0xff90caf9, 0xffce93d8};
    String[] labels = {"无标签", "红色标签", "黄色标签", "绿色标签", "蓝色标签", "紫色标签"};
    LinearLayout content = Ui.row(this);
    Ui.padding(content, 4, 8);
    android.graphics.drawable.GradientDrawable bg = Ui.shape(Color.WHITE, Ui.dp(this, 8));
    content.setBackground(bg);
    PopupWindow popup = new PopupWindow(content, Ui.dp(this, 296), Ui.dp(this, 64), true);
    popup.setBackgroundDrawable(bg);
    popup.setElevation(Ui.dp(this, 8));
    for (int i = 0; i < colors.length; i++) {
      int color = colors[i];
      FrameLayout choice = new FrameLayout(this);
      ImageView icon = new ImageView(this);
      icon.setImageResource(color == 0 ? R.drawable.myflat_tag_none : tagResource(color));
      choice.addView(
          icon, new FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28), Gravity.CENTER));
      choice.setContentDescription(labels[i]);
      choice.setBackgroundResource(android.R.drawable.list_selector_background);
      choice.setOnClickListener(
          v -> {
            store.setColorTag(page.id, color);
            page.colorTag = color;
            popup.dismiss();
            ((ImageView) ((FrameLayout) anchor).getChildAt(0)).setImageResource(tagResource(color));
          });
      content.addView(choice, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
    }
    popup.showAsDropDown(anchor, -Ui.dp(this, 248), 0, Gravity.END);
  }

  private void movePage() {
    DocumentStore.Document document = store.document(page.document);
    FolderPicker.show(
        this,
        document == null ? 1 : document.folder,
        id -> {
          FolderSecurity.require(
              this,
              id,
              () -> {
                store.movePages(Collections.singletonList(page.id), id);
                finish();
              },
              null);
        });
  }

  private void saveGallery() {
    if (Build.VERSION.SDK_INT < 29
        && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 601);
      return;
    }
    ProgressDialog progress = ProgressDialog.show(this, "", "正在保存", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                Gallery.save(this, store.file(page.result));
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      Toast.makeText(this, "保存到图库", Toast.LENGTH_SHORT).show();
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

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 601
        && results.length > 0
        && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) saveGallery();
  }

  private void rescan() {
    DocumentStore.Document document = store.document(page.document);
    startActivity(
        new Intent(this, CameraActivity.class)
            .putExtra("rescan_page", page.id)
            .putExtra("document", page.document)
            .putExtra("folder", document == null ? 1 : document.folder));
  }

  private void beginErase() {
    ProgressDialog progress = ProgressDialog.show(this, "", "处理中…", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                Bitmap paper = new dev.myflat.scanner.ScanEngine(this).paperBackground(processed);
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      if (isFinishing() || isDestroyed()) {
                        paper.recycle();
                        return;
                      }
                      if (paperBackground != null) paperBackground.recycle();
                      paperBackground = paper;
                      build(PageCanvas.Mode.ERASE);
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

  private void removeHandwriting() {
    startActivityForResult(
        new Intent(this, HandwritingReviewActivity.class).putExtra("page", page.id), 602);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 602 && result == RESULT_OK && data != null) {
      pageId = data.getStringExtra("page");
      load();
    }
  }

  private void more() {
    ActionMenus.show(this, "editor", menuItems(), () -> build(PageCanvas.Mode.VIEW));
  }

  private void deletePage() {
    Ui.alert(this)
        .setMessage("您确定要将 1 个页面移至废纸篓吗？")
        .setPositiveButton(
            "移到废纸篓",
            (a, b) ->
                MyFlatApp.of(this)
                    .io
                    .submit(
                        () -> {
                          try {
                            store.trashPages(Collections.singletonList(pageId));
                            runOnUiThread(this::finish);
                          } catch (Exception ex) {
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
