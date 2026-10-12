package dev.myflat.app;

import android.app.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import java.util.List;
import java.util.function.*;

/** Camera overlays share the same controls and spacing while leaving the preview visible. */
final class CameraMenus {
  private CameraMenus() {}

  static Dialog more(
      Activity activity,
      ScanSettings options,
      boolean sound,
      Consumer<Boolean> soundChanged,
      Runnable settings,
      Runnable guide,
      Runnable importer,
      Runnable optionsChanged) {
    LinearLayout card = Ui.column(activity);
    card.setBackground(Ui.shape(0xee191b1a, Ui.dp(activity, 20)));
    Ui.padding(card, 16, 8);
    Dialog dialog = dialog(activity, card);
    LinearLayout header = Ui.row(activity);
    header.addView(
        action(
            activity,
            sound ? R.drawable.myflat_ic_sound_on : R.drawable.myflat_ic_sound_off,
            "快门\n声音",
            () -> {
              boolean selected =
                  !activity
                      .getPreferences(Activity.MODE_PRIVATE)
                      .getBoolean("shutter_sound", false);
              soundChanged.accept(selected);
              ImageView icon = (ImageView) ((LinearLayout) header.getChildAt(0)).getChildAt(0);
              icon.setImageResource(
                  selected ? R.drawable.myflat_ic_sound_on : R.drawable.myflat_ic_sound_off);
              header.getChildAt(0).setContentDescription(selected ? "快门声音开" : "快门声音关");
            }),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 104), 1));
    header.getChildAt(0).setContentDescription(sound ? "快门声音开" : "快门声音关");
    header.addView(
        action(
            activity,
            R.drawable.myflat_ic_settings,
            "设置",
            () -> {
              dialog.dismiss();
              settings.run();
            }),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 104), 1));
    card.addView(header);
    card.addView(divider(activity, 0xff343635));
    toggle(
        activity,
        card,
        "增强颜色",
        "提升色彩饱和度和对比度，以提高图像清晰度。",
        options.enhance,
        value -> {
          options.enhance = value;
          optionsChanged.run();
        });
    toggle(
        activity,
        card,
        "去除手指",
        "移除扫描中的可见手指。",
        options.fingers,
        value -> {
          options.fingers = value;
          optionsChanged.run();
        });
    toggle(
        activity,
        card,
        "拉直",
        "调整对齐以获得更直的结果。",
        options.straighten,
        value -> {
          options.straighten = value;
          optionsChanged.run();
        });
    LinearLayout footer = Ui.row(activity);
    footer.addView(
        footerAction(
            activity,
            R.drawable.myflat_ic_compare,
            "扫描指南",
            () -> {
              dialog.dismiss();
              guide.run();
            }),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 64), 1));
    View separator = divider(activity, 0xff343635);
    footer.addView(
        separator, new LinearLayout.LayoutParams(Ui.dp(activity, 1), Ui.dp(activity, 32)));
    footer.addView(
        footerAction(
            activity,
            R.drawable.myflat_ic_camera_import,
            "导入",
            () -> {
              dialog.dismiss();
              importer.run();
            }),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 64), 1));
    card.addView(footer);
    dialog.setOnShowListener(
        d -> {
          Window w = dialog.getWindow();
          w.setLayout(
              activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 32), -2);
          w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
          WindowManager.LayoutParams flags = w.getAttributes();
          flags.y = Ui.dp(activity, 40);
          flags.dimAmount = 0;
          w.setAttributes(flags);
        });
    return dialog;
  }

  static Dialog folders(
      Activity activity,
      List<DocumentStore.Folder> folders,
      long selected,
      LongConsumer choose,
      Runnable all) {
    LinearLayout card = Ui.column(activity);
    card.setBackground(Ui.shape(Color.WHITE, Ui.dp(activity, 20)));
    card.setClipToOutline(true);
    Dialog dialog = dialog(activity, card);
    TextView title = Ui.bold(activity, "保存扫描到", 18);
    Ui.padding(title, 20, 20);
    card.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 64)));
    LinearLayout heading = Ui.row(activity);
    Ui.padding(heading, 20, 0);
    heading.setBackgroundColor(0xfff5f5f5);
    heading.addView(
        icon(activity, R.drawable.myflat_ic_folder, Ui.INK),
        new LinearLayout.LayoutParams(Ui.dp(activity, 22), Ui.dp(activity, 22)));
    TextView label = Ui.bold(activity, "  文件夹", 16);
    heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    TextView viewAll = Ui.text(activity, "查看全部  ›", 14, 0xff777777);
    heading.addView(viewAll);
    heading.setContentDescription("查看全部文件夹");
    heading.setOnClickListener(
        v -> {
          dialog.dismiss();
          all.run();
        });
    card.addView(heading, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 52)));
    ScrollView scroll = new ScrollView(activity);
    LinearLayout rows = Ui.column(activity);
    // Show the selected folder and a few recent destinations; the complete list is one tap away.
    int shown = 0;
    for (DocumentStore.Folder folder : folders) {
      if (folder.id != selected && shown >= 3) continue;
      LinearLayout row = Ui.row(activity);
      Ui.padding(row, 20, 0);
      boolean checked = folder.id == selected;
      row.setBackgroundColor(checked ? 0xffd5f3ee : Color.WHITE);
      TextView name = Ui.text(activity, folder.name, 16, checked ? Ui.GREEN : Ui.INK);
      name.setSingleLine(true);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      if (checked) {
        ImageView check = icon(activity, R.drawable.myflat_ic_check, Color.WHITE);
        check.setBackground(Ui.shape(Ui.GREEN, Ui.dp(activity, 12)));
        Ui.padding(check, 3, 3);
        row.addView(check, new LinearLayout.LayoutParams(Ui.dp(activity, 20), Ui.dp(activity, 20)));
      }
      row.setContentDescription(folder.name + (checked ? "，已选中" : ""));
      row.setOnClickListener(
          v -> {
            choose.accept(folder.id);
            dialog.dismiss();
          });
      rows.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 56)));
      shown++;
    }
    scroll.addView(rows);
    card.addView(
        scroll, new LinearLayout.LayoutParams(-1, Ui.dp(activity, Math.min(shown * 56, 224))));
    card.addView(Ui.spacer(activity, 20));
    dialog.setOnShowListener(
        d -> {
          Window w = dialog.getWindow();
          w.setLayout(
              Math.round(activity.getResources().getDisplayMetrics().widthPixels * .80f), -2);
          w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
          WindowManager.LayoutParams flags = w.getAttributes();
          flags.y = Ui.dp(activity, 104);
          flags.dimAmount = .08f;
          w.setAttributes(flags);
        });
    return dialog;
  }

  private static Dialog dialog(Activity activity, LinearLayout card) {
    Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setContentView(card);
    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    dialog.setCanceledOnTouchOutside(true);
    return dialog;
  }

  private static ImageView icon(Activity activity, int resource, int tint) {
    ImageView icon = new ImageView(activity);
    icon.setImageResource(resource);
    icon.setImageTintList(ColorStateList.valueOf(tint));
    return icon;
  }

  private static View action(Activity activity, int resource, String text, Runnable click) {
    LinearLayout box = Ui.column(activity);
    box.setGravity(Gravity.CENTER);
    ImageView image = icon(activity, resource, Color.WHITE);
    box.addView(image, new LinearLayout.LayoutParams(Ui.dp(activity, 24), Ui.dp(activity, 24)));
    box.addView(Ui.spacer(activity, 10));
    TextView label = Ui.text(activity, text, 18, Color.WHITE);
    label.setGravity(Gravity.CENTER);
    box.addView(label);
    box.setContentDescription(text.replace('\n', ' '));
    box.setOnClickListener(v -> click.run());
    return box;
  }

  private static View footerAction(Activity activity, int resource, String text, Runnable click) {
    LinearLayout row = Ui.row(activity);
    row.setGravity(Gravity.CENTER);
    row.addView(
        icon(activity, resource, Color.WHITE),
        new LinearLayout.LayoutParams(Ui.dp(activity, 24), Ui.dp(activity, 24)));
    TextView label = Ui.text(activity, "  " + text, 17, Color.WHITE);
    row.addView(label);
    row.setContentDescription(text);
    row.setOnClickListener(v -> click.run());
    return row;
  }

  private static void toggle(
      Activity activity,
      LinearLayout card,
      String title,
      String summary,
      boolean value,
      Consumer<Boolean> changed) {
    LinearLayout row = Ui.row(activity);
    Ui.padding(row, 10, 12);
    LinearLayout words = Ui.column(activity);
    words.addView(Ui.text(activity, title, 18, Color.WHITE));
    TextView description = Ui.text(activity, summary, 14, 0xffaaaaaa);
    Ui.padding(description, 0, 4);
    words.addView(description);
    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    PillSwitch toggle = new PillSwitch(activity);
    toggle.setChecked(value);
    toggle.setContentDescription(title);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(Ui.dp(activity, 52), Ui.dp(activity, 48));
    lp.leftMargin = Ui.dp(activity, 12);
    row.addView(toggle, lp);
    toggle.setOnCheckedChangeListener((button, selected) -> changed.accept(selected));
    row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
    row.setMinimumHeight(Ui.dp(activity, 92));
    card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    card.addView(divider(activity, 0xff343635));
  }

  private static final class PillSwitch extends CompoundButton {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();

    PillSwitch(Activity activity) {
      super(activity);
      setButtonDrawable(null);
      setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
      return Switch.class.getName();
    }

    @Override
    public void setChecked(boolean checked) {
      super.setChecked(checked);
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float width = Ui.dp(getContext(), 48), height = Ui.dp(getContext(), 28);
      float x = (getWidth() - width) / 2, y = (getHeight() - height) / 2;
      track.set(x, y, x + width, y + height);
      paint.setColor(isChecked() ? 0xff00ae94 : 0xff626463);
      canvas.drawRoundRect(track, height / 2, height / 2, paint);
      paint.setColor(Color.WHITE);
      float radius = height / 2 - Ui.dp(getContext(), 2);
      canvas.drawCircle(
          isChecked() ? x + width - height / 2 : x + height / 2, y + height / 2, radius, paint);
    }
  }

  private static View divider(Activity activity, int color) {
    View view = new View(activity);
    view.setBackgroundColor(color);
    view.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(activity, 1)));
    return view;
  }
}
