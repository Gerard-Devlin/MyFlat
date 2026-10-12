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

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("preferences", 0);
    build();
  }

  private void build() {
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
    root.addView(Ui.line(this));
    ScrollView scroll = new ScrollView(this);
    list = Ui.column(this);
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    category("实验性功能");
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
    item("扫描分辨率", "高", this::resolution);
    ScanSettings scan = ScanSettings.defaults(this);
    item(
        "两页扫描顺序",
        scan.rightFirst ? "从右至左" : "从左至右",
        () ->
            choice(
                "两页扫描顺序",
                new String[] {"从左至右", "从右至左"},
                scan.rightFirst ? 1 : 0,
                n -> {
                  ScanSettings s = ScanSettings.defaults(this);
                  s.rightFirst = n == 1;
                  s.save(this);
                  build();
                }));
    item("自定义定时器", prefs.getInt("timer", 7) + "s", this::timer);
    category("应用程序设置");
    item("管理存储", "", this::storage);
    item(
        "通知设置",
        "",
        () ->
            startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
    category("附加的功能");
    item("备份与恢复", "", this::backup);
    item("高级设置", "", this::advanced);
    category("联系我们");
    item("客户支持", "", () -> Support.show(this));
    category("法律文件");
    item(
        "使用条款",
        "",
        () ->
            textPage(
                "使用条款",
                "MyFlat is provided as is, without warranty. You are responsible for the documents"
                    + " you scan and share."));
    item(
        "隐私政策",
        "",
        () ->
            textPage(
                "隐私政策",
                "MyFlat processes documents on your device. Scans, source photos and document"
                    + " metadata stay in app storage unless you export or share them. The app does"
                    + " not upload documents or require an account."));
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
  }

  private void category(String title) {
    TextView label = Ui.bold(this, title, 14);
    label.setTextColor(0xff666666);
    Ui.padding(label, 16, 8);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
    p.topMargin = Ui.dp(this, 16);
    list.addView(label, p);
  }

  private LinearLayout item(String title, String value, Runnable click) {
    LinearLayout row = Ui.row(this);
    Ui.padding(row, 16, 0);
    row.setMinimumHeight(Ui.dp(this, 56));
    TextView text = Ui.text(this, title, 16, Ui.INK);
    row.addView(text, new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1));
    text.setGravity(Gravity.CENTER_VERTICAL);
    TextView right = Ui.text(this, value.isEmpty() ? "›" : value, 14, 0xff666666);
    row.addView(right);
    row.setBackgroundResource(android.R.drawable.list_selector_background);
    row.setOnClickListener(v -> click.run());
    list.addView(row);
    return row;
  }

  private void toggle(String title, String summary, String key, boolean initial) {
    LinearLayout row = Ui.row(this);
    Ui.padding(row, 16, 12);
    LinearLayout labels = Ui.column(this);
    labels.addView(Ui.text(this, title, 16, Ui.INK));
    if (!summary.isEmpty()) labels.addView(Ui.text(this, summary, 14, 0xff666666));
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    Switch toggle = new Switch(this);
    toggle.setChecked(prefs.getBoolean(key, initial));
    toggle.setContentDescription(title);
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
    row.addView(toggle);
    row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
    list.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, summary.isEmpty() ? 56 : 76)));
  }

  private interface Selected {
    void set(int at);
  }

  private void choice(String title, String[] values, int selected, Selected chosen) {
    Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout body = Ui.column(this);
    body.setBackgroundColor(Color.WHITE);
    LinearLayout head = Ui.row(this);
    head.addView(Ui.iconButton(this, "back", "返回", dialog::dismiss));
    head.addView(Ui.bold(this, title, 20));
    body.addView(head);
    for (int i = 0; i < values.length; i++) {
      int at = i;
      LinearLayout row = Ui.row(this);
      Ui.padding(row, 16, 0);
      row.addView(
          Ui.text(this, values[i], 16, Ui.INK),
          new LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1));
      RadioButton radio = new RadioButton(this);
      radio.setChecked(selected == i);
      radio.setClickable(false);
      row.addView(radio);
      row.setOnClickListener(
          v -> {
            chosen.set(at);
            dialog.dismiss();
          });
      body.addView(row);
    }
    Ui.dialogInsets(dialog, body);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
  }

  private void timer() {
    EditText input = Ui.input(this, "" + prefs.getInt("timer", 7), "秒");
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    LinearLayout body = Ui.column(this);
    Ui.padding(body, 24, 16);
    body.addView(input);
    new AlertDialog.Builder(this)
        .setTitle("自定义定时器")
        .setView(body)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确定",
            (d, w) -> {
              try {
                int seconds = Integer.parseInt(input.getText().toString());
                if (seconds < 1 || seconds > 60) throw new NumberFormatException();
                prefs.edit().putInt("timer", seconds).apply();
                build();
              } catch (NumberFormatException ex) {
                Toast.makeText(this, "请输入 1 到 60 秒", Toast.LENGTH_SHORT).show();
              }
            })
        .show();
  }

  private void resolution() {
    String message = "使用相机提供的最高分辨率。";
    try {
      android.hardware.camera2.CameraManager manager =
          getSystemService(android.hardware.camera2.CameraManager.class);
      for (String id : manager.getCameraIdList()) {
        android.hardware.camera2.CameraCharacteristics c = manager.getCameraCharacteristics(id);
        if (!Integer.valueOf(1)
            .equals(c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING))) continue;
        android.hardware.camera2.params.StreamConfigurationMap map =
            c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size size =
            CameraActivity.largest(
                map.getOutputSizes(android.graphics.ImageFormat.JPEG),
                map.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG));
        if (Build.VERSION.SDK_INT >= 31) {
          android.hardware.camera2.params.StreamConfigurationMap full =
              c.get(
                  android.hardware.camera2.CameraCharacteristics
                      .SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
          if (full != null)
            size =
                CameraActivity.largest(
                    new android.util.Size[] {size},
                    full.getOutputSizes(android.graphics.ImageFormat.JPEG),
                    full.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG));
        }
        int width = prefs.getInt("active_jpeg_width", size.getWidth()),
            height = prefs.getInt("active_jpeg_height", size.getHeight());
        message = width + " × " + height;
        break;
      }
    } catch (Exception ignored) {
    }
    new AlertDialog.Builder(this)
        .setTitle("扫描分辨率 · 高")
        .setMessage(message)
        .setPositiveButton("关闭", null)
        .show();
  }

  private void storage() {
    android.os.StatFs disk = new android.os.StatFs(getFilesDir().getPath());
    long bytes = size(getFilesDir());
    new AlertDialog.Builder(this)
        .setTitle("管理存储")
        .setMessage(
            String.format(
                Locale.getDefault(),
                "文档占用 %.1f MB\n可用空间 %.1f GB",
                bytes / 1048576d,
                disk.getAvailableBytes() / 1073741824d))
        .setItems(
            new String[] {"清理临时文件", "查看回收站"},
            (d, w) -> {
              if (w == 0) {
                File exports = new File(getCacheDir(), "exports");
                delete(exports);
                Toast.makeText(this, "临时文件已清理", Toast.LENGTH_SHORT).show();
              } else startActivity(new Intent(this, MainActivity.class).putExtra("trash", true));
            })
        .setPositiveButton("关闭", null)
        .show();
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

  private void backup() {
    new AlertDialog.Builder(this)
        .setTitle("备份与恢复")
        .setItems(
            new String[] {"备份到文件", "从备份文件恢复"},
            (d, w) -> {
              if (w == 0)
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
              else
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("application/zip")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    2);
            })
        .show();
  }

  private void advanced() {
    new AlertDialog.Builder(this)
        .setTitle("高级设置")
        .setItems(
            new String[] {"拍摄与处理", "扫描指南", "恢复未完成的页面"},
            (d, w) -> {
              if (w == 0) {
                EffectsForm form = new EffectsForm(this, ScanSettings.defaults(this), false);
                new AlertDialog.Builder(this)
                    .setTitle("拍摄与处理")
                    .setView(form.view)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("应用", (a, b) -> form.value().save(this))
                    .show();
              } else if (w == 1) new ScanGuideDialog(this, 0, () -> {}).show();
              else {
                MyFlatApp.of(this).store.recoverQueue();
                MyFlatApp.of(this).startProcessing();
                Toast.makeText(this, "已恢复处理队列", Toast.LENGTH_SHORT).show();
              }
            })
        .show();
  }

  private void textPage(String title, String content) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(content)
        .setPositiveButton("关闭", null)
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null || busy) return;
    busy = true;
    Uri uri = data.getData();
    ProgressDialog progress =
        ProgressDialog.show(this, "", request == 1 ? "正在备份" : "正在恢复", true, false);
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                if (request == 1) {
                  try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法打开文件");
                    Backup.write(this, out);
                  }
                } else {
                  try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("无法打开文件");
                    Backup.restore(this, in);
                  }
                }
                runOnUiThread(
                    () -> {
                      busy = false;
                      progress.dismiss();
                      Toast.makeText(this, "已完成", Toast.LENGTH_SHORT).show();
                      MyFlatApp.of(this).startProcessing();
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      busy = false;
                      progress.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
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
