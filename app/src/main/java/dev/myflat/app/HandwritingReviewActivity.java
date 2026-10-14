package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.Future;

/** Handwriting removal is previewed before the user chooses how to save it. */
public final class HandwritingReviewActivity extends Activity {
  private DocumentStore.Page page;
  private File output;
  private ComparisonView preview;
  private TextView message, save;
  private SeekBar compare;
  private Bitmap before, after;
  private Future<?> job;

  @Override
  public void onCreate(Bundle state) {
    AppAppearance.prepare(this);
    super.onCreate(state);
    String id = getIntent().getStringExtra("page");
    FolderSecurity.requirePage(
        this,
        id,
        () -> {
          page = MyFlatApp.of(this).store.page(id);
          build();
          process();
        },
        this::finish);
  }

  private void build() {
    LinearLayout root = Ui.root(this);
    LinearLayout head = Ui.row(this);
    head.addView(Ui.iconButton(this, "back", "返回", this::finish));
    TextView title = Ui.text(this, "擦除笔记", 20, Ui.INK);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
    hp.leftMargin = Ui.dp(this, 16);
    head.addView(title, hp);
    root.addView(head);
    preview = new ComparisonView();
    preview.setBackgroundColor(Ui.PAPER);
    root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
    message = Ui.text(this, "处理中…", 14, Ui.MUTED);
    message.setGravity(Gravity.CENTER);
    Ui.padding(message, 16, 8);
    root.addView(message);
    compare = new SeekBar(this);
    compare.setMax(100);
    compare.setProgress(100);
    compare.setEnabled(false);
    compare.setContentDescription("原始图像与擦除笔记结果对比");
    root.addView(compare);
    compare.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int n, boolean user) {
            updateComparison(n);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
    FrameLayout footer = new FrameLayout(this);
    save = Ui.bold(this, "保存", 14);
    save.setGravity(Gravity.CENTER);
    save.setTextColor(Color.WHITE);
    save.setBackground(Ui.shape(Ui.GREEN, Ui.dp(this, 8)));
    save.setEnabled(false);
    save.setAlpha(.4f);
    save.setOnClickListener(v -> savingMethods());
    FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, Ui.dp(this, 48));
    sp.setMargins(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 24));
    footer.addView(save, sp);
    root.addView(footer);
  }

  private void process() {
    job =
        MyFlatApp.of(this)
            .io
            .submit(
                () -> {
                  Bitmap full = null, processed = null;
                  try {
                    Bitmap originalPreview =
                        Images.decode(MyFlatApp.of(this).store.file(page.result), 1600);
                    runOnUiThread(
                        () -> {
                          if (isFinishing() || isDestroyed()) originalPreview.recycle();
                          else {
                            before = originalPreview;
                            preview.invalidate();
                          }
                        });
                    full = Images.decode(MyFlatApp.of(this).store.file(page.result), 0);
                    processed =
                        new dev.myflat.scanner.ScanEngine(this)
                            .removeHandwriting(
                                full, (stage, n) -> runOnUiThread(() -> message.setText(stage)));
                    if (Thread.currentThread().isInterrupted()) return;
                    File temporary = File.createTempFile("handwriting-", ".jpg", getCacheDir());
                    DocumentStore.saveJpeg(temporary, processed, 100);
                    Bitmap display = Images.decode(temporary, 1600);
                    runOnUiThread(
                        () -> {
                          if (isFinishing() || isDestroyed()) {
                            display.recycle();
                            temporary.delete();
                            return;
                          }
                          output = temporary;
                          after = display;
                          preview.invalidate();
                          message.setText("拖动滑杆查看原始图像与处理结果。");
                          compare.setEnabled(true);
                          save.setEnabled(true);
                          save.setAlpha(1);
                        });
                  } catch (Exception error) {
                    runOnUiThread(
                        () -> {
                          if (!isFinishing()) Ui.error(this, error);
                        });
                  } finally {
                    if (processed != null && processed != full) processed.recycle();
                    if (full != null) full.recycle();
                  }
                });
  }

  private void updateComparison(int position) {
    if (preview != null) {
      preview.position = position;
      preview.invalidate();
    }
  }

  private final class ComparisonView extends View {
    int position = 100;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    ComparisonView() {
      super(HandwritingReviewActivity.this);
      setContentDescription("原始图像与处理结果预览");
    }

    @Override
    protected void onDraw(Canvas c) {
      Bitmap image = after != null ? after : before;
      if (image == null || image.isRecycled()) return;
      float scale =
          Math.min((float) getWidth() / image.getWidth(), (float) getHeight() / image.getHeight());
      float width = image.getWidth() * scale, height = image.getHeight() * scale;
      RectF area =
          new RectF(
              (getWidth() - width) / 2,
              (getHeight() - height) / 2,
              (getWidth() + width) / 2,
              (getHeight() + height) / 2);
      c.drawBitmap(image, null, area, paint);
      if (after != null && before != null && !before.isRecycled() && position < 100) {
        float edge = area.left + area.width() * (100 - position) / 100f;
        c.save();
        c.clipRect(area.left, area.top, edge, area.bottom);
        c.drawBitmap(before, null, area, paint);
        c.restore();
        paint.setColor(Ui.GREEN);
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        c.drawLine(edge, area.top, edge, area.bottom, paint);
      }
    }
  }

  private void savingMethods() {
    Dialog d = new Dialog(this);
    d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout content = Ui.column(this);
    content.setBackground(Ui.shape(Color.WHITE, Ui.dp(this, 12)));
    TextView title = Ui.bold(this, "保存方法", 16);
    Ui.padding(title, 16, 16);
    content.addView(title);
    content.addView(
        savingRow(
            "plus",
            "保存为新页面",
            () -> {
              d.dismiss();
              saveResult(true);
            }));
    content.addView(
        savingRow(
            "check",
            "覆盖",
            () -> {
              d.dismiss();
              Ui.alert(this)
                  .setTitle("覆盖这个页面吗？")
                  .setMessage("覆盖将替换此页面。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton("覆盖", (dialog, which) -> saveResult(false))
                  .show();
            }));
    d.setContentView(content);
    d.show();
    d.getWindow()
        .setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    d.getWindow().setLayout(-1, -2);
    d.getWindow().setGravity(Gravity.BOTTOM);
    AppAppearance.dialog(d, content);
  }

  private View savingRow(String icon, String title, Runnable click) {
    LinearLayout row = Ui.row(this);
    Ui.padding(row, 16, 12);
    row.addView(
        new Ui.Icon(this, icon), new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
    TextView label = Ui.text(this, title, 16, Ui.INK);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.leftMargin = Ui.dp(this, 8);
    row.addView(label, lp);
    row.setBackgroundResource(android.R.drawable.list_selector_background);
    row.setOnClickListener(v -> click.run());
    return row;
  }

  private void saveResult(boolean copy) {
    save.setEnabled(false);
    ProgressDialog progress = ProgressDialog.show(this, "", "处理中…", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              Bitmap image = null;
              try {
                DocumentStore store = MyFlatApp.of(this).store;
                String id = page.id;
                image = Images.decode(output, 0);
                synchronized (store) {
                  if (copy) {
                    ScanSettings options = ScanSettings.parse(page.settings);
                    options.handwriting = true;
                    try (InputStream source = new FileInputStream(store.file(page.original))) {
                      id = store.addSource(page.document, source, options);
                    }
                  }
                  store.complete(store.page(id), image, 100);
                }
                String saved = id;
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      setResult(RESULT_OK, new Intent().putExtra("page", saved));
                      finish();
                    });
              } catch (Exception error) {
                runOnUiThread(
                    () -> {
                      progress.dismiss();
                      save.setEnabled(true);
                      Ui.error(this, error);
                    });
              } finally {
                if (image != null) image.recycle();
              }
            });
  }

  @Override
  protected void onDestroy() {
    if (job != null) job.cancel(true);
    if (output != null) output.delete();
    if (before != null) before.recycle();
    if (after != null) after.recycle();
    super.onDestroy();
  }
}
