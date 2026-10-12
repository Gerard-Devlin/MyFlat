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
    if (output != null) finishScreen();
    else {
      build();
      if (format == Exporter.Format.ZIP) zipName();
    }
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
                new AlertDialog.Builder(this)
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
        TextView plus = Ui.text(this, "+", 28, 0xffbdbdbd);
        plus.setBackground(Ui.shape(0xffdddddd, Ui.dp(this, 2)));
        plus.setGravity(Gravity.CENTER);
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
                new AlertDialog.Builder(this)
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
    LinearLayout body = Ui.column(this);
    Ui.padding(body, 24, 8);
    EditText input = Ui.input(this, name, "文件名称");
    android.graphics.drawable.GradientDrawable box = Ui.shape(Color.WHITE, Ui.dp(this, 4));
    box.setStroke(Ui.dp(this, 1), Ui.GREEN);
    input.setBackground(box);
    Ui.padding(input, 12, 12);
    body.addView(input);
    body.addView(Ui.spacer(this, 24));
    LinearLayout heading = Ui.row(this);
    heading.addView(Ui.bold(this, "常用文件名", 14), new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(
        Ui.iconButton(
            this,
            "edit",
            "编辑常用文件名",
            () ->
                Ui.rename(
                    this,
                    "常用文件名",
                    getSharedPreferences("export", 0).getString("presets", "扫描,报告,文档"),
                    text ->
                        getSharedPreferences("export", 0)
                            .edit()
                            .putString("presets", text)
                            .apply())));
    body.addView(heading);
    for (String preset :
        getSharedPreferences("export", 0).getString("presets", "扫描,报告,文档").split(",")) {
      TextView chip =
          Ui.pill(
              this,
              preset
                  + "_"
                  + new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date()),
              false,
              () ->
                  input.setText(
                      preset
                          + "_"
                          + new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT)
                              .format(new Date())));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.topMargin = Ui.dp(this, 8);
      body.addView(chip, lp);
    }
    new AlertDialog.Builder(this)
        .setTitle("设置 PDF 文件名")
        .setView(body)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确定",
            (d, w) -> {
              name = Exporter.safeName(input.getText().toString());
              build();
            })
        .show();
  }

  private void zipName() {
    EditText input = Ui.input(this, name, "文件名称");
    LinearLayout body = Ui.column(this);
    Ui.padding(body, 24, 16);
    body.addView(input);
    new AlertDialog.Builder(this)
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
    List<DocumentStore.Page> candidates = new ArrayList<>();
    for (DocumentStore.Document doc : MyFlatApp.of(this).store.documents(0, "", false))
      for (DocumentStore.Page p : MyFlatApp.of(this).store.pages(doc.id))
        if ("ready".equals(p.status) && !ids.contains(p.id)) candidates.add(p);
    if (candidates.isEmpty()) {
      Toast.makeText(this, "没有其他可添加的页面", Toast.LENGTH_SHORT).show();
      return;
    }
    boolean[] checked = new boolean[candidates.size()];
    String[] names = new String[candidates.size()];
    for (int i = 0; i < names.length; i++)
      names[i] =
          MyFlatApp.of(this).store.document(candidates.get(i).document).title
              + " · "
              + (candidates.get(i).position + 1);
    new AlertDialog.Builder(this)
        .setTitle("添加页面")
        .setMultiChoiceItems(names, checked, (d, w, b) -> checked[w] = b)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "添加",
            (d, w) -> {
              for (int i = 0; i < checked.length; i++)
                if (checked[i]) ids.add(candidates.get(i).id);
              build();
            })
        .show();
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
    LinearLayout area = Ui.column(this);
    area.setGravity(Gravity.CENTER);
    Ui.padding(area, 32, 32);
    progressText = Ui.bold(this, "正在创建 " + (format == Exporter.Format.PDF ? "PDF" : "ZIP"), 20);
    progressText.setGravity(Gravity.CENTER);
    area.addView(progressText);
    area.addView(Ui.spacer(this, 32));
    progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progressBar.setMax(pages.size());
    area.addView(progressBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 8)));
    root.addView(area, new LinearLayout.LayoutParams(-1, -1));
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
                                  progressText.setText("正在创建 " + done + " / " + total);
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
    header.addView(Ui.iconButton(this, "back", "返回", this::finish));
    header.addView(Ui.bold(this, format == Exporter.Format.PDF ? "创建 PDF" : "创建 ZIP", 20));
    root.addView(header);
    LinearLayout body = Ui.column(this);
    body.setGravity(Gravity.CENTER);
    Ui.padding(body, 32, 32);
    TextView title = Ui.bold(this, "文件已生成", 24);
    title.setGravity(Gravity.CENTER);
    body.addView(title);
    body.addView(Ui.spacer(this, 48));
    TextView check = Ui.text(this, "✓", 64, Ui.GREEN);
    check.setGravity(Gravity.CENTER);
    body.addView(check);
    body.addView(Ui.spacer(this, 24));
    TextView filename = Ui.text(this, output.getName(), 16, Ui.INK);
    filename.setGravity(Gravity.CENTER);
    body.addView(filename);
    TextView location =
        Ui.text(
            this, Build.VERSION.SDK_INT >= 29 ? "文件已保存到 下载 / MyFlat" : "保存文件到你的设备", 14, Ui.MUTED);
    location.setGravity(Gravity.CENTER);
    body.addView(location);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout shareArea = Ui.column(this);
    Ui.padding(shareArea, 24, 16);
    shareArea.setBackground(Ui.shape(Ui.PAPER, Ui.dp(this, 16)));
    shareArea.addView(Ui.bold(this, "分享", 16));
    shareArea.addView(Ui.spacer(this, 12));
    shareArea.addView(Ui.primary(this, "分享文件", this::share));
    if (Build.VERSION.SDK_INT < 29)
      shareArea.addView(
          Ui.pill(
              this,
              "保存到文件",
              false,
              () ->
                  startActivityForResult(
                      new Intent(Intent.ACTION_CREATE_DOCUMENT)
                          .setType(mime())
                          .addCategory(Intent.CATEGORY_OPENABLE)
                          .putExtra(Intent.EXTRA_TITLE, output.getName()),
                      1)));
    root.addView(shareArea);
    LinearLayout footer = Ui.row(this);
    Ui.padding(footer, 16, 16);
    footer.addView(
        Ui.pill(this, "完成", false, this::finish),
        new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1));
    footer.addView(new Space(this), new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
    footer.addView(
        Ui.primary(this, "打开文件", this::openFile),
        new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1));
    root.addView(footer);
  }

  private void share() {
    Uri uri = ShareProvider.uri(this, output);
    Intent intent =
        new Intent(Intent.ACTION_SEND)
            .setType(mime())
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newRawUri("MyFlat", uri));
    startActivity(Intent.createChooser(intent, "分享"));
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
  }
}
