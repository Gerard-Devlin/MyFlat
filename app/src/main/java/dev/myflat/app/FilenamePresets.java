package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** Persistent filename formats, date chips, preview and drag ordering. */
final class FilenamePresets {
  private static final String[] TOKENS = {"FOLDERNAME", "YYYY", "YY", "MM", "DD", "_"};

  private static CharSequence display(Activity a, String format) {
    SpannableString text = new SpannableString(format);
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\{(FOLDERNAME|YYYY|YY|MM|DD)\\}").matcher(format);
    while (matcher.find())
      text.setSpan(
          new TokenSpan(a, matcher.group(1)),
          matcher.start(),
          matcher.end(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return text;
  }

  private static void decorate(Activity a, Editable text) {
    for (TokenSpan span : text.getSpans(0, text.length(), TokenSpan.class)) text.removeSpan(span);
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\{(FOLDERNAME|YYYY|YY|MM|DD)\\}").matcher(text);
    while (matcher.find())
      text.setSpan(
          new TokenSpan(a, matcher.group(1)),
          matcher.start(),
          matcher.end(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private static final class TokenSpan extends android.text.style.ReplacementSpan {
    final Activity a;
    final String label;

    TokenSpan(Activity a, String token) {
      this.a = a;
      label = token.equals("FOLDERNAME") ? AppAppearance.text(a, "文件夹名称") : token;
    }

    @Override
    public int getSize(
        android.graphics.Paint p,
        CharSequence t,
        int st,
        int en,
        android.graphics.Paint.FontMetricsInt fm) {
      return Math.round(p.measureText(label)) + Ui.dp(a, 16);
    }

    @Override
    public void draw(
        android.graphics.Canvas c,
        CharSequence t,
        int st,
        int en,
        float x,
        int top,
        int y,
        int bottom,
        android.graphics.Paint p) {
      int old = p.getColor();
      float width = p.measureText(label) + Ui.dp(a, 16);
      p.setColor(AppAppearance.color(a, 0xffe0e0e0));
      c.drawRoundRect(
          x, top + Ui.dp(a, 2), x + width, bottom - Ui.dp(a, 2), Ui.dp(a, 8), Ui.dp(a, 8), p);
      p.setColor(AppAppearance.color(a, 0xff424242));
      c.drawText(label, x + Ui.dp(a, 8), y, p);
      p.setColor(old);
    }
  }

  private static List<String> load(Activity a) {
    List<String> values = new ArrayList<>();
    String json = a.getSharedPreferences("export", 0).getString("formats", null);
    if (json != null)
      try {
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
      } catch (JSONException ignored) {
      }
    String previous = a.getSharedPreferences("export", 0).getString("presets", null);
    if (previous != null) {
      for (String value : previous.split(","))
        if (!value.trim().isEmpty()) values.add(value.trim() + "_{YYYY}{MM}{DD}");
    } else {
      values.add("{FOLDERNAME}_{YYYY}{MM}{DD}");
      values.add("John Doe_{YYYY}{MM}{DD}");
      values.add("Report_{YYYY}{MM}{DD}");
    }
    return values;
  }

  private static void save(Activity a, List<String> values) {
    a.getSharedPreferences("export", 0)
        .edit()
        .putString("formats", new JSONArray(values).toString())
        .apply();
  }

  static String render(String value, String folder) {
    Calendar now = Calendar.getInstance();
    return value
        .replace("{FOLDERNAME}", folder)
        .replace("{YYYY}", String.format(Locale.ROOT, "%04d", now.get(Calendar.YEAR)))
        .replace("{YY}", String.format(Locale.ROOT, "%02d", now.get(Calendar.YEAR) % 100))
        .replace("{MM}", String.format(Locale.ROOT, "%02d", now.get(Calendar.MONTH) + 1))
        .replace("{DD}", String.format(Locale.ROOT, "%02d", now.get(Calendar.DAY_OF_MONTH)));
  }

  static String validate(String name) {
    if (name.trim().isEmpty()) return "请输入文件名";
    if (name.length() > 256) return "输入的字符数已超过限制。";
    if (name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || name.equals(".") || name.equals(".."))
      return "无效的文件名";
    return null;
  }

  private static TextView button(Activity a, String text, boolean primary, Runnable click) {
    TextView view = Ui.bold(a, text, 14);
    view.setGravity(Gravity.CENTER);
    Ui.padding(view, 16, 12);
    view.setTextColor(primary ? Color.WHITE : Ui.INK);
    if (primary) view.setBackground(Ui.shape(Ui.GREEN, Ui.dp(a, 8)));
    view.setOnClickListener(v -> click.run());
    return view;
  }

  private static View chips(Activity a, EditText input) {
    HorizontalScrollView scroll = new HorizontalScrollView(a);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout row = Ui.row(a);
    Ui.padding(row, 8, 8);
    row.setBackgroundColor(0xfff5f5f5);
    for (String token : TOKENS) {
      String label = token.equals("FOLDERNAME") ? "文件夹名称" : token;
      TextView chip = Ui.text(a, label, 13, 0xff616161);
      Ui.padding(chip, 8, 4);
      chip.setBackground(Ui.shape(0xffe0e0e0, Ui.dp(a, 12)));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
      cp.rightMargin = Ui.dp(a, 8);
      row.addView(chip, cp);
      chip.setOnClickListener(
          v -> {
            int start = Math.max(0, input.getSelectionStart()),
                end = Math.max(start, input.getSelectionEnd());
            input.getText().replace(start, end, token.equals("_") ? "_" : "{" + token + "}");
          });
    }
    scroll.addView(row);
    return scroll;
  }

  static void rename(
      Activity a, String kind, String initial, String folder, Consumer<String> result) {
    Dialog d = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(Color.TRANSPARENT);
    FrameLayout center = new FrameLayout(a);
    root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout card = Ui.column(a);
    Ui.padding(card, 16, 20);
    card.setBackground(Ui.shape(Color.WHITE, Ui.dp(a, 12)));
    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
    cp.setMargins(Ui.dp(a, 30), Ui.dp(a, 16), Ui.dp(a, 30), Ui.dp(a, 16));
    center.addView(card, cp);
    TextView title = Ui.bold(a, "设置" + kind + "的名称", 18);
    card.addView(title);
    card.addView(Ui.spacer(a, 16));
    LinearLayout field = Ui.row(a);
    android.graphics.drawable.GradientDrawable box = Ui.shape(0xfffafafa, Ui.dp(a, 8));
    box.setStroke(Ui.dp(a, 2), Ui.GREEN);
    field.setBackground(box);
    EditText input = Ui.input(a, initial, "请输入文件名");
    input.setTextSize(14);
    input.setBackgroundColor(Color.TRANSPARENT);
    Ui.padding(input, 12, 12);
    field.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(a, 54), 1));
    field.addView(Ui.iconButton(a, "close", "清除文本", () -> input.setText("")));
    card.addView(field);
    TextView error = Ui.text(a, "", 12, 0xffe53935);
    error.setVisibility(View.GONE);
    card.addView(error);
    card.addView(Ui.spacer(a, 12));
    LinearLayout heading = Ui.row(a);
    heading.addView(Ui.text(a, "经常使用的文件名", 12, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout presets = Ui.column(a);
    Runnable refresh =
        () -> {
          presets.removeAllViews();
          List<String> formats = load(a);
          for (int i = 0; i < Math.min(3, formats.size()); i++) {
            String format = formats.get(i);
            TextView view = Ui.text(a, format, 14, Ui.INK);
            AppAppearance.keepText(view);
            view.setText(display(a, format));
            Ui.padding(view, 8, 8);
            view.setBackground(Ui.shape(0xfff5f5f5, Ui.dp(a, 14)));
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
            vp.bottomMargin = Ui.dp(a, 8);
            presets.addView(view, vp);
            view.setOnClickListener(v -> input.setText(format));
          }
          if (formats.isEmpty()) presets.addView(Ui.text(a, "没有经常使用的文件名。", 14, 0xffbdbdbd));
        };
    heading.addView(button(a, "编辑", false, () -> manage(a, folder, refresh)));
    card.addView(heading);
    card.addView(Ui.spacer(a, 8));
    card.addView(presets);
    refresh.run();
    card.addView(Ui.spacer(a, 30));
    LinearLayout actions = Ui.row(a);
    actions.setGravity(Gravity.END);
    actions.addView(
        button(a, "取消", false, d::dismiss),
        new LinearLayout.LayoutParams(Ui.dp(a, 80), Ui.dp(a, 48)));
    actions.addView(new Space(a), new LinearLayout.LayoutParams(Ui.dp(a, 12), 1));
    actions.addView(
        button(
            a,
            "确定",
            true,
            () -> {
              String name = render(input.getText().toString(), folder).trim();
              String problem = validate(name);
              if (problem != null) {
                error.setText(problem);
                error.setVisibility(View.VISIBLE);
                return;
              }
              result.accept(name);
              d.dismiss();
            }),
        new LinearLayout.LayoutParams(Ui.dp(a, 80), Ui.dp(a, 48)));
    card.addView(actions);
    root.addView(chips(a, input));
    Ui.dialogInsets(d, root);
    d.getWindow()
        .setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    d.getWindow().setDimAmount(.6f);
    d.show();
    d.getWindow().setLayout(-1, -1);
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {}

          public void afterTextChanged(Editable e) {
            decorate(a, e);
          }
        });
    decorate(a, input.getText());
    input.selectAll();
    input.requestFocus();
  }

  private static void manage(Activity a, String folder, Runnable changed) {
    Dialog d = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    List<String> values = load(a);
    boolean[] order = {false};
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout head = Ui.row(a);
    head.addView(Ui.iconButton(a, "back", "返回", d::dismiss));
    head.addView(
        Ui.text(a, "经常使用的文件名", 20, Ui.INK), new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1));
    TextView reorder = button(a, "编辑顺序", false, () -> {});
    head.addView(reorder);
    root.addView(head);
    ScrollView scroll = new ScrollView(a);
    LinearLayout list = Ui.column(a);
    Ui.padding(list, 8, 12);
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Runnable[] draw = new Runnable[1];
    draw[0] =
        () -> {
          list.removeAllViews();
          for (int i = 0; i < values.size(); i++) {
            int at = i;
            String format = values.get(i);
            LinearLayout row = Ui.row(a);
            Ui.padding(row, 12, 8);
            row.setBackground(Ui.shape(0xfff5f5f5, Ui.dp(a, 12)));
            if (order[0])
              row.addView(
                  new Ui.Icon(a, "menu"),
                  new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
            TextView name = Ui.text(a, format, 14, Ui.INK);
            AppAppearance.keepText(name);
            name.setText(display(a, format));
            Ui.padding(name, 8, 8);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(
                Ui.iconButton(
                    a,
                    "edit",
                    "编辑",
                    () ->
                        edit(
                            a,
                            folder,
                            format,
                            at,
                            values,
                            () -> {
                              save(a, values);
                              changed.run();
                              draw[0].run();
                            })));
            row.setOnLongClickListener(
                v -> {
                  v.startDragAndDrop(
                      ClipData.newPlainText("format", "" + at),
                      new View.DragShadowBuilder(v),
                      Integer.valueOf(at),
                      0);
                  return true;
                });
            row.setOnDragListener(
                (v, e) -> {
                  if (e.getAction() == DragEvent.ACTION_DROP) {
                    int from = (Integer) e.getLocalState();
                    if (from >= 0 && from < values.size()) {
                      values.add(at, values.remove(from));
                      save(a, values);
                      changed.run();
                      draw[0].run();
                    }
                  }
                  return true;
                });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.setMargins(Ui.dp(a, 8), 0, Ui.dp(a, 8), Ui.dp(a, 8));
            list.addView(row, rp);
          }
          if (values.isEmpty()) list.addView(Ui.text(a, "没有经常使用的文件名。", 14, Ui.MUTED));
        };
    reorder.setOnClickListener(
        v -> {
          order[0] = !order[0];
          reorder.setText(order[0] ? "完成" : "编辑顺序");
          save(a, values);
          changed.run();
          draw[0].run();
        });
    draw[0].run();
    root.addView(
        button(
            a,
            "＋ 添加",
            false,
            () ->
                edit(
                    a,
                    folder,
                    "",
                    -1,
                    values,
                    () -> {
                      save(a, values);
                      changed.run();
                      draw[0].run();
                    })),
        new LinearLayout.LayoutParams(-1, Ui.dp(a, 56)));
    Ui.dialogInsets(d, root);
    d.show();
    d.getWindow().setLayout(-1, -1);
  }

  private static void edit(
      Activity a, String folder, String previous, int at, List<String> values, Runnable saved) {
    Dialog d = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout head = Ui.row(a);
    head.addView(Ui.iconButton(a, "back", "返回", d::dismiss));
    head.addView(
        Ui.text(a, "经常使用的文件名", 20, Ui.INK), new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1));
    if (at >= 0)
      head.addView(
          button(
              a,
              "删除",
              false,
              () ->
                  Ui.alert(a)
                      .setMessage("您要删除文件名格式吗？")
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "删除",
                          (x, w) -> {
                            values.remove(at);
                            saved.run();
                            d.dismiss();
                          })
                      .show()));
    root.addView(head);
    ScrollView scroll = new ScrollView(a);
    LinearLayout body = Ui.column(a);
    body.setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), Ui.dp(a, 24));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout card = Ui.column(a);
    Ui.padding(card, 16, 20);
    card.setBackground(Ui.shape(0xfff5f5f5, Ui.dp(a, 8)));
    card.addView(Ui.text(a, "预览", 12, Ui.MUTED));
    ImageView icon = new ImageView(a);
    icon.setImageResource(R.drawable.myflat_filename_preview);
    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 64));
    ip.gravity = Gravity.CENTER;
    ip.topMargin = Ui.dp(a, 8);
    card.addView(icon, ip);
    TextView preview = Ui.text(a, "", 13, Ui.INK);
    AppAppearance.keepText(preview);
    preview.setGravity(Gravity.CENTER);
    card.addView(preview);
    body.addView(card);
    body.addView(Ui.spacer(a, 24));
    body.addView(Ui.text(a, "文件名格式", 12, Ui.MUTED));
    body.addView(Ui.spacer(a, 8));
    EditText input = Ui.input(a, previous, "请创建文件名格式。");
    input.setTextSize(14);
    Ui.padding(input, 12, 12);
    android.graphics.drawable.GradientDrawable box = Ui.shape(0xfffafafa, Ui.dp(a, 8));
    box.setStroke(Ui.dp(a, 2), Ui.GREEN);
    input.setBackground(box);
    body.addView(input);
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            preview.setText(render(s.toString(), folder));
          }

          public void afterTextChanged(Editable e) {
            decorate(a, e);
          }
        });
    decorate(a, input.getText());
    preview.setText(render(previous, folder));
    TextView save =
        button(
            a,
            "保存",
            true,
            () -> {
              String format = input.getText().toString();
              String problem = validate(render(format, folder));
              if (problem != null) {
                input.setError(problem);
                return;
              }
              if (at < 0) values.add(format);
              else values.set(at, format);
              saved.run();
              d.dismiss();
            });
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 48));
    sp.setMargins(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 16));
    root.addView(save, sp);
    root.addView(chips(a, input));
    Ui.dialogInsets(d, root);
    d.show();
    d.getWindow().setLayout(-1, -1);
  }
}
