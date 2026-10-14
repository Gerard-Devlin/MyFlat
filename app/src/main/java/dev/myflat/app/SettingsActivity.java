package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Native settings hierarchy with camera preferences and local document management. */
public final class SettingsActivity extends Activity {
  private LinearLayout list;
  private SharedPreferences prefs;
  private boolean busy;
  private ScrollView settingsScroll;
  private int scrollPosition;
  private boolean includeLocked;
  private Dialog transferDialog;
  private LinearLayout transferBody;

  @Override
  public void onCreate(Bundle state) {
    AppAppearance.prepare(this);
    super.onCreate(state);
    prefs = getSharedPreferences("preferences", 0);
    build();
    if ("timer".equals(getIntent().getStringExtra("screen"))) timer();
  }

  private void build() {
    if (settingsScroll != null) scrollPosition = settingsScroll.getScrollY();
    LinearLayout root = Ui.root(this);
    LinearLayout header = Ui.row(this);
    header.addView(Ui.iconButton(this, "back", "返回", this::finish));
    TextView title = Ui.bold(this, "设置", 20);
    title.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
    titleParams.leftMargin = Ui.dp(this, 16);
    title.setTypeface(android.graphics.Typeface.DEFAULT);
    header.addView(title, titleParams);
    root.addView(header);
    ScrollView scroll = new ScrollView(this);
    settingsScroll = scroll;
    scroll.setFillViewport(true);
    list = Ui.column(this);
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    category("实验性功能");
    item("建议文本更改", "", () -> Support.translation(this));
    item(
        "分享这个应用程式",
        "",
        () ->
            startActivity(
                Intent.createChooser(
                    new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(
                            Intent.EXTRA_TEXT, "MyFlat\nhttps://github.com/Gerard-Devlin/MyFlat"),
                    "分享")));
    category("相机设置");
    toggle("将副本保存到相册", "自动将扫描保存到设备相册。", "gallery", false);
    toggle("扫描时振动", "", "haptic", false);
    String[] directions = {"自动旋转", "竖向", "横向"};
    item(
        "扫描方向",
        directions[prefs.getInt("direction", 0)],
        () ->
            choice(
                "扫描方向",
                directions,
                prefs.getInt("direction", 0),
                n -> {
                  prefs.edit().putInt("direction", n).apply();
                  build();
                }));
    String resolutionValue;
    try {
      resolutionValue = PhotoResolution.discover(this).selected(this).label();
    } catch (Exception ignored) {
      resolutionValue = "";
    }
    item("扫描分辨率", resolutionValue, this::resolution);
    ScanSettings scan = ScanSettings.defaults(this);
    item("两页扫描顺序", scan.rightFirst ? "从右至左" : "从左至右", this::twoPagesOrder);
    item("自定义定时器", timerLabel(customTimer()), this::timer);
    category("应用程序设置");
    item("夜间模式", AppAppearance.nightLabel(this), this::nightMode);
    item("语言", "", this::language);
    item("管理存储", "", this::storage);
    item("文件夹安全", "", () -> FolderSecurity.settings(this));
    item(
        "通知",
        "",
        () ->
            startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
    category("附加的功能");
    toggle("扫描二维码", "", "qr", true);
    item("备份 / 恢复", "", this::backup);
    item("高级设置", "", this::advanced);
    category("联系我们");
    item("客户支持", "", () -> Support.show(this));
    item("商务咨询", "", () -> Support.business(this));
    category("法律文件");
    item("使用条款", "", () -> WelcomeActivity.policy(this, "使用条款", WelcomeActivity.TERMS));
    item("隐私政策", "", () -> WelcomeActivity.policy(this, "隐私政策", WelcomeActivity.PRIVACY));
    item(
        "开放源代码许可",
        "",
        () ->
            startActivity(
                new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "https://github.com/Gerard-Devlin/MyFlat/blob/main/THIRD_PARTY_NOTICES.md"))));
    category("关于此应用");
    item(
        "开发者",
        "MyFlat",
        () ->
            startActivity(
                new Intent(
                    Intent.ACTION_VIEW, Uri.parse("https://github.com/Gerard-Devlin/MyFlat"))));
    String version = "";
    try {
      version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception ignored) {
    }
    item("版本", version, () -> {});
    list.addView(Ui.spacer(this, 24));
    scroll.post(() -> scroll.scrollTo(0, scrollPosition));
  }

  private void category(String title) {
    TextView label = Ui.bold(this, title, 14);
    label.setTextColor(0xff666666);
    Ui.padding(label, 16, 8);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = Ui.dp(this, 16);
    list.addView(label, p);
  }

  private LinearLayout item(String title, String value, Runnable click) {
    return item(list, title, value, "", click);
  }

  private LinearLayout item(
      LinearLayout target, String title, String value, String summary, Runnable click) {
    LinearLayout row = Ui.row(this);
    Ui.padding(row, 16, 16);
    row.setMinimumHeight(Ui.dp(this, 56));
    LinearLayout labels = Ui.column(this);
    labels.addView(Ui.text(this, title, 16, Ui.INK));
    if (!summary.isEmpty()) labels.addView(Ui.text(this, summary, 14, 0xff666666));
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    if (value.isEmpty()) {
      ImageView next = new ImageView(this);
      next.setImageResource(R.drawable.myflat_settings_next);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
      p.leftMargin = Ui.dp(this, 16);
      row.addView(next, p);
    } else {
      TextView right = Ui.text(this, value, 14, 0xff666666);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
      p.leftMargin = Ui.dp(this, 16);
      row.addView(right, p);
    }
    row.setBackgroundResource(android.R.drawable.list_selector_background);
    row.setOnClickListener(v -> click.run());
    target.addView(row);
    return row;
  }

  private void toggle(String title, String summary, String key, boolean initial) {
    toggle(list, title, summary, key, initial);
  }

  private void toggle(
      LinearLayout target, String title, String summary, String key, boolean initial) {
    LinearLayout row = Ui.row(this);
    Ui.padding(row, 16, 16);
    row.setMinimumHeight(Ui.dp(this, 56));
    LinearLayout labels = Ui.column(this);
    labels.addView(Ui.text(this, title, 16, Ui.INK));
    if (!summary.isEmpty()) labels.addView(Ui.text(this, summary, 14, 0xff666666));
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    Switch toggle = new Switch(this);
    toggle.setChecked(prefs.getBoolean(key, initial));
    toggle.setContentDescription(title);
    toggle.setThumbTintList(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {Ui.GREEN, 0xff9e9e9e}));
    toggle.setTrackTintList(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {0xffb2dfdb, 0xffe0e0e0}));
    toggle.setOnCheckedChangeListener(
        (b, value) -> {
          prefs.edit().putBoolean(key, value).apply();
          if (value
              && key.equals("gallery")
              && Build.VERSION.SDK_INT < 29
              && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(
                new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        });
    LinearLayout.LayoutParams widget = new LinearLayout.LayoutParams(-2, -2);
    widget.leftMargin = Ui.dp(this, 16);
    row.addView(toggle, widget);
    row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
    target.addView(row);
  }

  private interface Selected {
    void set(int at);
  }

  private Dialog page(String title, LinearLayout body) {
    Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout header = Ui.row(this);
    header.addView(
        Ui.iconButton(
            this,
            "back",
            "返回",
            () -> {
              if (!busy) dialog.dismiss();
            }));
    TextView label = Ui.text(this, title, 20, Ui.INK);
    label.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
    titleParams.leftMargin = Ui.dp(this, 16);
    header.addView(label, titleParams);
    root.addView(header);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Ui.dialogInsets(dialog, root);
    dialog.setOnDismissListener(d -> build());
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
    return dialog;
  }

  private void choice(String title, String[] values, int selected, Selected chosen) {
    choice(title, values, selected, chosen, null);
  }

  private void choice(String title, String[] values, int selected, Selected chosen, View guide) {
    LinearLayout body = Ui.column(this);
    ImageView[] checks = new ImageView[values.length];
    for (int i = 0; i < values.length; i++) {
      final int at = i;
      LinearLayout row = Ui.row(this);
      Ui.padding(row, 16, 16);
      row.setMinimumHeight(Ui.dp(this, 56));
      row.addView(Ui.text(this, values[i], 16, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
      ImageView check = new ImageView(this);
      check.setImageResource(R.drawable.myflat_ic_check);
      check.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
      check.setVisibility(selected == i ? View.VISIBLE : View.INVISIBLE);
      row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
      checks[i] = check;
      row.setBackgroundResource(android.R.drawable.list_selector_background);
      row.setOnClickListener(
          v -> {
            chosen.set(at);
            for (int j = 0; j < checks.length; j++)
              checks[j].setVisibility(j == at ? View.VISIBLE : View.INVISIBLE);
          });
      body.addView(row);
    }
    if (guide != null) body.addView(guide);
    page(title, body);
  }

  private void nightMode() {
    choice(
        "夜间模式",
        new String[] {"系统默认", "关闭", "开启"},
        AppAppearance.nightMode(this),
        at -> AppAppearance.setNightMode(this, at));
  }

  private void language() {
    String[][] languages = AppAppearance.LANGUAGES;
    String[] labels = new String[languages.length];
    int selected = 0;
    for (int i = 0; i < languages.length; i++) {
      labels[i] = languages[i][1];
      if (AppAppearance.language(this).equals(languages[i][0])) selected = i;
    }
    choice("语言", labels, selected, at -> AppAppearance.setLanguage(this, languages[at][0]));
  }

  private float customTimer() {
    return prefs.getFloat("timer_seconds", prefs.getInt("timer", 7));
  }

  private static String timerLabel(float value) {
    return (value == (int) value ? Integer.toString((int) value) : Float.toString(value)) + "s";
  }

  private void timer() {
    final float[] times = {1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 7f, 10f, 15f, 20f};
    LinearLayout body = Ui.column(this);
    LinearLayout row = Ui.row(this);
    Ui.padding(row, 16, 16);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.myflat_ic_camera_timer_off);
    row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
    SeekBar slider = new SeekBar(this);
    slider.setMax(times.length - 1);
    int initial = 0;
    for (int i = 0; i < times.length; i++)
      if (Math.abs(times[i] - customTimer()) < Math.abs(times[initial] - customTimer()))
        initial = i;
    slider.setProgress(initial);
    slider.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
    slider.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
    LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(0, -2, 1);
    sliderParams.leftMargin = Ui.dp(this, 16);
    row.addView(slider, sliderParams);
    TextView seconds = Ui.text(this, timerLabel(times[initial]), 14, 0xff666666);
    seconds.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    seconds.setMinWidth(Ui.dp(this, 32));
    LinearLayout.LayoutParams secondsParams = new LinearLayout.LayoutParams(-2, -2);
    secondsParams.leftMargin = Ui.dp(this, 8);
    row.addView(seconds, secondsParams);
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar seek, int value, boolean fromUser) {
            seconds.setText(timerLabel(times[value]));
            if (fromUser)
              prefs
                  .edit()
                  .putFloat("timer_seconds", times[value])
                  .putInt("timer", Math.round(times[value]))
                  .apply();
          }

          public void onStartTrackingTouch(SeekBar seek) {}

          public void onStopTrackingTouch(SeekBar seek) {}
        });
    body.addView(row);
    page("自定义定时器", body);
  }

  private void resolution() {
    try {
      Dialog dialog = PhotoResolution.picker(this, PhotoResolution.discover(this), this::build);
      dialog.show();
    } catch (Exception error) {
      Ui.error(this, error);
    }
  }

  private void storage() {
    StorageManagementDialog.show(this);
  }

  private static long size(File f) {
    if (f.isFile()) return f.length();
    long total = 0;
    File[] children = f.listFiles();
    if (children != null) for (File child : children) total += size(child);
    return total;
  }

  private void delete(File f) {
    try {
      if (!f.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath() + File.separator))
        return;
      if (f.isDirectory()) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) delete(child);
      }
      f.delete();
    } catch (IOException ignored) {
    }
  }

  private void twoPagesOrder() {
    LinearLayout guide = Ui.column(this);
    guide.setGravity(Gravity.CENTER_HORIZONTAL);
    guide.setBackgroundColor(0xffeceff1);
    Ui.padding(guide, 32, 32);
    ImageView arrow = new ImageView(this);
    arrow.setImageResource(R.drawable.myflat_settings_order_arrow);
    guide.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 16)));
    guide.addView(Ui.spacer(this, 16));
    com.airbnb.lottie.LottieAnimationView animation =
        new com.airbnb.lottie.LottieAnimationView(this);
    boolean right = ScanSettings.defaults(this).rightFirst;
    animation.setAnimation(
        right ? R.raw.myflat_settings_order_right : R.raw.myflat_settings_order_left);
    animation.setRepeatCount(-1);
    animation.playAnimation();
    guide.addView(animation, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
    choice(
        "两页扫描顺序",
        new String[] {"从左至右", "从右至左"},
        right ? 1 : 0,
        n -> {
          ScanSettings s = ScanSettings.defaults(this);
          s.rightFirst = n == 1;
          s.save(this);
          arrow.setScaleX(n == 1 ? -1 : 1);
          animation.setAnimation(
              n == 1 ? R.raw.myflat_settings_order_right : R.raw.myflat_settings_order_left);
          animation.playAnimation();
        },
        guide);
    arrow.setScaleX(right ? -1 : 1);
  }

  private void backup() {
    LinearLayout body = Ui.column(this);
    item(body, "备份", "", "将你的 MyFlat 数据导出为备份文件。", this::backupFile);
    item(
        body,
        "恢复",
        "",
        "从 MyFlat 的备份文件中恢复数据。",
        () ->
            startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("application/zip")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                2));
    item(body, "恢复丢失的页面", "", "添加由于错误而不在数据库中的文件夹或页面。", this::recover);
    LinearLayout guide = Ui.column(this);
    Ui.padding(guide, 16, 12);
    guide.addView(Ui.line(this));
    guide.addView(Ui.spacer(this, 12));
    guide.addView(Ui.text(this, "备份文件将保存到您选择的文件夹中。", 14, 0xff666666));
    guide.addView(Ui.spacer(this, 4));
    guide.addView(Ui.text(this, "请注意，任何移动到垃圾桶的数据都不会被备份。", 14, 0xff666666));
    guide.addView(Ui.spacer(this, 4));
    TextView feedback = Ui.text(this, "如果您在备份/恢复方面有任何问题，请通过客户支持与我们联系。", 14, 0xff666666);
    feedback.setOnClickListener(v -> Support.show(this));
    guide.addView(feedback);
    body.addView(guide);
    page("备份 / 恢复", body);
  }

  private void backupFile() {
    includeLocked = false;
    inspectBackup(false);
  }

  private void inspectBackup(boolean include) {
    busy = true;
    ProgressDialog waiting = ProgressDialog.show(this, "", "正在分析数据以准备备份", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                Backup.Preview preview = Backup.inspect(this, include);
                runOnUiThread(
                    () -> {
                      busy = false;
                      waiting.dismiss();
                      transferInfo(false, preview, null);
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      busy = false;
                      waiting.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void transferInfo(boolean restoring, Backup.Preview preview, Uri archive) {
    if (transferDialog != null) transferDialog.dismiss();
    transferBody = Ui.column(this);
    Ui.padding(transferBody, 24, 24);
    transferBody.addView(Ui.spacer(this, 40));
    TextView title = Ui.bold(this, restoring ? "准备好恢复数据" : "准备好备份数据", 22);
    title.setGravity(Gravity.CENTER);
    transferBody.addView(title);
    transferBody.addView(Ui.spacer(this, 40));
    ImageView image = new ImageView(this);
    image.setImageResource(
        restoring ? R.drawable.myflat_restore_ready : R.drawable.myflat_backup_ready);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    transferBody.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(this, 130)));
    transferBody.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1));
    if (!restoring && FolderSecurity.hasSecureFolders(this)) {
      CheckBox secured = Ui.check(this, "包括锁定的文件夹", includeLocked);
      secured.setOnCheckedChangeListener(
          (button, checked) -> {
            if (!checked) {
              includeLocked = false;
              inspectBackup(false);
              return;
            }
            secured.setOnCheckedChangeListener(null);
            secured.setChecked(false);
            FolderSecurity.authenticate(
                this,
                () -> {
                  includeLocked = true;
                  inspectBackup(true);
                },
                () -> transferInfo(false, preview, null));
          });
      transferBody.addView(secured);
    }
    LinearLayout summary = Ui.column(this);
    Ui.padding(summary, 20, 20);
    summary.setBackground(Ui.shape(0xfffafafa, Ui.dp(this, 20)));
    summaryRow(summary, "页面", Integer.toString(preview.pages));
    summary.addView(Ui.spacer(this, 8));
    summaryRow(summary, "文件夹", Integer.toString(preview.folders));
    summary.addView(Ui.spacer(this, 20));
    summary.addView(Ui.line(this));
    summary.addView(Ui.spacer(this, 20));
    summaryRow(
        summary, "估计大小", String.format(Locale.getDefault(), "%.1f MB", preview.bytes / 1048576d));
    transferBody.addView(summary);
    transferBody.addView(Ui.spacer(this, 32));
    TextView start =
        Ui.primary(
            this,
            "开始",
            () -> {
              if (restoring) transfer(2, archive);
              else
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("application/zip")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(
                            Intent.EXTRA_TITLE,
                            "MyFlat-"
                                + new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT)
                                    .format(new Date())
                                + ".zip"),
                    1);
            });
    transferBody.addView(start, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
    transferDialog = page(restoring ? "恢复" : "备份", transferBody);
  }

  private void summaryRow(LinearLayout body, String title, String value) {
    LinearLayout row = Ui.row(this);
    row.addView(Ui.text(this, title, 16, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(Ui.text(this, value, 16, Ui.INK));
    body.addView(row);
  }

  private void recover() {
    if (busy) return;
    if (FolderSecurity.hasPin(this))
      FolderSecurity.authenticate(this, this::recoverAllowed, () -> {});
    else recoverAllowed();
  }

  private void recoverAllowed() {
    if (busy) return;
    busy = true;
    ProgressDialog waiting = ProgressDialog.show(this, "", "正在查找丢失的页面", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                LostPages.Preview found = LostPages.inspect(this);
                runOnUiThread(
                    () -> {
                      busy = false;
                      waiting.dismiss();
                      Ui.alert(this)
                          .setTitle("恢复丢失的页面")
                          .setMessage("找到 " + found.pages + " 个页面。")
                          .setNegativeButton("取消", null)
                          .setPositiveButton("恢复", (d, w) -> restoreLost(found))
                          .show();
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      busy = false;
                      waiting.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void restoreLost(LostPages.Preview found) {
    busy = true;
    ProgressDialog waiting = ProgressDialog.show(this, "", "正在恢复", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                int count = LostPages.restore(this, found);
                MyFlatApp.of(this).store.recoverQueue();
                MyFlatApp.of(this).startProcessing();
                runOnUiThread(
                    () -> {
                      busy = false;
                      waiting.dismiss();
                      Ui.alert(this)
                          .setTitle("恢复完成")
                          .setMessage("已恢复 " + count + " 个页面。")
                          .setPositiveButton("确定", null)
                          .show();
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      busy = false;
                      waiting.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void advanced() {
    LinearLayout body = Ui.column(this);
    item(
        body,
        "相机API版本",
        prefs.getInt("camera_api", 2) == 1 ? "Camera1" : "Camera2",
        "选择用于扫描的相机API。",
        () ->
            choice(
                "相机API版本",
                new String[] {"Camera1", "Camera2"},
                prefs.getInt("camera_api", 2) - 1,
                value -> prefs.edit().putInt("camera_api", value + 1).apply()));
    toggle(body, "扫描前自动对焦", "", "explicit_focus", true);
    toggle(body, "点击屏幕进行扫描", "", "preview_touch_scan", false);
    toggle(body, "放大预览", "", "zoom_preview", false);
    toggle(body, "显示扫描区域", "显示要扫描的区域。", "show_scan_guide", true);
    item(body, "恢复丢失的页面", "", "添加由于错误而不在数据库中的文件夹或页面。", this::recover);
    page("高级设置", body);
  }

  private void textPage(String title, String content) {
    Ui.alert(this).setTitle(title).setMessage(content).setPositiveButton("关闭", null).show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null || busy) return;
    Uri uri = data.getData();
    if (request == 1) {
      transfer(request, uri);
      return;
    }
    if (request != 2) return;
    busy = true;
    ProgressDialog waiting = ProgressDialog.show(this, "", "正在分析备份文件以恢复数据", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                Backup.Preview preview = Backup.inspect(this, uri);
                runOnUiThread(
                    () -> {
                      busy = false;
                      waiting.dismiss();
                      transferInfo(true, preview, uri);
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      busy = false;
                      waiting.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void transfer(int request, Uri uri) {
    if (busy) return;
    busy = true;
    if (transferDialog == null) {
      transferBody = Ui.column(this);
      transferDialog = page(request == 1 ? "备份" : "恢复", transferBody);
    }
    transferDialog.setCancelable(false);
    transferBody.removeAllViews();
    Ui.padding(transferBody, 24, 24);
    transferBody.addView(Ui.spacer(this, 40));
    TextView title = Ui.bold(this, request == 1 ? "正在备份" : "正在恢复", 22);
    title.setGravity(Gravity.CENTER);
    transferBody.addView(title);
    com.airbnb.lottie.LottieAnimationView animation =
        new com.airbnb.lottie.LottieAnimationView(this);
    animation.setAnimation(R.raw.myflat_backup_progress);
    animation.setRepeatCount(-1);
    animation.playAnimation();
    transferBody.addView(animation, new LinearLayout.LayoutParams(-1, Ui.dp(this, 280)));
    TextView notice = Ui.text(this, "请不要在备份或恢复过程中关闭应用。", 16, 0xff666666);
    notice.setGravity(Gravity.CENTER);
    transferBody.addView(notice);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                if (request == 1) {
                  try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法打开文件");
                    Backup.write(this, out, includeLocked);
                  }
                } else {
                  try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("无法打开文件");
                    Backup.restore(this, in);
                  }
                }
                runOnUiThread(() -> transferDone(request));
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      busy = false;
                      transferDialog.setCancelable(true);
                      transferDialog.dismiss();
                      transferDialog = null;
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void transferDone(int request) {
    busy = false;
    transferDialog.setCancelable(true);
    transferBody.removeAllViews();
    transferBody.addView(Ui.spacer(this, 40));
    TextView title = Ui.bold(this, request == 1 ? "备份完成" : "恢复完成", 22);
    title.setGravity(Gravity.CENTER);
    transferBody.addView(title);
    transferBody.addView(Ui.spacer(this, 40));
    ImageView image = new ImageView(this);
    image.setImageResource(
        request == 1 ? R.drawable.myflat_backup_done : R.drawable.myflat_restore_done);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    transferBody.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(this, 130)));
    transferBody.addView(Ui.spacer(this, 32));
    TextView description =
        Ui.text(this, request == 1 ? "备份文件已保存到文件夹中。" : "数据已成功恢复。", 16, 0xff666666);
    description.setGravity(Gravity.CENTER);
    transferBody.addView(description);
    transferBody.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1));
    TextView done =
        Ui.primary(
            this,
            "关闭",
            () -> {
              transferDialog.dismiss();
              transferDialog = null;
            });
    transferBody.addView(done, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
    MyFlatApp.of(this).startProcessing();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 7
        && (results.length == 0
            || results[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
      prefs.edit().putBoolean("gallery", false).apply();
      build();
    }
  }

  @Override
  public void onBackPressed() {
    if (!busy) super.onBackPressed();
  }
}
