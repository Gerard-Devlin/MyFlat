package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** PDF preparation, page ordering, export progress and the generated file screen. */
public final class ExportActivity extends Activity {
  private final ArrayList<String> ids = new ArrayList<>();
  private String name;
  private boolean grayscale, reverse, busy, tip = true;
  private int quality = 4;
  private Exporter.Format format;
  private File output;
  private TextView progressText;
  private ProgressBar progressBar;
  private TextView progressMessage;
  private java.util.concurrent.Future<?> exportTask;
  private static final String[] QUALITIES = {"最低", "低", "中等", "高", "最高"};

  static void open(
      Activity a, String title, List<DocumentStore.Page> pages, Exporter.Format format) {
    ArrayList<String> ids = new ArrayList<>();
    for (DocumentStore.Page p : pages) ids.add(p.id);
    a.startActivity(
        new Intent(a, ExportActivity.class)
            .putExtra("title", title)
            .putStringArrayListExtra("pages", ids)
            .putExtra("format", format.name()));
  }

  @Override
  public void onCreate(Bundle state) {
    AppAppearance.prepare(this);
    super.onCreate(state);
    if (state == null) {
      ids.addAll(getIntent().getStringArrayListExtra("pages"));
      name =
          getIntent().getStringExtra("title")
              + "_"
              + new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
      format = Exporter.Format.valueOf(getIntent().getStringExtra("format"));
      quality = getSharedPreferences("export", 0).getInt("quality", 4);
      grayscale = getSharedPreferences("export", 0).getBoolean("grayscale", false);
    } else {
      ids.addAll(state.getStringArrayList("pages"));
      name = state.getString("name");
      format = Exporter.Format.valueOf(state.getString("format"));
      quality = state.getInt("quality");
      grayscale = state.getBoolean("gray");
      reverse = state.getBoolean("reverse");
      String path = state.getString("output");
      if (path != null) output = new File(path);
    }
    FolderSecurity.requirePages(
        this,
        ids,
        () -> {
          if (output != null) finishScreen();
          else {
            build();
            if (format == Exporter.Format.ZIP) zipName();
          }
        },
        this::finish);
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putStringArrayList("pages", ids);
    out.putString("name", name);
    out.putString("format", format.name());
    out.putInt("quality", quality);
    out.putBoolean("gray", grayscale);
    out.putBoolean("reverse", reverse);
    if (output != null) out.putString("output", output.getPath());
  }

  private List<DocumentStore.Page> pages() {
    List<DocumentStore.Page> pages = new ArrayList<>();
    for (String id : ids) {
      DocumentStore.Page p = MyFlatApp.of(this).store.page(id);
      if (p != null) pages.add(p);
    }
    if (reverse) Collections.reverse(pages);
    return pages;
  }

  private String extension() {
    return format == Exporter.Format.PDF ? ".pdf" : format == Exporter.Format.ZIP ? ".zip" : ".jpg";
  }

  private void build() {
    LinearLayout root = Ui.root(this);
    LinearLayout header = Ui.row(this);
    header.addView(Ui.iconButton(this, "back", "返回", this::finish));
    TextView title = Ui.text(this, name + extension(), 20, Ui.INK);
    title.setBackgroundResource(R.drawable.myflat_dash_underline);
    AppAppearance.keepText(title);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setOnClickListener(v -> rename());
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
    titleParams.leftMargin = Ui.dp(this, 16);
    header.addView(title, titleParams);
    header.addView(Ui.iconButton(this, "edit", "设置文件名", this::rename));
    root.addView(header);
    LinearLayout settings = Ui.row(this);
    Ui.padding(settings, 8, 0);
    settings.addView(
        chip("色彩模式", grayscale ? "灰度" : "彩色", this::settings),
        new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
    settings.addView(
        chip("质量", QUALITIES[quality], this::settings),
        new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
    settings.addView(
        chip(
            "顺序",
            reverse ? "倒序" : "正序",
            () ->
                Ui.alert(this)
                    .setTitle("顺序")
                    .setSingleChoiceItems(
                        new String[] {"正序", "倒序"},
                        reverse ? 1 : 0,
                        (d, w) -> {
                          reverse = w == 1;
                          d.dismiss();
                          build();
                        })
                    .show()),
        new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
    settings.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(-2, Ui.dp(this, 40)));
    LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
    qualityParams.leftMargin = Ui.dp(this, 16);
    settings.getChildAt(1).setLayoutParams(qualityParams);
    ((LinearLayout) settings.getChildAt(2)).setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    if (format == Exporter.Format.PDF) root.addView(settings);
    FrameLayout center = new FrameLayout(this);
    root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(0xffeeeeee);
    LinearLayout grid = Ui.column(this);
    grid.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 80));
    TextView previewTitle = Ui.text(this, "预览", 12, Ui.INK);
    previewTitle.setGravity(Gravity.CENTER_VERTICAL);
    grid.addView(previewTitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));
    scroll.addView(grid);
    center.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    if (tip) {
      LinearLayout hint = Ui.row(this);
      hint.setBackground(Ui.shape(0xffffecb3, Ui.dp(this, 8)));
      hint.setElevation(Ui.dp(this, 4));
      Ui.padding(hint, 16, 0);
      TextView hintText = Ui.bold(this, "可通过拖动重新排列页面。", 12);
      hintText.setTextColor(0x99000000);
      hint.addView(hintText, new LinearLayout.LayoutParams(0, -2, 1));
      View close =
          Ui.iconButton(
              this,
              "close",
              "关闭提示",
              () -> {
                tip = false;
                build();
              });
      hint.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
      FrameLayout.LayoutParams hintParams =
          new FrameLayout.LayoutParams(-1, Ui.dp(this, 30), Gravity.TOP);
      hintParams.setMargins(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), 0);
      center.addView(hint, hintParams);
    }
    List<DocumentStore.Page> pages = pages();
    LinearLayout row = null;
    int width = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 40)) / 3;
    for (int i = 0; i <= pages.size(); i++) {
      if (i % 3 == 0) {
        row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        grid.addView(row);
      }
      LinearLayout cell = Ui.column(this);
      Ui.padding(cell, 4, 4);
      FrameLayout cover = new FrameLayout(this);
      cover.setBackgroundColor(Color.TRANSPARENT);
      if (i == pages.size()) {
        FrameLayout plus = new FrameLayout(this);
        plus.setBackground(Ui.shape(0xffdddddd, Ui.dp(this, 4)));
        ImageView addIcon = new ImageView(this);
        addIcon.setImageResource(R.drawable.myflat_export_add_page);
        plus.addView(
            addIcon,
            new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));
        cover.addView(
            plus, new FrameLayout.LayoutParams(Math.round(width * .71f), -1, Gravity.CENTER));
        cell.setContentDescription("添加页面");
        cell.setOnClickListener(v -> addPages());
      } else {
        DocumentStore.Page p = pages.get(i);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
        try {
          Ui.thumbnail(image, MyFlatApp.of(this).store.file(p.result));
        } catch (Exception ignored) {
        }
        cell.setContentDescription("导出第 " + (i + 1) + " 页");
        cell.setOnLongClickListener(
            v -> {
              v.startDragAndDrop(
                  ClipData.newPlainText("page", p.id), new View.DragShadowBuilder(v), p.id, 0);
              return true;
            });
        cell.setOnDragListener(
            (v, e) -> {
              if (e.getAction() == DragEvent.ACTION_DROP) {
                String id = (String) e.getLocalState();
                ArrayList<String> displayed = new ArrayList<>(ids);
                if (reverse) Collections.reverse(displayed);
                int at = displayed.indexOf(p.id);
                if (displayed.remove(id)) {
                  displayed.add(Math.max(0, Math.min(at, displayed.size())), id);
                  if (reverse) Collections.reverse(displayed);
                  ids.clear();
                  ids.addAll(displayed);
                  build();
                }
                return true;
              }
              return true;
            });
        cell.setOnClickListener(
            v ->
                Ui.alert(this)
                    .setItems(
                        new String[] {"查看页面", "移除此页"},
                        (d, w) -> {
                          if (w == 0)
                            startActivity(
                                new Intent(this, EditorActivity.class).putExtra("page", p.id));
                          else {
                            ids.remove(p.id);
                            build();
                          }
                        })
                    .show());
      }
      cell.addView(cover, new LinearLayout.LayoutParams(-1, width));
      TextView number = Ui.bold(this, i < pages.size() ? "" + (i + 1) : "", 12);
      number.setIncludeFontPadding(false);
      number.setGravity(Gravity.CENTER);
      Ui.padding(number, 0, 8);
      cell.addView(number);
      row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    if (row != null)
      for (int i = (pages.size() + 1) % 3; i > 0 && i < 3; i++)
        row.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
    FrameLayout footer = new FrameLayout(this);
    TextView create = Ui.bold(this, format == Exporter.Format.PDF ? "创建 PDF" : "创建 ZIP", 14);
    create.setTextColor(Color.WHITE);
    create.setGravity(Gravity.CENTER);
    create.setBackground(Ui.shape(Ui.GREEN, Ui.dp(this, 8)));
    create.setOnClickListener(v -> create());
    FrameLayout.LayoutParams createParams = new FrameLayout.LayoutParams(-1, -1);
    createParams.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
    footer.addView(create, createParams);
    FrameLayout.LayoutParams footerParams =
        new FrameLayout.LayoutParams(-1, Ui.dp(this, 64), Gravity.BOTTOM);
    footerParams.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
    center.addView(footer, footerParams);
  }

  private View chip(String title, String value, Runnable click) {
    LinearLayout row = Ui.row(this);
    row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    row.setPadding(0, 0, 0, Ui.dp(this, 4));
    row.addView(Ui.text(this, title + "  ", 14, 0xff8d8d93));
    TextView selected = Ui.text(this, value, 14, 0xff424242);
    selected.setBackgroundResource(R.drawable.myflat_dash_underline);
    row.addView(selected);
    row.setOnClickListener(v -> click.run());
    return row;
  }

  private void rename() {
    FilenamePresets.rename(
        this,
        format == Exporter.Format.PDF ? "PDF" : "ZIP",
        name,
        getIntent().getStringExtra("title"),
        chosen -> {
          name = chosen;
          build();
        });
  }

  private void zipName() {
    EditText input = Ui.input(this, name, "文件名称");
    LinearLayout body = Ui.column(this);
    Ui.padding(body, 24, 16);
    body.addView(input);
    Ui.alert(this)
        .setTitle("创建 ZIP")
        .setView(body)
        .setNegativeButton("取消", (d, w) -> finish())
        .setPositiveButton(
            "创建",
            (d, w) -> {
              name = Exporter.safeName(input.getText().toString());
              create();
            })
        .setOnCancelListener(d -> finish())
        .show();
  }

  // This platform-themed dialog draws the seek bar track and thumb directly.
  @android.annotation.SuppressLint("AppCompatCustomView")
  private void settings() {
    Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
    FrameLayout backdrop = new FrameLayout(this);
    LinearLayout card = Ui.column(this);
    card.setBackground(Ui.shape(Color.WHITE, Ui.dp(this, 8)));
    card.setClipToOutline(true);
    card.setElevation(Ui.dp(this, 24));
    FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -1);
    cardParams.setMargins(Ui.dp(this, 24), Ui.dp(this, 80), Ui.dp(this, 24), Ui.dp(this, 80));
    backdrop.addView(card, cardParams);
    ScrollView scroll = new ScrollView(this);
    LinearLayout body = Ui.column(this);
    body.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 16));
    scroll.addView(body);
    card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView title = Ui.bold(this, "PDF 设置", 20);
    title.setTextColor(0xff3a3a3c);
    body.addView(title);
    body.addView(Ui.spacer(this, 36));
    TextView modeTitle = Ui.bold(this, "色彩模式", 14);
    modeTitle.setTextColor(0xff3a3a3c);
    body.addView(modeTitle);
    body.addView(Ui.spacer(this, 16));
    LinearLayout modes = Ui.row(this);
    boolean[] gray = {grayscale};
    int[] q = {quality};
    FrameLayout[] images = new FrameLayout[2];
    TextView[] labels = new TextView[2];
    Runnable updateMode =
        () -> {
          for (int j = 0; j < 2; j++) {
            boolean selected = (j == 1) == gray[0];
            android.graphics.drawable.GradientDrawable border =
                Ui.shape(selected ? 0x3330bfa5 : Color.TRANSPARENT, Ui.dp(this, 2));
            if (selected) border.setStroke(Ui.dp(this, 2), Ui.GREEN);
            images[j].setForeground(border);
            labels[j].setTextColor(selected ? Ui.GREEN : Ui.INK);
            labels[j].setTypeface(
                android.graphics.Typeface.DEFAULT,
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
          }
        };
    for (int i = 0; i < 2; i++) {
      int at = i;
      LinearLayout choice = Ui.column(this);
      choice.setGravity(Gravity.CENTER_HORIZONTAL);
      FrameLayout image = new FrameLayout(this);
      ImageView sample = new ImageView(this);
      sample.setImageResource(i == 0 ? R.drawable.myflat_pdf_color : R.drawable.myflat_pdf_gray);
      image.addView(sample, new FrameLayout.LayoutParams(-1, -1));
      choice.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 48)));
      choice.addView(Ui.spacer(this, 8));
      TextView label = Ui.text(this, i == 0 ? "彩色" : "灰度", 14, Ui.INK);
      label.setGravity(Gravity.CENTER);
      choice.addView(label);
      images[i] = image;
      labels[i] = label;
      choice.setOnClickListener(
          v -> {
            gray[0] = at == 1;
            updateMode.run();
          });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 66), -2);
      lp.leftMargin = Ui.dp(this, i == 0 ? 4 : 16);
      modes.addView(choice, lp);
    }
    updateMode.run();
    body.addView(modes);
    body.addView(Ui.spacer(this, 32));
    TextView sizeTitle = Ui.bold(this, "质量 / 文件大小", 14);
    sizeTitle.setTextColor(0xff3a3a3c);
    body.addView(sizeTitle);
    body.addView(Ui.spacer(this, 16));
    TextView qualityText = Ui.text(this, QUALITIES[q[0]], 14, 0xff3a3a3c);
    qualityText.setGravity(Gravity.CENTER);
    body.addView(qualityText);
    TextView estimate = Ui.text(this, estimate(q[0]), 14, 0xff3a3a3c);
    estimate.setGravity(Gravity.CENTER);
    body.addView(estimate);
    body.addView(pdfSpacer(21.5f));
    SeekBar slider =
        new SeekBar(this) {
          private final android.graphics.Paint track =
              new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

          @Override
          protected synchronized void onDraw(android.graphics.Canvas canvas) {
            float left = getPaddingLeft(),
                right = getWidth() - getPaddingRight(),
                y = getHeight() / 2f;
            track.setColor(0xfff0f1f2);
            track.setStrokeWidth(Ui.dp(ExportActivity.this, 4));
            canvas.drawLine(left, y, right, y, track);
            track.setColor(0xffc5c6c8);
            track.setStrokeWidth(Ui.dp(ExportActivity.this, 1));
            for (int tick = 0; tick <= 4; tick++) {
              float x = left + (right - left) * tick / 4;
              canvas.drawLine(
                  x,
                  y - Ui.dp(ExportActivity.this, 4),
                  x,
                  y + Ui.dp(ExportActivity.this, 4),
                  track);
            }
            track.setColor(Ui.GREEN);
            canvas.drawCircle(
                left + (right - left) * getProgress() / 4, y, Ui.dp(ExportActivity.this, 9), track);
          }
        };
    slider.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
    slider.setMax(4);
    slider.setProgress(q[0]);
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int value, boolean user) {
            q[0] = value;
            qualityText.setText(QUALITIES[value]);
            estimate.setText(estimate(value));
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
    LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 18));
    sliderParams.setMargins(Ui.dp(this, 7), 0, Ui.dp(this, 7), 0);
    body.addView(slider, sliderParams);
    body.addView(pdfSpacer(10.5f));
    LinearLayout limits = Ui.row(this);
    Ui.padding(limits, 10, 0);
    limits.addView(Ui.text(this, "最小", 14, 0xff8d8d93), new LinearLayout.LayoutParams(0, -2, 1));
    limits.addView(Ui.text(this, "最大", 14, 0xff8d8d93));
    body.addView(limits);
    LinearLayout buttons = Ui.row(this);
    buttons.setGravity(Gravity.END);
    buttons.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20));
    TextView close = pdfButton("关闭", false, dialog::dismiss);
    buttons.addView(close, new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)));
    buttons.addView(new Space(this), new LinearLayout.LayoutParams(Ui.dp(this, 16), 1));
    buttons.addView(
        pdfButton(
            "确定",
            true,
            () -> {
              grayscale = gray[0];
              quality = q[0];
              getSharedPreferences("export", 0)
                  .edit()
                  .putBoolean("grayscale", grayscale)
                  .putInt("quality", quality)
                  .apply();
              dialog.dismiss();
              build();
            }),
        new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)));
    card.addView(buttons);
    Ui.dialogInsets(dialog, backdrop);
    dialog
        .getWindow()
        .setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
  }

  private View pdfSpacer(float height) {
    View view = new View(this);
    view.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, height)));
    return view;
  }

  private TextView pdfButton(String text, boolean positive, Runnable click) {
    TextView button = Ui.bold(this, text, 14);
    button.setTextColor(positive ? Color.WHITE : Ui.INK);
    button.setGravity(Gravity.CENTER);
    Ui.padding(button, 16, 12);
    if (positive) button.setBackground(Ui.shape(Ui.GREEN, Ui.dp(this, 8)));
    button.setOnClickListener(v -> click.run());
    return button;
  }

  private String estimate(int q) {
    long size = 0;
    for (DocumentStore.Page p : pages())
      try {
        size += MyFlatApp.of(this).store.file(p.result).length();
      } catch (Exception ignored) {
      }
    double factor = new double[] {.08, .18, .4, .7, 1}[q];
    return String.format(Locale.getDefault(), "大约 %.1f MB", size * factor / 1048576d);
  }

  private void addPages() {
    DocumentStore store = MyFlatApp.of(this).store;
    Set<Long> folders = new LinkedHashSet<>();
    for (String id : ids) {
      DocumentStore.Page page = store.page(id);
      DocumentStore.Document doc = page == null ? null : store.document(page.document);
      if (doc != null) folders.add(doc.folder);
    }
    List<DocumentStore.Page> candidates = new ArrayList<>();
    for (long folder : folders)
      if (FolderSecurity.isAccessible(this, folder))
        for (DocumentStore.Document doc : store.documents(folder, "", false))
          for (DocumentStore.Page page : store.pages(doc.id))
            if ("ready".equals(page.status)) candidates.add(page);
    ExportPageSelection.show(
        this,
        name,
        candidates,
        new ArrayList<>(ids),
        chosen -> {
          ids.clear();
          ids.addAll(chosen);
          build();
        });
  }

  private void create() {
    if (busy) return;
    List<DocumentStore.Page> pages = pages();
    if (pages.isEmpty()) {
      Toast.makeText(this, "请选择页面", Toast.LENGTH_SHORT).show();
      return;
    }
    busy = true;
    LinearLayout root = Ui.root(this);
    LinearLayout header = Ui.row(this);
    header.addView(Ui.iconButton(this, "close", "关闭", this::onBackPressed));
    root.addView(header);
    FrameLayout area = new FrameLayout(this);
    root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
    progressText = Ui.bold(this, "正在创建 " + (format == Exporter.Format.PDF ? "PDF" : "ZIP"), 24);
    progressText.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, -2);
    titleParams.setMargins(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
    area.addView(progressText, titleParams);
    com.airbnb.lottie.LottieAnimationView animation =
        new com.airbnb.lottie.LottieAnimationView(this);
    animation.setAnimation(R.raw.myflat_export_processing);
    animation.setRepeatCount(com.airbnb.lottie.LottieDrawable.INFINITE);
    animation.playAnimation();
    FrameLayout.LayoutParams animationParams =
        new FrameLayout.LayoutParams(
            Ui.dp(this, 100), Ui.dp(this, 100), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    area.addView(animation, animationParams);
    TextView percentage = Ui.bold(this, "0%", 58);
    percentage.setGravity(Gravity.CENTER);
    percentage.setTextColor(Ui.GREEN);
    FrameLayout.LayoutParams percentageParams = new FrameLayout.LayoutParams(-1, -2);
    area.addView(percentage, percentageParams);
    progressMessage = Ui.bold(this, "0 / " + pages.size(), 14);
    progressMessage.setGravity(Gravity.CENTER);
    progressMessage.setTextColor(0xffaaaaaa);
    FrameLayout.LayoutParams messageParams = new FrameLayout.LayoutParams(-1, -2);
    area.addView(progressMessage, messageParams);
    progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progressBar.setMax(pages.size());
    progressBar.setVisibility(View.GONE);
    area.addView(progressBar);
    area.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          int height = b - t;
          titleParams.topMargin = Math.max(Ui.dp(this, 32), Math.round(height * .18f));
          animationParams.topMargin = Math.round(height * .29f);
          percentageParams.topMargin = Math.round(height * .40f);
          messageParams.topMargin =
              percentageParams.topMargin + percentage.getHeight() + Ui.dp(this, 68);
          if (height != ob - ot) {
            progressText.setLayoutParams(titleParams);
            animation.setLayoutParams(animationParams);
            percentage.setLayoutParams(percentageParams);
            progressMessage.setLayoutParams(messageParams);
          }
        });
    exportTask =
        MyFlatApp.of(this)
            .io
            .submit(
                () -> {
                  try {
                    File file =
                        Exporter.create(
                            this,
                            name,
                            pages,
                            format,
                            false,
                            grayscale,
                            quality,
                            (done, total) ->
                                runOnUiThread(
                                    () -> {
                                      percentage.setText(done * 100 / Math.max(1, total) + "%");
                                      progressMessage.setText(done + " / " + total);
                                      progressBar.setProgress(done);
                                    }));
                    saveDownload(file);
                    runOnUiThread(
                        () -> {
                          output = file;
                          busy = false;
                          finishScreen();
                        });
                  } catch (Exception ex) {
                    runOnUiThread(
                        () -> {
                          busy = false;
                          if (isFinishing() || isDestroyed()) return;
                          build();
                          Ui.error(this, ex);
                        });
                  }
                });
  }

  private void saveDownload(File file) throws IOException {
    if (Build.VERSION.SDK_INT < 29) return;
    ContentValues values = new ContentValues();
    values.put(MediaStore.Downloads.DISPLAY_NAME, file.getName());
    values.put(MediaStore.Downloads.MIME_TYPE, mime());
    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MyFlat");
    values.put(MediaStore.Downloads.IS_PENDING, 1);
    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    if (uri == null) throw new IOException("无法保存文件");
    try (InputStream in = new FileInputStream(file);
        OutputStream out = getContentResolver().openOutputStream(uri)) {
      if (out == null) throw new IOException("无法保存文件");
      Exporter.copy(in, out);
    } catch (IOException ex) {
      getContentResolver().delete(uri, null, null);
      throw ex;
    }
    values.clear();
    values.put(MediaStore.Downloads.IS_PENDING, 0);
    getContentResolver().update(uri, values, null, null);
  }

  private String mime() {
    return format == Exporter.Format.PDF
        ? "application/pdf"
        : format == Exporter.Format.ZIP ? "application/zip" : "image/jpeg";
  }

  private void finishScreen() {
    LinearLayout root = Ui.root(this);
    LinearLayout header = Ui.row(this);
    header.addView(Ui.iconButton(this, "close", "关闭", this::finish));
    root.addView(header);
    LinearLayout body = Ui.column(this);
    body.setGravity(Gravity.CENTER_HORIZONTAL);
    Ui.padding(body, 32, 0);
    body.addView(Ui.spacer(this, 32));
    TextView title = Ui.bold(this, format == Exporter.Format.PDF ? "PDF已创建。" : "ZIP已创建。", 24);
    title.setGravity(Gravity.CENTER);
    body.addView(title);
    LinearLayout result = Ui.column(this);
    result.setGravity(Gravity.CENTER);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.myflat_export_done);
    result.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 126), Ui.dp(this, 126)));
    result.addView(Ui.spacer(this, 24));
    TextView location = Ui.text(this, "该文件存储在此处。", 16, Ui.MUTED);
    location.setGravity(Gravity.CENTER);
    result.addView(location);
    TextView filename =
        Ui.text(
            this,
            Build.VERSION.SDK_INT >= 29 ? "Download/MyFlat/" + output.getName() : output.getName(),
            16,
            Ui.GREEN);
    AppAppearance.keepText(filename);
    filename.setGravity(Gravity.CENTER);
    filename.setOnClickListener(v -> openFile());
    result.addView(filename);
    body.addView(result, new LinearLayout.LayoutParams(-1, 0, 1));
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout shareArea = Ui.column(this);
    Ui.padding(shareArea, 16, 16);
    shareArea.setBackground(Ui.shape(0xfff5f5f5, Ui.dp(this, 16)));
    shareArea.addView(Ui.bold(this, "分享", 16));
    shareArea.addView(Ui.spacer(this, 8));
    shareArea.addView(Ui.text(this, "请在符合版权的范围内使用它。", 14, Ui.MUTED));
    LinearLayout targets = Ui.row(this);
    Intent intent = shareIntent();
    List<android.content.pm.ResolveInfo> handlers =
        getPackageManager().queryIntentActivities(intent, 0);
    handlers.removeIf(handler -> getPackageName().equals(handler.activityInfo.packageName));
    for (int i = 0; i < 3; i++) {
      if (i >= handlers.size()) {
        targets.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
        continue;
      }
      android.content.pm.ResolveInfo handler = handlers.get(i);
      LinearLayout target = Ui.column(this);
      target.setGravity(Gravity.CENTER);
      Ui.padding(target, 8, 8);
      ImageView image = new ImageView(this);
      image.setImageDrawable(handler.loadIcon(getPackageManager()));
      target.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
      target.addView(Ui.spacer(this, 8));
      TextView name = Ui.text(this, handler.loadLabel(getPackageManager()).toString(), 12, Ui.INK);
      AppAppearance.keepText(name);
      name.setGravity(Gravity.CENTER | Gravity.TOP);
      name.setMaxLines(2);
      name.setMinLines(2);
      target.addView(name);
      target.setOnClickListener(
          v -> {
            try {
              startActivity(
                  shareIntent()
                      .setComponent(
                          new ComponentName(
                              handler.activityInfo.packageName, handler.activityInfo.name)));
            } catch (ActivityNotFoundException ex) {
              Ui.error(this, ex);
            }
          });
      targets.addView(target, new LinearLayout.LayoutParams(0, -2, 1));
    }
    targets.addView(
        Ui.action(this, "more", "更多", this::share),
        new LinearLayout.LayoutParams(0, Ui.dp(this, 104), 1));
    shareArea.addView(targets);
    LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(-1, -2);
    shareParams.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16));
    root.addView(shareArea, shareParams);
    LinearLayout footer = Ui.row(this);
    Ui.padding(footer, 16, 8);
    TextView done = pdfButton("完成", false, this::finish);
    done.setBackground(Ui.shape(0xfff5f5f5, Ui.dp(this, 8)));
    footer.addView(done, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
    footer.addView(new Space(this), new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
    footer.addView(
        pdfButton("打开文件", true, this::openFile),
        new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
    root.addView(footer);
  }

  private Intent shareIntent() {
    Uri uri = ShareProvider.uri(this, output);
    Intent intent =
        new Intent(Intent.ACTION_SEND)
            .setType(mime())
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newRawUri("MyFlat", uri));
    return intent;
  }

  private void share() {
    try {
      startActivity(Intent.createChooser(shareIntent(), "分享"));
    } catch (ActivityNotFoundException ex) {
      Ui.error(this, ex);
    }
  }

  private void openFile() {
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(ShareProvider.uri(this, output), mime())
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (ActivityNotFoundException ex) {
      Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 1 && result == RESULT_OK && data != null && data.getData() != null)
      MyFlatApp.of(this)
          .io
          .submit(
              () -> {
                try (InputStream in = new FileInputStream(output);
                    OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                  Exporter.copy(in, out);
                } catch (Exception ex) {
                  runOnUiThread(() -> Ui.error(this, ex));
                }
              });
  }

  @Override
  public void onBackPressed() {
    if (!busy) super.onBackPressed();
    else
      Ui.alert(this)
          .setMessage("退出后将取消创建文件。")
          .setNegativeButton("继续", null)
          .setPositiveButton(
              "退出",
              (d, w) -> {
                if (exportTask != null) exportTask.cancel(true);
                busy = false;
                finish();
              })
          .show();
  }
}
