package dev.myflat.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

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
  private ImageView modeButton, flashButton, timerButton, autoButton, recent;
  private String recentPage;
  private View shutter;
  private Guide guide;
  private ScanSettings options;
  private String documentId, cameraId;
  private long folder;
  private boolean flash = false,
      flashAvailable = false,
      auto = false,
      capturing = false,
      resumed = false,
      armed = true;
  private int timer = 0, sensorOrientation = 90, captured = 0;
  private Size previewSize = new Size(1440, 1080);
  private Bitmap lastSmall;
  private long stableSince = 0, lastShot = 0;
  private float changeSinceShot = 0;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    options = ScanSettings.defaults(this);
    documentId =
        state != null ? state.getString("document") : getIntent().getStringExtra("document");
    folder = getIntent().getLongExtra("folder", getPreferences(MODE_PRIVATE).getLong("folder", 1));
    if (state != null) {
      captured = state.getInt("captured");
      folder = state.getLong("folder", folder);
    }
    build();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("document", documentId);
    out.putInt("captured", captured);
    out.putLong("folder", folder);
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
    preview.setOnClickListener(v -> focus());

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
              options.doublePage = !options.doublePage;
              options.save(this);
              updateControls();
              guide.invalidate();
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
              flash = !flash;
              updatePreview();
              updateControls();
            });
    timerButton =
        cameraControl(
            controls,
            "无定时",
            () -> {
              timer = timer == 0 ? 3 : timer == 3 ? 5 : 0;
              updateControls();
            });
    autoButton =
        cameraControl(
            controls,
            "自动拍摄关",
            () -> {
              auto = !auto;
              armed = true;
              stableSince = 0;
              updateControls();
            });
    ImageView more = cameraControl(controls, "更多", this::settings);
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

    LinearLayout folderBox = Ui.row(this);
    folderBox.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable border = Ui.shape(Color.WHITE, Ui.dp(this, 24));
    border.setStroke(Ui.dp(this, 1), 0xffeeeeee);
    folderBox.setBackground(border);
    Ui.padding(folderBox, 16, 0);
    folderLabel = Ui.text(this, folderName(), 14, Ui.MUTED);
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
        options.doublePage
            ? R.drawable.myflat_ic_camera_page_two
            : R.drawable.myflat_ic_camera_page_one,
        false,
        options.doublePage ? "双页扫描" : "单页扫描");
    controlState(
        flashButton,
        flash ? R.drawable.myflat_ic_camera_flash_on : R.drawable.myflat_ic_camera_flash_off,
        flash,
        flash ? "闪光灯开" : "闪光灯关");
    controlState(
        timerButton,
        timer == 3
            ? R.drawable.myflat_ic_camera_timer_3
            : timer == 5
                ? R.drawable.myflat_ic_camera_timer_5
                : R.drawable.myflat_ic_camera_timer_off,
        timer > 0,
        timer == 0 ? "无定时" : timer + "秒定时");
    controlState(autoButton, R.drawable.myflat_ic_camera_auto, auto, auto ? "自动拍摄开" : "自动拍摄关");
    idleHint();
  }

  private void idleHint() {
    hint.setText(auto ? "正在寻找扫描目标…" : "");
    hint.setVisibility(auto ? View.VISIBLE : View.GONE);
  }

  private String folderName() {
    for (DocumentStore.Folder f : MyFlatApp.of(this).store.folders())
      if (f.id == folder) return f.name;
    return "默认文件夹";
  }

  private void chooseFolder() {
    List<DocumentStore.Folder> folders = MyFlatApp.of(this).store.folders();
    String[] names = new String[folders.size() + 1];
    for (int i = 0; i < folders.size(); i++) names[i] = folders.get(i).name;
    names[folders.size()] = "＋ 新建文件夹";
    new AlertDialog.Builder(this)
        .setTitle("保存到文件夹")
        .setItems(
            names,
            (d, which) -> {
              if (which == folders.size())
                Ui.rename(
                    this,
                    "新建文件夹",
                    "",
                    name -> selectFolder(MyFlatApp.of(this).store.createFolder(name)));
              else selectFolder(folders.get(which).id);
            })
        .show();
  }

  private void selectFolder(long id) {
    folder = id;
    getPreferences(MODE_PRIVATE).edit().putLong("folder", folder).apply();
    if (documentId != null) MyFlatApp.of(this).store.moveDocument(documentId, folder);
    folderLabel.setText(folderName());
    updateRecent();
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
    EffectsForm form = new EffectsForm(this, options, true);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form.view);
    new AlertDialog.Builder(this)
        .setTitle("拍摄与处理")
        .setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "应用",
            (d, w) -> {
              options = form.value();
              options.save(this);
              updateControls();
              guide.invalidate();
            })
        .show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    capturing = false;
    shutter.setAlpha(1f);
    updateRecent();
    folderLabel.setText(folderName());
    cameraThread = new HandlerThread("myflat-camera");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 10);
    else if (preview.isAvailable()) open();
    main.postDelayed(stability, 600);
  }

  @Override
  protected void onPause() {
    resumed = false;
    main.removeCallbacksAndMessages(null);
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
    if (lastSmall != null) {
      lastSmall.recycle();
      lastSmall = null;
    }
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
        new AlertDialog.Builder(this)
            .setTitle("需要相机权限")
            .setMessage("拍摄书页需要相机权限。你也可以回到资料库导入图片或 PDF。")
            .setPositiveButton("返回资料库", (d, w) -> finish())
            .show();
    }
  }

  private final TextureView.SurfaceTextureListener surfaceListener =
      new TextureView.SurfaceTextureListener() {
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
          if (resumed
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
    if (camera != null || opening || !resumed || cameraHandler == null) return;
    opening = true;
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
      StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      if (map == null) throw new IOException("相机不支持图像输出");
      Size jpeg = choose(map.getOutputSizes(android.graphics.ImageFormat.JPEG), 3200, 4f / 3);
      previewSize = choose(map.getOutputSizes(SurfaceTexture.class), 1440, 4f / 3);
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
      camera.createCaptureSession(
          Arrays.asList(surface, reader.getSurface()),
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
              if (camera == null) {
                s.close();
                return;
              }
              session = s;
              updatePreview();
              runOnUiThread(
                  () -> {
                    transform();
                    idleHint();
                  });
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
              showError(new IOException("相机预览配置失败"));
            }
          },
          cameraHandler);
    } catch (Exception ex) {
      showError(ex);
    }
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
    if (session == null || repeating == null || cameraHandler == null) return;
    try {
      repeating.set(
          CaptureRequest.FLASH_MODE,
          flash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
      session.setRepeatingRequest(repeating.build(), null, cameraHandler);
    } catch (CameraAccessException | IllegalStateException ex) {
      showError(ex);
    }
  }

  private void focus() {
    if (session == null || repeating == null) return;
    try {
      repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
      session.capture(repeating.build(), null, cameraHandler);
      repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
      hint.setVisibility(View.VISIBLE);
      hint.setText("正在对焦");
      main.postDelayed(
          () -> {
            if (resumed) idleHint();
          },
          800);
    } catch (CameraAccessException | IllegalStateException ex) {
      showError(ex);
    }
  }

  private void shootWithTimer() {
    if (capturing || camera == null || session == null) return;
    capturing = true;
    shutter.setAlpha(.45f);
    focus();
    if (timer == 0) main.postDelayed(this::captureStill, 300);
    else countdown(timer);
  }

  private void countdown(int seconds) {
    if (!resumed) return;
    if (seconds <= 0) {
      captureStill();
      return;
    }
    hint.setVisibility(View.VISIBLE);
    hint.setText("" + seconds);
    main.postDelayed(() -> countdown(seconds - 1), 1000);
  }

  private void captureStill() {
    if (!resumed || camera == null || session == null || reader == null) {
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
          CaptureRequest.FLASH_MODE,
          flash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
      request.set(CaptureRequest.JPEG_QUALITY, (byte) 98);
      int rotation = getWindowManager().getDefaultDisplay().getRotation();
      request.set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation - rotation * 90 + 360) % 360);
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
    ScanSettings settings = ScanSettings.parse(options.json());
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              File temporary = null;
              Bitmap bitmap = null;
              try {
                temporary = File.createTempFile("capture-", ".jpg", getCacheDir());
                try (OutputStream out = new FileOutputStream(temporary)) {
                  out.write(bytes);
                }
                bitmap = Images.decode(this, Uri.fromFile(temporary), 4096);
                if (documentId == null)
                  documentId =
                      MyFlatApp.of(this)
                          .store
                          .createDocument(
                              folder,
                              new SimpleDateFormat("扫描 yyyy-MM-dd HH.mm", Locale.getDefault())
                                  .format(new Date()));
                int count =
                    Importer.add(this, MyFlatApp.of(this).store, documentId, bitmap, settings);
                captured += count;
                runOnUiThread(
                    () -> {
                      if (!isDestroyed()) {
                        updateRecent();
                        hint.setVisibility(View.VISIBLE);
                        hint.setText("已保存，翻页后继续拍摄");
                        unlock();
                      }
                    });
              } catch (Exception ex) {
                showError(ex);
                runOnUiThread(this::unlock);
              } finally {
                if (bitmap != null) bitmap.recycle();
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

  private final Runnable stability =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          if (auto && !capturing && preview.isAvailable() && camera != null) {
            Bitmap small = preview.getBitmap(48, 64);
            if (small != null) {
              int[] pixels = new int[48 * 64];
              small.getPixels(pixels, 0, 48, 0, 0, 48, 64);
              double sum = 0, squares = 0, delta = 255;
              for (int pixel : pixels) {
                float v = (((pixel >> 16) & 255) + ((pixel >> 8) & 255) + (pixel & 255)) / 3f;
                sum += v;
                squares += v * v;
              }
              double mean = sum / pixels.length, variance = squares / pixels.length - mean * mean;
              if (lastSmall != null) {
                int[] previous = new int[pixels.length];
                lastSmall.getPixels(previous, 0, 48, 0, 0, 48, 64);
                delta = 0;
                for (int i = 0; i < pixels.length; i++)
                  delta += Math.abs((pixels[i] & 255) - (previous[i] & 255));
                delta /= pixels.length;
                lastSmall.recycle();
              }
              lastSmall = small;
              long now = SystemClock.elapsedRealtime();
              changeSinceShot += delta;
              if (!armed && changeSinceShot > 35 && now - lastShot > 2200) armed = true;
              // Conservative photometric gate: avoids firing on a blank/dark preview.
              boolean paper = mean > 80 && variance > 400;
              if (armed && paper && delta < 2.2) {
                if (stableSince == 0) stableSince = now;
                hint.setVisibility(View.VISIBLE);
                hint.setText("保持稳定 · 即将拍摄");
                if (now - stableSince > 1600) {
                  shootWithTimer();
                  stableSince = 0;
                }
              } else {
                stableSince = 0;
                hint.setVisibility(View.VISIBLE);
                hint.setText(armed ? "正在寻找扫描目标…" : "翻页后自动拍摄下一页");
              }
            }
          }
          main.postDelayed(this, 400);
        }
      };

  private final class Guide extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Guide(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float x = getWidth() * .06f,
          y = getHeight() * .06f,
          w = getWidth() * .94f,
          h = getHeight() * .94f;
      paint.setColor(0xbbffffff);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Ui.dp(CameraActivity.this, 1));
      // Single-page mode has an unobstructed preview, as in the reference camera.
      if (options.doublePage) {
        paint.setColor(Ui.GREEN);
        paint.setStrokeWidth(Ui.dp(CameraActivity.this, 2));
        canvas.drawLine(getWidth() * options.split, y, getWidth() * options.split, h, paint);
      }
    }
  }
}
