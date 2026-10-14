package dev.myflat.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import dev.myflat.scanner.LivePageDetector;
import dev.myflat.scanner.PageGeometry;
import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Camera2 capture with continuous focus, torch, timer and stable-frame auto shutter. */
public final class CameraActivity extends Activity {
  private TextureView preview;
  private volatile boolean opening;
  private Surface previewSurface;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private CaptureRequest.Builder repeating;
  private ImageReader reader;
  private HandlerThread cameraThread;
  private Handler cameraHandler;
  private final Handler main = new Handler(Looper.getMainLooper());
  private TextView hint, folderLabel;
  private ImageView modeButton,
      flashButton,
      timerButton,
      autoButton,
      recent,
      focusMarker,
      focusZoom;
  private TextView qrChip;
  private CameraQrDetector.Detection qrDetection;
  private long qrSeen;
  private Bitmap zoomBitmap;
  private Rect sensorArea;
  private int afRegions, aeRegions, timerMode, flashMode, scanMode;
  private float timerSeconds;
  private String rescanPage;
  private boolean securityReady;
  private String recentPage;
  private View shutter;
  private Guide guide;
  private ScanSettings options;
  private String documentId, cameraId;
  private LegacyCapture legacy;
  private long folder;
  private boolean flash = false,
      flashAvailable = false,
      auto = false,
      capturing = false,
      resumed = false,
      armed = true;
  private int timer = 0, sensorOrientation = 90, captured = 0;
  private Size previewSize = new Size(1440, 1080);
  private int[] lastPixels;
  private PreviewAnalysis analysis;
  private LivePageDetector.Result detection = LivePageDetector.Result.searching();
  private ScanGuideDialog walkthrough;
  private Dialog cameraMenu;
  private int guideStep;
  private boolean analysisBusy, detectorFailed, shutterSound;
  private long hintUntil;
  private final MediaActionSound sound = new MediaActionSound();
  private long stableSince = 0, lastShot = 0;
  private float changeSinceShot = 0;
  private int deviceOrientation;
  private OrientationEventListener orientation;
  private final List<View> rotatingControls = new ArrayList<>();

  @Override
  public void onCreate(Bundle state) {
    AppAppearance.prepare(this);
    super.onCreate(state);
    options = ScanSettings.defaults(this);
    documentId =
        state != null ? state.getString("document") : getIntent().getStringExtra("document");
    folder = getIntent().getLongExtra("folder", getPreferences(MODE_PRIVATE).getLong("folder", 1));
    if (state != null) {
      captured = state.getInt("captured");
      folder = state.getLong("folder", folder);
    }
    rescanPage = getIntent().getStringExtra("rescan_page");
    timerMode = Math.max(0, Math.min(4, getPreferences(MODE_PRIVATE).getInt("timer_mode", 0)));
    flashMode = Math.max(0, Math.min(3, getPreferences(MODE_PRIVATE).getInt("flash_mode", 0)));
    scanMode =
        Math.max(
            0,
            Math.min(
                2, getPreferences(MODE_PRIVATE).getInt("scan_mode", options.doublePage ? 1 : 0)));
    auto = getPreferences(MODE_PRIVATE).getBoolean("auto", false);
    if (rescanPage != null) {
      scanMode = 0;
      options.doublePage = false;
      auto = false;
    }
    updateTimer();
    guideStep = state == null ? 0 : state.getInt("guide_step");
    shutterSound = getPreferences(MODE_PRIVATE).getBoolean("shutter_sound", false);
    build();
    Runnable enter =
        () -> {
          securityReady = true;
          if (resumed) resumeCameraFlow();
        };
    if (rescanPage != null) FolderSecurity.requirePage(this, rescanPage, enter, this::finish);
    else if (documentId != null)
      FolderSecurity.requireDocument(this, documentId, enter, this::finish);
    else FolderSecurity.require(this, folder, enter, this::finish);
    orientation =
        new OrientationEventListener(this) {
          public void onOrientationChanged(int value) {
            if (value == ORIENTATION_UNKNOWN) return;
            int next = ((value + 45) / 90 * 90) % 360;
            if (next != deviceOrientation) {
              deviceOrientation = next;
              for (View control : rotatingControls)
                control.animate().rotation(-next).setDuration(180).start();
            }
          }
        };
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("document", documentId);
    out.putInt("captured", captured);
    out.putLong("folder", folder);
    out.putInt("guide_step", walkthrough != null ? walkthrough.step() : guideStep);
  }

  private void build() {
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(0xfffafafa);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (Build.VERSION.SDK_INT >= 28) {
      WindowManager.LayoutParams flags = getWindow().getAttributes();
      flags.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      getWindow().setAttributes(flags);
    }
    CameraLayout root = new CameraLayout(this);
    setContentView(root);
    FrameLayout frame = new FrameLayout(this);
    frame.setBackgroundColor(Color.BLACK);
    root.addView(frame);
    preview = new TextureView(this);
    frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    preview.setSurfaceTextureListener(surfaceListener);
    guide = new Guide(this);
    frame.addView(guide, new FrameLayout.LayoutParams(-1, -1));
    hint = Ui.text(this, "", 16, Color.WHITE);
    hint.setGravity(Gravity.CENTER);
    hint.setBackground(Ui.shape(Color.BLACK, Ui.dp(this, 24)));
    Ui.padding(hint, 16, 5);
    frame.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    preview.setContentDescription("相机取景，点按对焦");
    preview.setOnTouchListener(
        new View.OnTouchListener() {
          private float x, y;
          private boolean held;
          private final Runnable magnify =
              () -> {
                if (getSharedPreferences("preferences", 0).getBoolean("zoom_preview", false)) {
                  held = true;
                  showFocusZoom(x, y);
                }
              };

          @Override
          public boolean onTouch(View v, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
              x = event.getX();
              y = event.getY();
              held = false;
              main.postDelayed(magnify, ViewConfiguration.getLongPressTimeout());
              return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
              main.removeCallbacks(magnify);
              hideFocusZoom();
              if (!held) {
                preview.performClick();
                if (getSharedPreferences("preferences", 0).getBoolean("preview_touch_scan", false))
                  shootWithTimer();
                else focus(x, y);
              }
              return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
              main.removeCallbacks(magnify);
              hideFocusZoom();
              return true;
            }
            return true;
          }
        });
    focusMarker = new ImageView(this);
    focusMarker.setImageResource(R.drawable.myflat_ic_camera_focus);
    focusMarker.setImageTintList(android.content.res.ColorStateList.valueOf(0xffbdff00));
    focusMarker.setVisibility(View.GONE);
    focusMarker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    frame.addView(focusMarker, new FrameLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
    focusZoom = new ImageView(this);
    focusZoom.setScaleType(ImageView.ScaleType.CENTER_CROP);
    focusZoom.setBackground(Ui.shape(Color.WHITE, Ui.dp(this, 8)));
    focusZoom.setClipToOutline(true);
    focusZoom.setVisibility(View.GONE);
    FrameLayout.LayoutParams zoomParams =
        new FrameLayout.LayoutParams(
            Ui.dp(this, 120), Ui.dp(this, 160), Gravity.TOP | Gravity.RIGHT);
    zoomParams.setMargins(Ui.dp(this, 16), Ui.dp(this, 48), Ui.dp(this, 16), 0);
    frame.addView(focusZoom, zoomParams);
    qrChip = Ui.text(this, "", 14, 0xff303030);
    qrChip.setSingleLine(true);
    qrChip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    qrChip.setGravity(Gravity.CENTER_VERTICAL);
    qrChip.setBackground(Ui.shape(0xfffdd835, Ui.dp(this, 20)));
    Ui.padding(qrChip, 16, 8);
    qrChip.setVisibility(View.GONE);
    qrChip.setOnClickListener(v -> showQr());
    FrameLayout.LayoutParams qrParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    qrParams.setMargins(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 24));
    frame.addView(qrChip, qrParams);

    CameraPanel panel = new CameraPanel(this);
    panel.setBackgroundColor(0xfffafafa);
    root.addView(panel);
    LinearLayout controls = Ui.row(this);
    Ui.padding(controls, 8, 0);
    modeButton =
        cameraControl(
            controls,
            "单页扫描",
            () -> {
              showMenu(
                  CameraMenus.scanModes(
                      this,
                      scanMode,
                      mode -> {
                        scanMode = mode;
                        options.doublePage = mode == 1;
                        options.save(this);
                        getPreferences(MODE_PRIVATE).edit().putInt("scan_mode", mode).apply();
                        updateControls();
                        guide.clear();
                        stableSince = 0;
                      }));
            });
    flashButton =
        cameraControl(
            controls,
            "闪光灯关",
            () -> {
              if (!flashAvailable) {
                Toast.makeText(this, "此相机没有闪光灯", Toast.LENGTH_SHORT).show();
                return;
              }
              // Off → automatic flash → continuous light → capture flash → off.
              flashMode = flashMode == 0 ? 2 : flashMode == 2 ? 3 : flashMode == 3 ? 1 : 0;
              getPreferences(MODE_PRIVATE).edit().putInt("flash_mode", flashMode).apply();
              flash = flashMode == 3;
              updatePreview();
              updateControls();
            });
    timerButton =
        cameraControl(
            controls,
            "无定时",
            () -> {
              timerMode = (timerMode + 1) % 5;
              getPreferences(MODE_PRIVATE).edit().putInt("timer_mode", timerMode).apply();
              updateTimer();
              if (timerMode == 4
                  && !getPreferences(MODE_PRIVATE).getBoolean("custom_timer_notice", false)) {
                Toast.makeText(this, "您可以在“设置”中设置计时器的时间。", Toast.LENGTH_SHORT).show();
                getPreferences(MODE_PRIVATE).edit().putBoolean("custom_timer_notice", true).apply();
              }
              updateControls();
            });
    ((View) timerButton.getParent())
        .setOnLongClickListener(
            v -> {
              startActivity(new Intent(this, SettingsActivity.class).putExtra("screen", "timer"));
              return true;
            });
    autoButton =
        cameraControl(
            controls,
            "自动拍摄关",
            () -> {
              auto = !auto;
              getPreferences(MODE_PRIVATE).edit().putBoolean("auto", auto).apply();
              armed = true;
              stableSince = 0;
              updateControls();
            });
    ImageView more = cameraControl(controls, "更多", this::more);
    more.setImageResource(R.drawable.myflat_ic_camera_more);
    more.setImageTintList(android.content.res.ColorStateList.valueOf(0xff808080));
    more.setLayoutParams(
        new FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28), Gravity.CENTER));
    panel.addView(controls);

    recent = new ImageView(this);
    recent.setScaleType(ImageView.ScaleType.CENTER_CROP);
    recent.setBackgroundColor(0xfff5f5f5);
    recent.setContentDescription("最近扫描页");
    recent.setOnClickListener(
        v -> {
          if (recentPage == null) return;
          DocumentStore.Page page = MyFlatApp.of(this).store.page(recentPage);
          if (page != null && "ready".equals(page.status))
            startActivity(
                new Intent(this, EditorActivity.class)
                    .putExtra("page", recentPage)
                    .putExtra("folder", folder));
          else Toast.makeText(this, "扫描页正在处理中", Toast.LENGTH_SHORT).show();
        });
    panel.addView(recent);

    FrameLayout button = new FrameLayout(this);
    button.setBackground(Ui.shape(0xffdedfe0, Ui.dp(this, 48)));
    button.setContentDescription("快门");
    View inner = new View(this);
    inner.setBackground(Ui.shape(Color.WHITE, Ui.dp(this, 48)));
    FrameLayout.LayoutParams inside = new FrameLayout.LayoutParams(-1, -1);
    int ring = Ui.dp(this, 7);
    inside.setMargins(ring, ring, ring, ring);
    button.addView(inner, inside);
    shutter = button;
    button.setOnClickListener(v -> shootWithTimer());
    panel.addView(button);

    LinearLayout library = Ui.column(this);
    library.setGravity(Gravity.CENTER);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.myflat_ic_library);
    library.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
    library.addView(Ui.spacer(this, 5));
    TextView text = Ui.text(this, "库", 16, Ui.MUTED);
    text.setGravity(Gravity.CENTER);
    library.addView(text);
    library.setContentDescription("库");
    library.setOnClickListener(v -> library());
    panel.addView(library);
    rotatingControls.add(library);
    rotatingControls.add(recent);

    LinearLayout folderBox = Ui.row(this);
    folderBox.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable border = Ui.shape(Color.WHITE, Ui.dp(this, 24));
    border.setStroke(Ui.dp(this, 1), 0xffeeeeee);
    folderBox.setBackground(border);
    Ui.padding(folderBox, 16, 0);
    folderLabel = Ui.text(this, folderName(), 14, Ui.MUTED);
    AppAppearance.keepText(folderLabel);
    folderLabel.setSingleLine(true);
    folderLabel.setMaxWidth(Ui.dp(this, 180));
    folderLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
    folderBox.addView(folderLabel);
    Space gap = new Space(this);
    folderBox.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 18), 1));
    Ui.Icon up = new Ui.Icon(this, "chevron_up");
    up.color = Ui.MUTED;
    folderBox.addView(up, new LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)));
    folderBox.setContentDescription("选择保存文件夹");
    folderBox.setOnClickListener(v -> chooseFolder());
    panel.addView(folderBox);
    updateControls();
    updateRecent();
  }

  private ImageView cameraControl(LinearLayout row, String description, Runnable click) {
    FrameLayout hit = new FrameLayout(this);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    hit.addView(
        image, new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
    row.addView(hit, new LinearLayout.LayoutParams(0, -1, 1));
    hit.setContentDescription(description);
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    hit.setBackgroundResource(android.R.drawable.list_selector_background);
    hit.setOnClickListener(v -> click.run());
    rotatingControls.add(image);
    return image;
  }

  private void controlState(ImageView button, int drawable, boolean selected, String description) {
    button.setImageResource(drawable);
    button.setImageTintList(
        android.content.res.ColorStateList.valueOf(selected ? Ui.GREEN : 0xff808080));
    ((View) button.getParent()).setContentDescription(description);
  }

  private void updateControls() {
    controlState(
        modeButton,
        scanMode == 2
            ? R.drawable.myflat_ic_camera_mode_auto
            : options.doublePage
                ? R.drawable.myflat_ic_camera_page_two
                : R.drawable.myflat_ic_camera_page_one,
        false,
        scanMode == 2 ? "自动检测扫描页" : options.doublePage ? "双页扫描" : "单页扫描");
    controlState(
        flashButton,
        flashMode == 1
            ? R.drawable.myflat_ic_camera_flash_on
            : flashMode == 2
                ? R.drawable.myflat_ic_camera_flash_auto
                : flashMode == 3
                    ? R.drawable.myflat_ic_camera_flash_always
                    : R.drawable.myflat_ic_camera_flash_off,
        flashMode != 0,
        flashMode == 1 ? "闪光灯开" : flashMode == 2 ? "闪光灯自动" : flashMode == 3 ? "闪光灯常亮" : "闪光灯关");
    controlState(
        timerButton,
        timer == 3
            ? R.drawable.myflat_ic_camera_timer_3
            : timer == 4
                ? R.drawable.myflat_ic_camera_timer_4
                : timer == 5
                    ? R.drawable.myflat_ic_camera_timer_5
                    : R.drawable.myflat_ic_camera_timer_off,
        timer > 0,
        timerSeconds == 0 ? "无定时" : timerLabel(timerSeconds) + "秒定时");
    if (timerMode == 4) {
      Bitmap symbol = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
      Canvas image = new Canvas(symbol);
      android.graphics.drawable.Drawable face =
          getDrawable(R.drawable.myflat_ic_camera_timer_custom);
      face.setBounds(0, 0, 96, 96);
      face.draw(image);
      Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
      number.setColor(Color.BLACK);
      number.setTextAlign(Paint.Align.CENTER);
      number.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
      number.setTextSize(timerSeconds < 10 && timerSeconds == Math.floor(timerSeconds) ? 38 : 27);
      image.drawText(timerLabel(timerSeconds), 48, 67, number);
      timerButton.setImageBitmap(symbol);
    }
    controlState(autoButton, R.drawable.myflat_ic_camera_auto, auto, auto ? "自动拍摄开" : "自动拍摄关");
    idleHint();
  }

  private void idleHint() {
    if (hint == null || capturing || SystemClock.elapsedRealtime() < hintUntil) return;
    if (!getSharedPreferences("preferences", 0).getBoolean("show_scan_guide", true)) {
      hint.setVisibility(View.GONE);
      return;
    }
    if (walkthrough != null && walkthrough.isShowing()) {
      hint.setVisibility(View.GONE);
      return;
    }
    String message;
    if (detectorFailed) message = "页面检测暂不可用，可手动拍摄";
    else
      switch (detection.state) {
        case TILTED:
          message = "请把文件和水平对齐";
          break;
        case TOO_CLOSE:
          message = "请在更远的地方扫描";
          break;
        case OUT_OF_BOUNDS:
          message = "将所有四个角都放在里面";
          break;
        case MISALIGNED_GUTTER:
          message = "请将书的中心与屏幕上的垂直线对齐";
          break;
        case PAGE_INVALID:
          message = "正在寻找扫描目标…";
          break;
        default:
          message = auto ? (armed ? "保持稳定 · 即将拍摄" : "翻页后自动拍摄下一页") : "";
      }
    hint.setText(message);
    hint.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private void temporaryHint(String message, long duration) {
    hintUntil = SystemClock.elapsedRealtime() + duration;
    hint.setText(message);
    hint.setVisibility(View.VISIBLE);
    main.postDelayed(this::idleHint, duration);
  }

  private boolean overlayOpen() {
    return (walkthrough != null && walkthrough.isShowing())
        || (cameraMenu != null && cameraMenu.isShowing());
  }

  private void showMenu(Dialog dialog) {
    if (capturing) return;
    if (cameraMenu != null) cameraMenu.dismiss();
    cameraMenu = dialog;
    stableSince = 0;
    dialog.setOnDismissListener(
        d -> {
          if (cameraMenu == dialog) cameraMenu = null;
          stableSince = 0;
        });
    dialog.show();
  }

  private void more() {
    if (capturing) return;
    showMenu(
        CameraMenus.more(
            this,
            options,
            shutterSound,
            selected -> {
              shutterSound = selected;
              getPreferences(MODE_PRIVATE).edit().putBoolean("shutter_sound", selected).apply();
            },
            this::settings,
            this::resolution,
            () -> Support.show(this),
            this::importImages,
            () -> {
              options.save(this);
              updateControls();
            }));
  }

  private void resolution() {
    if (capturing || opening) return;
    try {
      PhotoResolution.Configuration config =
          photoResolutions != null ? photoResolutions : PhotoResolution.discover(this);
      showMenu(
          PhotoResolution.picker(
              this,
              config,
              () -> {
                stableSince = 0;
                detection = LivePageDetector.Result.searching();
                guide.clear();
                close();
                open();
              }));
    } catch (Exception error) {
      showError(error);
    }
  }

  private void showGuide(int step) {
    if (walkthrough != null && walkthrough.isShowing()) return;
    stableSince = 0;
    walkthrough =
        new ScanGuideDialog(
            this,
            step,
            () -> getPreferences(MODE_PRIVATE).edit().putBoolean("scan_guide_seen", true).apply());
    walkthrough.setOnDismissListener(
        d -> {
          guideStep = 0;
          walkthrough = null;
          stableSince = 0;
          if (resumed) {
            enableCamera();
            idleHint();
          }
        });
    walkthrough.show();
    idleHint();
  }

  private void enableCamera() {
    if (!resumed || !securityReady || (walkthrough != null && walkthrough.isShowing())) return;
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 10);
    else if (preview.isAvailable()) open();
  }

  private void importImages() {
    startActivity(
        new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("folder", folder)
            .putExtra("import", true));
  }

  private String folderName() {
    for (DocumentStore.Folder f : MyFlatApp.of(this).store.folders())
      if (f.id == folder) return f.name;
    return "默认文件夹";
  }

  private void chooseFolder() {
    if (capturing) return;
    showMenu(
        CameraMenus.folders(
            this,
            MyFlatApp.of(this).store.folders(),
            folder,
            this::selectFolder,
            this::allFolders));
  }

  private void allFolders() {
    List<DocumentStore.Folder> folders = MyFlatApp.of(this).store.folders();
    String[] names = new String[folders.size() + 1];
    for (int i = 0; i < folders.size(); i++) names[i] = folders.get(i).name;
    names[folders.size()] = "＋ 新建文件夹";
    showMenu(
        Ui.alert(this)
            .setTitle("保存到文件夹")
            .setItems(
                names,
                (d, which) -> {
                  if (which == folders.size()) newFolder();
                  else selectFolder(folders.get(which).id);
                })
            .create());
  }

  private void newFolder() {
    EditText name = Ui.input(this, "", "名称");
    LinearLayout body = Ui.column(this);
    Ui.padding(body, 24, 8);
    body.addView(name);
    showMenu(
        Ui.alert(this)
            .setTitle("新建文件夹")
            .setView(body)
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "保存",
                (dialog, which) ->
                    selectFolder(MyFlatApp.of(this).store.createFolder(name.getText().toString())))
            .create());
  }

  private void selectFolder(long id) {
    FolderSecurity.require(
        this,
        id,
        () -> {
          folder = id;
          getPreferences(MODE_PRIVATE).edit().putLong("folder", folder).apply();
          if (documentId != null) MyFlatApp.of(this).store.moveDocument(documentId, folder);
          folderLabel.setText(folderName());
          updateRecent();
        },
        null);
  }

  private void library() {
    startActivity(
        new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("folder", 0L));
  }

  private void updateRecent() {
    DocumentStore store = MyFlatApp.of(this).store;
    List<DocumentStore.Page> pages = new ArrayList<>();
    if (documentId != null) pages = store.pages(documentId);
    else {
      for (DocumentStore.Document d : store.documents(folder, "", false)) {
        pages = store.pages(d.id);
        if (!pages.isEmpty()) break;
      }
    }
    if (pages.isEmpty()) {
      recentPage = null;
      recent.setImageDrawable(null);
    } else {
      DocumentStore.Page page = pages.get(pages.size() - 1);
      recentPage = page.id;
      try {
        Ui.thumbnail(recent, store.file("ready".equals(page.status) ? page.result : page.original));
      } catch (IOException ignored) {
      }
    }
  }

  /** Full-bleed 3:4 preview and the original two-level camera controls. */
  private static final class CameraLayout extends ViewGroup {
    CameraLayout(Context c) {
      super(c);
      setBackgroundColor(0xfffafafa);
    }

    @Override
    protected void onMeasure(int w, int h) {
      int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h);
      int cameraHeight = Math.min(Math.round(width * 4f / 3), Math.round(height * .70f));
      getChildAt(0)
          .measure(
              MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(cameraHeight, MeasureSpec.EXACTLY));
      getChildAt(1)
          .measure(
              MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(height - cameraHeight, MeasureSpec.EXACTLY));
      setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      View camera = getChildAt(0);
      int height = camera.getMeasuredHeight();
      camera.layout(0, 0, getWidth(), height);
      getChildAt(1).layout(0, height, getWidth(), getHeight());
    }
  }

  private static final class CameraPanel extends ViewGroup {
    CameraPanel(Context c) {
      super(c);
    }

    private int dp(float value) {
      return Ui.dp(getContext(), value);
    }

    @Override
    protected void onMeasure(int w, int h) {
      int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h);
      getChildAt(0)
          .measure(
              MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(dp(44), MeasureSpec.EXACTLY));
      int[] sizes = {82, 80, 84};
      for (int i = 1; i <= 3; i++)
        getChildAt(i)
            .measure(
                MeasureSpec.makeMeasureSpec(dp(sizes[i - 1]), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(sizes[i - 1]), MeasureSpec.EXACTLY));
      getChildAt(4)
          .measure(
              MeasureSpec.makeMeasureSpec(width - dp(16), MeasureSpec.AT_MOST),
              MeasureSpec.makeMeasureSpec(dp(36), MeasureSpec.EXACTLY));
      setMeasuredDimension(width, height);
    }

    private void center(View v, int x, int y) {
      int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
      v.layout(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int width = getWidth(), height = getHeight();
      View controls = getChildAt(0);
      controls.layout(0, dp(4), width, dp(4) + controls.getMeasuredHeight());
      int y = Math.round(height * .55f);
      center(getChildAt(1), Math.round(width * .19f), y);
      center(getChildAt(2), width / 2, y);
      center(getChildAt(3), Math.round(width * .82f), y);
      View folder = getChildAt(4);
      int fy = Math.round(height * .77f) - folder.getMeasuredHeight() / 2;
      folder.layout(dp(8), fy, dp(8) + folder.getMeasuredWidth(), fy + folder.getMeasuredHeight());
    }
  }

  private void settings() {
    startActivity(new Intent(this, SettingsActivity.class));
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    options = ScanSettings.defaults(this);
    if (scanMode != 2) options.doublePage = scanMode == 1;
    updateTimer();
    updateControls();
    if (orientation != null) orientation.enable();
    capturing = false;
    shutter.setAlpha(1f);
    updateRecent();
    folderLabel.setText(folderName());
    cameraThread = new HandlerThread("myflat-camera");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());
    analysis = new PreviewAnalysis();
    analysisBusy = false;
    detectorFailed = false;
    detection = LivePageDetector.Result.searching();
    hintUntil = 0;
    resumeCameraFlow();
    idleHint();
    main.postDelayed(stability, 600);
  }

  private void resumeCameraFlow() {
    if (!securityReady || !resumed) return;
    if (!getPreferences(MODE_PRIVATE).getBoolean("scan_guide_seen", false)) showGuide(guideStep);
    else enableCamera();
  }

  @Override
  protected void onPause() {
    resumed = false;
    if (orientation != null) orientation.disable();
    main.removeCallbacksAndMessages(null);
    hideFocusZoom();
    if (focusMarker != null) focusMarker.setVisibility(View.GONE);
    if (qrChip != null) qrChip.setVisibility(View.GONE);
    close();
    if (cameraThread != null) {
      cameraThread.quitSafely();
      try {
        cameraThread.join(1500);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      cameraThread = null;
      cameraHandler = null;
    }
    PreviewAnalysis old = analysis;
    analysis = null;
    if (old != null) old.close();
    lastPixels = null;
    stableSince = 0;
    guide.clear();
    capturing = false;
    super.onPause();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 10) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
        if (preview.isAvailable()) open();
      } else
        Ui.alert(this)
            .setTitle("需要相机权限")
            .setMessage("拍摄书页需要相机权限。你也可以回到资料库导入图片或 PDF。")
            .setPositiveButton(
                "返回资料库",
                (d, w) -> {
                  library();
                  finish();
                })
            .show();
    }
  }

  private final TextureView.SurfaceTextureListener surfaceListener =
      new TextureView.SurfaceTextureListener() {
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
          if (resumed
              && (walkthrough == null || !walkthrough.isShowing())
              && checkSelfPermission(Manifest.permission.CAMERA)
                  == PackageManager.PERMISSION_GRANTED) open();
        }

        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
          transform();
        }

        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
          return true;
        }

        public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
      };

  @SuppressLint("MissingPermission")
  private void open() {
    if (camera != null
        || legacy != null
        || opening
        || !resumed
        || !securityReady
        || cameraHandler == null
        || (walkthrough != null && walkthrough.isShowing())) return;
    opening = true;
    if (getSharedPreferences("preferences", 0).getInt("camera_api", 2) == 1) {
      try {
        legacy = new LegacyCapture(this, preview.getSurfaceTexture());
        standardPhotoSize = legacy.photoSize;
        photoResolutions = legacy.resolutions;
        previewSize = legacy.previewSize;
        sensorOrientation = legacy.orientation;
        flashAvailable = legacy.flashAvailable;
        getSharedPreferences("preferences", 0)
            .edit()
            .putInt("active_jpeg_width", legacy.photoSize.getWidth())
            .putInt("active_jpeg_height", legacy.photoSize.getHeight())
            .apply();
        updatePreview();
        transform();
      } catch (Exception ex) {
        legacy = null;
        showError(ex);
      }
      opening = false;
      return;
    }
    try {
      CameraManager manager = getSystemService(CameraManager.class);
      String fallback = null;
      for (String id : manager.getCameraIdList()) {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        if (fallback == null) fallback = id;
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
          fallback = id;
          break;
        }
      }
      if (fallback == null) throw new IOException("没有可用相机");
      cameraId = fallback;
      CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
      Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
      if (orientation != null) sensorOrientation = orientation;
      flashAvailable = Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
      sensorArea = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
      Integer maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
      Integer maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
      afRegions = maxAf == null ? 0 : maxAf;
      aeRegions = maxAe == null ? 0 : maxAe;
      StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      if (map == null) throw new IOException("相机不支持图像输出");
      standardPhotoSize =
          largest(
              map.getOutputSizes(android.graphics.ImageFormat.JPEG),
              map.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG));
      photoResolutions = PhotoResolution.camera2(cameraId, c);
      PhotoResolution.Option selected = photoResolutions.selected(this);
      Size jpeg = selected.size;
      maximumPixelMode = selected.maximum;
      previewSize =
          choose(
              map.getOutputSizes(SurfaceTexture.class),
              1440,
              (float) Math.max(jpeg.getWidth(), jpeg.getHeight())
                  / Math.min(jpeg.getWidth(), jpeg.getHeight()));
      reader =
          ImageReader.newInstance(
              jpeg.getWidth(), jpeg.getHeight(), android.graphics.ImageFormat.JPEG, 2);
      reader.setOnImageAvailableListener(this::imageAvailable, cameraHandler);
      manager.openCamera(
          cameraId,
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
              opening = false;
              if (!resumed) {
                device.close();
                return;
              }
              camera = device;
              startPreview();
            }

            @Override
            public void onDisconnected(CameraDevice device) {
              opening = false;
              device.close();
              camera = null;
              showError(new IOException("相机已断开"));
            }

            @Override
            public void onError(CameraDevice device, int error) {
              opening = false;
              device.close();
              camera = null;
              showError(new IOException("无法打开相机（" + error + "）"));
            }
          },
          cameraHandler);
    } catch (Exception ex) {
      opening = false;
      showError(ex);
    }
  }

  private static Size choose(Size[] choices, int max, float aspect) {
    if (choices == null || choices.length == 0) return new Size(1280, 960);
    Size best = choices[0];
    double score = Double.MAX_VALUE;
    for (Size size : choices) {
      int edge = Math.max(size.getWidth(), size.getHeight());
      float ratio =
          (float) Math.max(size.getWidth(), size.getHeight())
              / Math.min(size.getWidth(), size.getHeight());
      double s = Math.abs(ratio - aspect) * 10000 + Math.abs(edge - max) + (edge > max ? 10000 : 0);
      if (s < score) {
        score = s;
        best = size;
      }
    }
    return best;
  }

  private boolean maximumPixelMode;
  private Size standardPhotoSize;
  private PhotoResolution.Configuration photoResolutions;

  static Size largest(Size[]... groups) {
    Size best = null;
    for (Size[] sizes : groups)
      if (sizes != null)
        for (Size size : sizes)
          if (best == null
              || (long) size.getWidth() * size.getHeight()
                  > (long) best.getWidth() * best.getHeight()) best = size;
    if (best == null) throw new IllegalArgumentException("相机没有 JPEG 输出尺寸");
    return best;
  }

  private void startPreview() {
    try {
      SurfaceTexture texture = preview.getSurfaceTexture();
      if (texture == null || camera == null) return;
      texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
      Surface surface = new Surface(texture);
      previewSurface = surface;
      repeating = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      repeating.addTarget(surface);
      repeating.set(
          CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      android.hardware.camera2.params.OutputConfiguration previewOutput =
          new android.hardware.camera2.params.OutputConfiguration(surface);
      android.hardware.camera2.params.OutputConfiguration photoOutput =
          new android.hardware.camera2.params.OutputConfiguration(reader.getSurface());
      if (Build.VERSION.SDK_INT >= 31 && maximumPixelMode) {
        previewOutput.addSensorPixelModeUsed(CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT);
        photoOutput.addSensorPixelModeUsed(CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
      }
      camera.createCaptureSessionByOutputConfigurations(
          Arrays.asList(previewOutput, photoOutput),
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
              if (camera == null) {
                s.close();
                return;
              }
              session = s;
              getSharedPreferences("preferences", 0)
                  .edit()
                  .putInt("active_jpeg_width", reader.getWidth())
                  .putInt("active_jpeg_height", reader.getHeight())
                  .apply();
              updatePreview();
              runOnUiThread(
                  () -> {
                    transform();
                    idleHint();
                  });
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
              s.close();
              if (!retryStandardPreview()) showError(new IOException("相机预览配置失败"));
            }
          },
          cameraHandler);
    } catch (Exception ex) {
      if (!retryStandardPreview()) showError(ex);
    }
  }

  private boolean retryStandardPreview() {
    if (!maximumPixelMode || standardPhotoSize == null || camera == null || !resumed) return false;
    maximumPixelMode = false;
    if (photoResolutions != null) {
      for (PhotoResolution.Option option : photoResolutions.options)
        if (option.size.equals(standardPhotoSize)) {
          photoResolutions.select(this, option);
          break;
        }
    }
    runOnUiThread(
        () ->
            Toast.makeText(
                    this,
                    "相机无法使用该尺寸，已切换为 "
                        + standardPhotoSize.getWidth()
                        + " × "
                        + standardPhotoSize.getHeight(),
                    Toast.LENGTH_LONG)
                .show());
    if (session != null) {
      session.close();
      session = null;
    }
    if (reader != null) reader.close();
    if (previewSurface != null) {
      previewSurface.release();
      previewSurface = null;
    }
    reader =
        ImageReader.newInstance(
            standardPhotoSize.getWidth(),
            standardPhotoSize.getHeight(),
            android.graphics.ImageFormat.JPEG,
            2);
    reader.setOnImageAvailableListener(this::imageAvailable, cameraHandler);
    startPreview();
    return true;
  }

  private void transform() {
    if (preview == null) return;
    int w = preview.getWidth(), h = preview.getHeight();
    if (w == 0 || h == 0) return;
    boolean swapped = sensorOrientation % 180 != 0;
    float bw = swapped ? previewSize.getHeight() : previewSize.getWidth(),
        bh = swapped ? previewSize.getWidth() : previewSize.getHeight();
    float viewRatio = (float) w / h, bufferRatio = bw / bh;
    Matrix matrix = new Matrix();
    if (viewRatio > bufferRatio) matrix.setScale(1, viewRatio / bufferRatio, w / 2f, h / 2f);
    else matrix.setScale(bufferRatio / viewRatio, 1, w / 2f, h / 2f);
    preview.setTransform(matrix);
  }

  private void updatePreview() {
    if (legacy != null) {
      try {
        legacy.flash(flashMode);
      } catch (RuntimeException ex) {
        showError(ex);
      }
      return;
    }
    if (session == null || repeating == null || cameraHandler == null) return;
    try {
      repeating.set(
          CaptureRequest.CONTROL_AE_MODE,
          flashAvailable && flashMode == 2
              ? CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
              : flashAvailable && flashMode == 1
                  ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                  : CaptureRequest.CONTROL_AE_MODE_ON);
      repeating.set(
          CaptureRequest.FLASH_MODE,
          flashAvailable && flashMode == 3
              ? CaptureRequest.FLASH_MODE_TORCH
              : CaptureRequest.FLASH_MODE_OFF);
      session.setRepeatingRequest(repeating.build(), null, cameraHandler);
    } catch (CameraAccessException | IllegalStateException ex) {
      showError(ex);
    }
  }

  private static String timerLabel(float seconds) {
    return seconds == Math.floor(seconds)
        ? Integer.toString((int) seconds)
        : Float.toString(seconds);
  }

  private void updateTimer() {
    SharedPreferences preferences = getSharedPreferences("preferences", 0);
    timerSeconds =
        timerMode == 0
            ? 0
            : timerMode == 1
                ? 3
                : timerMode == 2
                    ? 4
                    : timerMode == 3
                        ? 5
                        : preferences.getFloat("timer_seconds", preferences.getInt("timer", 7));
    timerSeconds = Math.max(0, Math.min(20, timerSeconds));
    timer = Math.round(timerSeconds);
  }

  private void focus() {
    focus(preview.getWidth() / 2f, preview.getHeight() / 2f);
  }

  private void focus(float viewX, float viewY) {
    if (legacy != null) {
      if (!getSharedPreferences("preferences", 0).getBoolean("explicit_focus", true)) return;
      legacy.focus(
          viewX / Math.max(1, preview.getWidth()), viewY / Math.max(1, preview.getHeight()));
      return;
    }
    if (session == null
        || repeating == null
        || !getSharedPreferences("preferences", 0).getBoolean("explicit_focus", true)) return;
    try {
      if (sensorArea != null && preview.getWidth() > 0 && preview.getHeight() > 0) {
        Matrix inverse = new Matrix();
        preview.getTransform(new Matrix()).invert(inverse);
        float[] point = {viewX, viewY};
        inverse.mapPoints(point);
        float nx = Math.max(0, Math.min(1, point[0] / preview.getWidth()));
        float ny = Math.max(0, Math.min(1, point[1] / preview.getHeight()));
        if (sensorOrientation == 90) {
          float old = nx;
          nx = ny;
          ny = 1 - old;
        } else if (sensorOrientation == 270) {
          float old = nx;
          nx = 1 - ny;
          ny = old;
        } else if (sensorOrientation == 180) {
          nx = 1 - nx;
          ny = 1 - ny;
        }
        Rect region = repeating.get(CaptureRequest.SCALER_CROP_REGION);
        if (region == null) region = sensorArea;
        int side = Math.max(1, Math.min(region.width(), region.height()) / 12);
        int x = region.left + Math.round(nx * region.width()),
            y = region.top + Math.round(ny * region.height());
        int left = Math.max(region.left, Math.min(region.right - side, x - side / 2));
        int top = Math.max(region.top, Math.min(region.bottom - side, y - side / 2));
        MeteringRectangle[] target = {
          new MeteringRectangle(
              new Rect(left, top, left + side, top + side), MeteringRectangle.METERING_WEIGHT_MAX)
        };
        if (afRegions > 0) repeating.set(CaptureRequest.CONTROL_AF_REGIONS, target);
        if (aeRegions > 0) repeating.set(CaptureRequest.CONTROL_AE_REGIONS, target);
      }
      repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
      session.capture(repeating.build(), null, cameraHandler);
      repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
      session.setRepeatingRequest(repeating.build(), null, cameraHandler);
      FrameLayout.LayoutParams marker = (FrameLayout.LayoutParams) focusMarker.getLayoutParams();
      marker.leftMargin =
          Math.max(
              0,
              Math.min(preview.getWidth() - Ui.dp(this, 32), Math.round(viewX) - Ui.dp(this, 16)));
      marker.topMargin =
          Math.max(
              0,
              Math.min(preview.getHeight() - Ui.dp(this, 32), Math.round(viewY) - Ui.dp(this, 16)));
      focusMarker.setLayoutParams(marker);
      focusMarker.setVisibility(View.VISIBLE);
      main.postDelayed(() -> focusMarker.setVisibility(View.GONE), 1300);
    } catch (CameraAccessException | IllegalStateException ex) {
      showError(ex);
    }
  }

  private void showFocusZoom(float x, float y) {
    if (!preview.isAvailable() || capturing || overlayOpen()) return;
    hideFocusZoom();
    Bitmap frame = preview.getBitmap();
    if (frame == null) return;
    int width = Math.max(1, frame.getWidth() / 4), height = Math.max(1, frame.getHeight() / 4);
    int left = Math.max(0, Math.min(frame.getWidth() - width, Math.round(x) - width / 2));
    int top = Math.max(0, Math.min(frame.getHeight() - height, Math.round(y) - height / 2));
    zoomBitmap = Bitmap.createBitmap(frame, left, top, width, height);
    if (zoomBitmap != frame) frame.recycle();
    focusZoom.setImageBitmap(zoomBitmap);
    focusZoom.setVisibility(View.VISIBLE);
  }

  private void hideFocusZoom() {
    if (focusZoom != null) {
      focusZoom.setVisibility(View.GONE);
      focusZoom.setImageDrawable(null);
    }
    if (zoomBitmap != null) {
      zoomBitmap.recycle();
      zoomBitmap = null;
    }
  }

  private void showQr() {
    if (qrDetection == null || capturing) return;
    CameraQrDetector.Detection target = qrDetection;
    showMenu(
        CameraMenus.qr(
            this,
            target.title,
            target.description,
            () -> {
              try {
                startActivity(target.intent());
              } catch (ActivityNotFoundException error) {
                Toast.makeText(this, "没有可打开此内容的应用", Toast.LENGTH_SHORT).show();
              }
            }));
  }

  private void updateQr(CameraQrDetector.Detection found) {
    if (!getSharedPreferences("preferences", 0).getBoolean("qr", true)) {
      qrDetection = null;
      qrChip.setVisibility(View.GONE);
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (found != null) {
      qrDetection = found;
      qrSeen = now;
      qrChip.setText(found.title + "  ›");
      qrChip.setContentDescription("二维码：" + found.description);
      qrChip.setVisibility(View.VISIBLE);
    } else if (now - qrSeen > 1500) {
      qrDetection = null;
      qrChip.setVisibility(View.GONE);
    }
  }

  private void shootWithTimer() {
    if (capturing
        || overlayOpen()
        || (legacy == null && (camera == null || session == null))
        || !securityReady
        || !FolderSecurity.isAccessible(this, folder)) return;
    capturing = true;
    shutter.setAlpha(.45f);
    focus();
    if (timerSeconds == 0) main.postDelayed(this::captureStill, 300);
    else countdownUntil(SystemClock.elapsedRealtime() + Math.round(timerSeconds * 1000));
  }

  private void countdownUntil(long deadline) {
    if (!resumed) return;
    long remaining = deadline - SystemClock.elapsedRealtime();
    if (remaining <= 0) {
      captureStill();
      return;
    }
    hint.setVisibility(View.VISIBLE);
    hint.setText(Integer.toString((int) Math.ceil(remaining / 1000.0)));
    main.postDelayed(() -> countdownUntil(deadline), Math.min(remaining, 100));
  }

  private void captureStill() {
    if (legacy != null) {
      if (!resumed || !FolderSecurity.isAccessible(this, folder)) {
        unlock();
        return;
      }
      int direction = getSharedPreferences("preferences", 0).getInt("direction", 0);
      int rotation = direction == 1 ? 0 : direction == 2 ? 90 : deviceOrientation;
      try {
        if (getSharedPreferences("preferences", 0).getBoolean("haptic", false))
          shutter.performHapticFeedback(
              Build.VERSION.SDK_INT >= 30
                  ? HapticFeedbackConstants.CONFIRM
                  : HapticFeedbackConstants.KEYBOARD_TAP);
        legacy.take(rotation, shutterSound, this::saveCaptureBytes);
      } catch (RuntimeException ex) {
        showError(ex);
        unlock();
      }
      return;
    }
    if (!resumed
        || camera == null
        || session == null
        || reader == null
        || !FolderSecurity.isAccessible(this, folder)) {
      unlock();
      return;
    }
    try {
      CaptureRequest.Builder request =
          camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      request.addTarget(reader.getSurface());
      request.set(
          CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      request.set(
          CaptureRequest.CONTROL_AE_MODE,
          flashAvailable && flashMode == 2
              ? CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
              : flashAvailable && flashMode == 1
                  ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                  : CaptureRequest.CONTROL_AE_MODE_ON);
      request.set(
          CaptureRequest.FLASH_MODE,
          flashAvailable && flashMode == 3
              ? CaptureRequest.FLASH_MODE_TORCH
              : CaptureRequest.FLASH_MODE_OFF);
      request.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
      if (Build.VERSION.SDK_INT >= 31 && maximumPixelMode)
        request.set(
            CaptureRequest.SENSOR_PIXEL_MODE, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
      int scanDirection = getSharedPreferences("preferences", 0).getInt("direction", 0);
      int photoOrientation = scanDirection == 1 ? 0 : scanDirection == 2 ? 90 : deviceOrientation;
      request.set(
          CaptureRequest.JPEG_ORIENTATION, (sensorOrientation + photoOrientation + 360) % 360);
      if (getSharedPreferences("preferences", 0).getBoolean("haptic", false))
        shutter.performHapticFeedback(
            Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.KEYBOARD_TAP);
      if (shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK);
      session.capture(
          request.build(),
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureFailed(
                CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
              showError(new IOException("拍摄失败，请重试"));
              runOnUiThread(() -> unlock());
            }
          },
          cameraHandler);
    } catch (Exception ex) {
      showError(ex);
      unlock();
    }
  }

  private void imageAvailable(ImageReader source) {
    byte[] bytes;
    try (Image image = source.acquireLatestImage()) {
      if (image == null) return;
      ByteBuffer buffer = image.getPlanes()[0].getBuffer();
      bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
    } catch (IllegalStateException closedReader) {
      return;
    }
    saveCaptureBytes(bytes);
  }

  private void saveCaptureBytes(byte[] bytes) {
    ScanSettings settings = ScanSettings.parse(options.json());
    if (rescanPage != null) settings.doublePage = false;
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              File temporary = null;
              try {
                temporary = File.createTempFile("capture-", ".jpg", getCacheDir());
                try (OutputStream out = new FileOutputStream(temporary)) {
                  out.write(bytes);
                }
                if (rescanPage != null) {
                  MyFlatApp.of(this).store.replaceScan(rescanPage, temporary, settings);
                  MyFlatApp.of(this).startProcessing();
                  runOnUiThread(
                      () -> {
                        if (!isDestroyed()) {
                          setResult(RESULT_OK, new Intent().putExtra("page", rescanPage));
                          finish();
                        }
                      });
                  return;
                }
                if (documentId == null)
                  documentId =
                      MyFlatApp.of(this)
                          .store
                          .createDocument(
                              folder,
                              new SimpleDateFormat("扫描 yyyy-MM-dd HH.mm", Locale.getDefault())
                                  .format(new Date()));
                int count =
                    Importer.addUri(
                        this,
                        MyFlatApp.of(this).store,
                        documentId,
                        Uri.fromFile(temporary),
                        settings);
                captured += count;
                runOnUiThread(
                    () -> {
                      if (!isDestroyed()) {
                        updateRecent();
                        unlock();
                        temporaryHint("已保存，翻页后继续拍摄", 1600);
                      }
                    });
              } catch (Exception ex) {
                showError(ex);
                runOnUiThread(this::unlock);
              } finally {
                if (temporary != null) temporary.delete();
              }
            });
  }

  private void unlock() {
    capturing = false;
    lastShot = SystemClock.elapsedRealtime();
    armed = false;
    changeSinceShot = 0;
    stableSince = 0;
    if (shutter != null) shutter.setAlpha(1);
    main.postDelayed(
        () -> {
          if (resumed) idleHint();
        },
        1600);
  }

  private void close() {
    if (legacy != null) {
      legacy.close();
      legacy = null;
    }
    opening = false;
    if (session != null) {
      session.close();
      session = null;
    }
    if (camera != null) {
      camera.close();
      camera = null;
    }
    if (reader != null) {
      reader.close();
      reader = null;
    }
    repeating = null;
    if (previewSurface != null) {
      previewSurface.release();
      previewSurface = null;
    }
  }

  private void showError(Exception ex) {
    runOnUiThread(
        () -> {
          if (!isFinishing() && !isDestroyed()) Ui.error(this, ex);
        });
  }

  @Override
  protected void onDestroy() {
    if (walkthrough != null) walkthrough.dismiss();
    hideFocusZoom();
    if (cameraMenu != null) cameraMenu.dismiss();
    sound.release();
    super.onDestroy();
  }

  private final Runnable stability =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          if (!capturing
              && securityReady
              && FolderSecurity.isAccessible(CameraActivity.this, folder)
              && !analysisBusy
              && !detectorFailed
              && !overlayOpen()
              && hasWindowFocus()
              && preview.isAvailable()
              && (session != null || legacy != null)
              && analysis != null) {
            boolean scanQr = getSharedPreferences("preferences", 0).getBoolean("qr", true);
            int analysisWidth = scanQr ? 576 : 288, analysisHeight = scanQr ? 768 : 384;
            Bitmap frame = preview.getBitmap(analysisWidth, analysisHeight);
            if (frame != null) {
              int[] pixels = new int[analysisWidth * analysisHeight];
              frame.getPixels(pixels, 0, analysisWidth, 0, 0, analysisWidth, analysisHeight);
              double delta = 255;
              if (lastPixels != null && lastPixels.length == pixels.length) {
                delta = 0;
                for (int i = 0; i < pixels.length; i++) {
                  int a = pixels[i], b = lastPixels[i];
                  delta +=
                      (Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                              + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                              + Math.abs((a & 255) - (b & 255)))
                          / 3f;
                }
                delta /= pixels.length;
              }
              lastPixels = pixels;
              analysisBusy = true;
              PreviewAnalysis owner = analysis;
              double movement = delta;
              float split = options.split;
              int previewOrientation = deviceOrientation;
              int requestedMode = scanMode;
              owner.executor.execute(
                  () -> {
                    LivePageDetector.Result result = LivePageDetector.Result.searching();
                    Throwable failure = null;
                    CameraQrDetector.Detection qr = null;
                    try {
                      if (!owner.closed) {
                        if (owner.detector == null)
                          owner.detector = new LivePageDetector(CameraActivity.this);
                        if (scanQr) qr = CameraQrDetector.detect(frame);
                        result =
                            owner.detector.detect(
                                frame, requestedMode == 1, split, previewOrientation);
                        if (requestedMode == 2 && (result.pageType == 3 || result.pageType == 4))
                          result = owner.detector.detect(frame, true, split, previewOrientation);
                      }
                    } catch (Exception | LinkageError error) {
                      failure = error;
                    } finally {
                      frame.recycle();
                    }
                    LivePageDetector.Result answer = result;
                    Throwable error = failure;
                    CameraQrDetector.Detection foundQr = qr;
                    main.post(
                        () -> {
                          if (!resumed || analysis != owner) return;
                          analysisBusy = false;
                          if (error != null) {
                            detectorFailed = true;
                            auto = false;
                            updateControls();
                            guide.clear();
                            return;
                          }
                          if (requestedMode != scanMode || split != options.split) return;
                          if (requestedMode == 2) options.doublePage = answer.outlines.size() == 2;
                          updateQr(foundQr);
                          detection = answer;
                          guide.outlines = answer.outlines;
                          guide.invalidate();
                          long now = SystemClock.elapsedRealtime();
                          changeSinceShot += movement;
                          if (!armed && changeSinceShot > 35 && now - lastShot > 2200) armed = true;
                          if (auto
                              && !capturing
                              && !overlayOpen()
                              && hasWindowFocus()
                              && armed
                              && answer.state == PageGeometry.State.NORMAL
                              && movement < 2.2) {
                            if (stableSince == 0) stableSince = now;
                            if (now - stableSince > 1600) {
                              shootWithTimer();
                              stableSince = 0;
                            }
                          } else stableSince = 0;
                          idleHint();
                        });
                  });
            }
          } else if (overlayOpen() || !hasWindowFocus()) stableSince = 0;
          main.postDelayed(this, 400);
        }
      };

  private static final class PreviewAnalysis implements AutoCloseable {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    volatile boolean closed;
    LivePageDetector detector;

    @Override
    public void close() {
      closed = true;
      executor.execute(
          () -> {
            if (detector != null) detector.close();
          });
      executor.shutdown();
    }
  }

  private final class Guide extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<float[]> outlines = Collections.emptyList();

    Guide(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void clear() {
      outlines = Collections.emptyList();
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      if (!getSharedPreferences("preferences", 0).getBoolean("show_scan_guide", true)) return;
      paint.setColor(Ui.GREEN);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Ui.dp(CameraActivity.this, 2));
      paint.setStrokeJoin(Paint.Join.ROUND);
      for (float[] edge : outlines) {
        path.reset();
        path.moveTo(edge[0] * getWidth(), edge[1] * getHeight());
        for (int i = 2; i < edge.length; i += 2)
          path.lineTo(edge[i] * getWidth(), edge[i + 1] * getHeight());
        path.close();
        canvas.drawPath(path, paint);
      }
      if (options.doublePage && !outlines.isEmpty()) {
        paint.setPathEffect(
            new DashPathEffect(
                new float[] {Ui.dp(CameraActivity.this, 10), Ui.dp(CameraActivity.this, 6)}, 0));
        paint.setColor(Color.WHITE);
        if (detection.stacked)
          canvas.drawLine(
              getWidth() * .06f,
              getHeight() * detection.split,
              getWidth() * .94f,
              getHeight() * detection.split,
              paint);
        else
          canvas.drawLine(
              getWidth() * detection.split,
              getHeight() * .06f,
              getWidth() * detection.split,
              getHeight() * .94f,
              paint);
        paint.setPathEffect(null);
      }
    }
  }
}
