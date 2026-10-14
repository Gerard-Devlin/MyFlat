package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PIN and biometric access protection for local folders; page files remain app-private. */
public final class FolderSecurity {
  private static final String PREFS = "folder_security";
  private static final int ITERATIONS = 120000;
  private static final Set<Long> unlocked = Collections.synchronizedSet(new HashSet<>());
  private static final Map<Activity, Guard> guards = new WeakHashMap<>();
  private static final Map<Activity, PinPrompt> prompts = new WeakHashMap<>();
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final ExecutorService crypto = Executors.newSingleThreadExecutor();
  private static boolean installed;
  private static int started;
  private static final Runnable backgroundLock = FolderSecurity::lockSession;

  private FolderSecurity() {}

  public static void install(Application application) {
    if (installed) return;
    installed = true;
    application.registerActivityLifecycleCallbacks(
        new Application.ActivityLifecycleCallbacks() {
          public void onActivityStarted(Activity a) {
            started++;
            main.removeCallbacks(backgroundLock);
          }

          public void onActivityStopped(Activity a) {
            started = Math.max(0, started - 1);
            if (started == 0 && !a.isChangingConfigurations())
              main.postDelayed(backgroundLock, 400);
          }

          public void onActivityResumed(Activity a) {
            Guard guard = guards.get(a);
            if (guard != null && !accessible(a, guard.folders)) {
              guard.hide();
              requireFolders(a, new ArrayList<>(guard.folders), guard::reveal, a::finish);
            }
          }

          public void onActivityDestroyed(Activity a) {
            guards.remove(a);
            PinPrompt prompt = prompts.remove(a);
            if (prompt != null) prompt.dialog.dismiss();
          }

          public void onActivityCreated(Activity a, Bundle b) {}

          public void onActivityPaused(Activity a) {}

          public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
  }

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static boolean hasPin(Context context) {
    return !preferences(context).getString("hash", "").isEmpty();
  }

  public static boolean locked(Context context, long folder) {
    return folder > 1 && MyFlatApp.of(context).store.folderLocked(folder);
  }

  public static boolean isAccessible(Context context, long folder) {
    return !locked(context, folder) || unlocked.contains(folder);
  }

  public static boolean hasSecureFolders(Context context) {
    for (DocumentStore.Folder folder : MyFlatApp.of(context).store.folders())
      if (locked(context, folder.id)) return true;
    return false;
  }

  private static boolean accessible(Context context, Collection<Long> folders) {
    for (Long folder : folders) if (!isAccessible(context, folder)) return false;
    return true;
  }

  /** A process-local grant never survives backgrounding or a process restart. */
  public static void lockSession() {
    unlocked.clear();
    for (Guard guard : new ArrayList<>(guards.values())) {
      if (accessible(guard.activity, guard.folders)) guard.reveal();
      else guard.hide();
    }
  }

  /** Drop the protected context when navigating back to a nonsensitive library screen. */
  public static void clearProtection(Activity activity) {
    Guard guard = guards.remove(activity);
    if (guard != null) guard.reveal();
    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }

  public static void require(Activity activity, long folder, Runnable allowed) {
    require(activity, folder, allowed, activity::finish);
  }

  public static void require(Activity activity, long folder, Runnable allowed, Runnable cancelled) {
    if (!locked(activity, folder)) {
      allowed.run();
      return;
    }
    Guard guard = guards.get(activity);
    if (guard == null) {
      guard = new Guard(activity);
      guards.put(activity, guard);
    }
    guard.folders.add(folder);
    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    Guard current = guard;
    if (isAccessible(activity, folder)) {
      current.reveal();
      allowed.run();
      return;
    }
    current.hide();
    authenticate(
        activity,
        () -> {
          unlocked.add(folder);
          current.reveal();
          allowed.run();
        },
        () -> {
          current.reveal();
          if (cancelled != null) cancelled.run();
        });
  }

  public static void requireDocument(
      Activity activity, String document, Runnable allowed, Runnable cancelled) {
    DocumentStore.Document item = MyFlatApp.of(activity).store.document(document);
    if (item == null) {
      if (cancelled != null) cancelled.run();
    } else require(activity, item.folder, allowed, cancelled);
  }

  public static void requirePage(
      Activity activity, String page, Runnable allowed, Runnable cancelled) {
    DocumentStore.Page item = MyFlatApp.of(activity).store.page(page);
    if (item == null) {
      if (cancelled != null) cancelled.run();
    } else requireDocument(activity, item.document, allowed, cancelled);
  }

  public static void requirePages(
      Activity activity, Collection<String> ids, Runnable allowed, Runnable cancelled) {
    Set<Long> folders = new LinkedHashSet<>();
    DocumentStore store = MyFlatApp.of(activity).store;
    for (String id : ids) {
      DocumentStore.Page page = store.page(id);
      if (page == null) continue;
      DocumentStore.Document document = store.document(page.document);
      if (document != null) folders.add(document.folder);
    }
    requireFolders(activity, new ArrayList<>(folders), allowed, cancelled);
  }

  private static void requireFolders(
      Activity activity, List<Long> folders, Runnable allowed, Runnable cancelled) {
    if (folders.isEmpty()) allowed.run();
    else {
      long folder = folders.remove(0);
      require(
          activity, folder, () -> requireFolders(activity, folders, allowed, cancelled), cancelled);
    }
  }

  public static void authenticate(Activity activity, Runnable allowed) {
    authenticate(activity, allowed, null);
  }

  public static void authenticate(Activity activity, Runnable allowed, Runnable cancelled) {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    if (!hasPin(activity)) {
      if (cancelled != null) cancelled.run();
      return;
    }
    PinPrompt active = prompts.get(activity);
    if (active != null) {
      active.success.add(allowed);
      if (cancelled != null) active.cancelled.add(cancelled);
      return;
    }
    PinPrompt prompt = new PinPrompt(activity, false, allowed, cancelled);
    prompts.put(activity, prompt);
    prompt.show();
  }

  public static void lockFolder(Activity activity, long folder, Runnable changed) {
    if (folder == 1) {
      toast(activity, "默认文件夹无法设置为安全文件夹。");
      return;
    }
    if (locked(activity, folder))
      authenticate(
          activity,
          () -> {
            MyFlatApp.of(activity).store.setFolderLocked(folder, false);
            unlocked.remove(folder);
            clearProtection(activity);
            toast(activity, "文件夹已解锁");
            changed.run();
          });
    else if (hasPin(activity)) {
      MyFlatApp.of(activity).store.setFolderLocked(folder, true);
      unlocked.remove(folder);
      clearProtection(activity);
      toast(activity, "文件夹已锁定");
      changed.run();
    } else {
      Dialog guide = new Dialog(activity);
      LinearLayout body = Ui.column(activity);
      body.setGravity(Gravity.CENTER_HORIZONTAL);
      body.setPadding(0, Ui.dp(activity, 28), 0, Ui.dp(activity, 32));
      GradientDrawable shape = Ui.shape(Color.WHITE, Ui.dp(activity, 48));
      shape.setCornerRadii(
          new float[] {
            Ui.dp(activity, 48),
            Ui.dp(activity, 48),
            Ui.dp(activity, 48),
            Ui.dp(activity, 48),
            0,
            0,
            0,
            0
          });
      body.setBackground(shape);
      ImageView image = new ImageView(activity);
      image.setImageResource(R.drawable.myflat_security_lock);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      image.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 12));
      body.addView(
          image, new LinearLayout.LayoutParams(Ui.dp(activity, 180), Ui.dp(activity, 180)));
      TextView message = Ui.bold(activity, "创建PIN以锁定文件夹", 13);
      message.setGravity(Gravity.CENTER);
      body.addView(message, new LinearLayout.LayoutParams(-1, -2));
      TextView move =
          button(
              activity,
              "转到PIN设置",
              true,
              () -> {
                guide.dismiss();
                create(activity, () -> lockFolder(activity, folder, changed));
              });
      LinearLayout.LayoutParams moveParams = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 44));
      moveParams.setMargins(Ui.dp(activity, 20), Ui.dp(activity, 40), Ui.dp(activity, 20), 0);
      body.addView(move, moveParams);
      guide.setContentView(body);
      guide.show();
      Window window = guide.getWindow();
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setGravity(Gravity.BOTTOM);
      window.setLayout(-1, -2);
    }
  }

  public static void settings(Activity activity) {
    Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout root = Ui.column(activity);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout header = Ui.row(activity);
    header.addView(Ui.iconButton(activity, "back", "返回", dialog::dismiss));
    TextView title = Ui.text(activity, "文件夹安全", 20, Ui.INK);
    LinearLayout.LayoutParams titleParams =
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 56), 1);
    titleParams.leftMargin = Ui.dp(activity, 16);
    title.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(title, titleParams);
    root.addView(header);
    root.addView(Ui.line(activity));
    ScrollView scroll = new ScrollView(activity);
    LinearLayout list = Ui.column(activity);
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Runnable[] refresh = new Runnable[1];
    refresh[0] =
        () -> {
          list.removeAllViews();
          if (!hasPin(activity)) preference(list, "创建PIN码", "", () -> create(activity, refresh[0]));
          else {
            preference(
                list,
                "更改PIN码",
                "",
                () -> authenticate(activity, () -> create(activity, refresh[0])));
            preference(
                list,
                "删除PIN码",
                "",
                () ->
                    authenticate(
                        activity,
                        () -> {
                          MyFlatApp.of(activity).store.clearFolderLocks();
                          preferences(activity).edit().clear().commit();
                          lockSession();
                          refresh[0].run();
                        }));
          }
          if (biometricAvailable(activity)) {
            LinearLayout row = Ui.row(activity);
            Ui.padding(row, 16, 12);
            LinearLayout labels = Ui.column(activity);
            labels.addView(Ui.text(activity, "使用生物识别", 16, hasPin(activity) ? Ui.INK : 0xffbdbdbd));
            TextView summary = Ui.text(activity, "使用注册到您设备的生物识别解锁文件夹。", 14, 0xff666666);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
            summaryParams.topMargin = Ui.dp(activity, 4);
            labels.addView(summary, summaryParams);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            Switch toggle = new Switch(activity);
            tint(toggle);
            toggle.setChecked(preferences(activity).getBoolean("biometric", false));
            toggle.setEnabled(hasPin(activity));
            toggle.setOnCheckedChangeListener(
                (b, value) -> {
                  if (value)
                    authenticate(
                        activity,
                        () -> preferences(activity).edit().putBoolean("biometric", true).apply(),
                        () -> {
                          toggle.setOnCheckedChangeListener(null);
                          toggle.setChecked(false);
                          refresh[0].run();
                        });
                  else preferences(activity).edit().putBoolean("biometric", false).apply();
                });
            row.addView(toggle);
            list.addView(row);
          }
          LinearLayout tips = Ui.column(activity);
          tips.setPadding(
              Ui.dp(activity, 20), Ui.dp(activity, 16), Ui.dp(activity, 20), Ui.dp(activity, 16));
          tips.setBackground(Ui.shape(0xfffffbef, Ui.dp(activity, 12)));
          tips.addView(Ui.bold(activity, "💡Tips!", 17));
          TextView one = Ui.text(activity, "您可以从文件夹视图顶部的菜单将文件夹设置为安全文件夹", 17, 0xff616161);
          LinearLayout.LayoutParams oneParams = new LinearLayout.LayoutParams(-1, -2);
          oneParams.topMargin = Ui.dp(activity, 8);
          tips.addView(one, oneParams);
          guideImage(tips, R.drawable.myflat_security_guide_1, 4);
          guideImage(tips, R.drawable.myflat_security_guide_2, 8);
          TextView two = Ui.text(activity, "设置为安全文件夹的文件夹受 4 位数密码保护。", 17, 0xff616161);
          LinearLayout.LayoutParams twoParams = new LinearLayout.LayoutParams(-1, -2);
          twoParams.topMargin = Ui.dp(activity, 12);
          tips.addView(two, twoParams);
          guideImage(tips, R.drawable.myflat_security_guide_3, 4);
          LinearLayout.LayoutParams tipsParams = new LinearLayout.LayoutParams(-1, -2);
          tipsParams.setMargins(
              Ui.dp(activity, 16), Ui.dp(activity, 20), Ui.dp(activity, 16), Ui.dp(activity, 24));
          list.addView(tips, tipsParams);
        };
    refresh[0].run();
    Ui.dialogInsets(dialog, root);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }

  public static void create(Activity activity, Runnable changed) {
    PinPrompt prompt = new PinPrompt(activity, true, changed, null);
    prompt.show();
  }

  private static void preference(LinearLayout list, String name, String detail, Runnable click) {
    Activity activity = (Activity) list.getContext();
    LinearLayout row = Ui.row(activity);
    Ui.padding(row, 16, 0);
    row.setMinimumHeight(Ui.dp(activity, 56));
    TextView label = Ui.text(activity, name, 16, Ui.INK);
    row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    TextView next = Ui.text(activity, "›", 24, 0xff9e9e9e);
    next.setGravity(Gravity.CENTER);
    row.addView(next, new LinearLayout.LayoutParams(Ui.dp(activity, 24), Ui.dp(activity, 24)));
    row.setOnClickListener(v -> click.run());
    row.setBackgroundResource(android.R.drawable.list_selector_background);
    list.addView(row);
  }

  private static void guideImage(LinearLayout parent, int resource, int top) {
    ImageView image = new ImageView(parent.getContext());
    image.setImageResource(resource);
    image.setAdjustViewBounds(true);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = Ui.dp(parent.getContext(), top);
    parent.addView(image, p);
  }

  private static TextView button(Activity activity, String text, boolean primary, Runnable click) {
    TextView button = Ui.text(activity, text, 14, primary ? Color.WHITE : Ui.GREEN);
    button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    button.setGravity(Gravity.CENTER);
    button.setBackground(Ui.shape(primary ? Ui.GREEN : 0xffe3f7f3, Ui.dp(activity, 8)));
    button.setOnClickListener(v -> click.run());
    return button;
  }

  private static void tint(Switch toggle) {
    toggle.setThumbTintList(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {Ui.GREEN, 0xffbdbdbd}));
    toggle.setTrackTintList(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {0xffa0dfd2, 0xffdddddd}));
  }

  @SuppressWarnings("deprecation")
  private static boolean biometricAvailable(Context context) {
    try {
      if (Build.VERSION.SDK_INT >= 29) {
        BiometricManager manager = context.getSystemService(BiometricManager.class);
        return manager != null && manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
      }
      if (Build.VERSION.SDK_INT >= 28) {
        FingerprintManager manager = context.getSystemService(FingerprintManager.class);
        return manager != null && manager.isHardwareDetected() && manager.hasEnrolledFingerprints();
      }
    } catch (SecurityException ignored) {
    }
    return false;
  }

  private static byte[] derive(char[] pin, byte[] salt) throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
      Arrays.fill(pin, '\0');
    }
  }

  private static String[] pinRecord(String value) throws GeneralSecurityException {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    byte[] hash = derive(value.toCharArray(), salt);
    String[] record = {
      android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP),
      android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
    };
    Arrays.fill(hash, (byte) 0);
    return record;
  }

  private static boolean savePin(Context context, String[] record) {
    return preferences(context)
        .edit()
        .putString("salt", record[0])
        .putString("hash", record[1])
        .putInt("failures", 0)
        .putLong("blocked_until", 0)
        .commit();
  }

  private static boolean verify(Context context, String value) throws GeneralSecurityException {
    SharedPreferences p = preferences(context);
    byte[] salt = android.util.Base64.decode(p.getString("salt", ""), android.util.Base64.NO_WRAP);
    byte[] expected =
        android.util.Base64.decode(p.getString("hash", ""), android.util.Base64.NO_WRAP);
    byte[] actual = derive(value.toCharArray(), salt);
    boolean match = MessageDigest.isEqual(expected, actual);
    Arrays.fill(actual, (byte) 0);
    int failures = match ? 0 : p.getInt("failures", 0) + 1;
    p.edit()
        .putInt("failures", failures)
        .putLong("blocked_until", failures >= 5 ? System.currentTimeMillis() + 30000 : 0)
        .commit();
    return match;
  }

  private static void toast(Context context, String message) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
  }

  private static final class Guard {
    final Activity activity;
    final Set<Long> folders = new LinkedHashSet<>();
    View mask;

    Guard(Activity activity) {
      this.activity = activity;
    }

    void hide() {
      if (activity.isFinishing() || activity.isDestroyed()) return;
      ViewGroup content = activity.findViewById(android.R.id.content);
      if (content == null) return;
      if (mask == null) {
        FrameLayout cover = new FrameLayout(activity);
        cover.setBackgroundColor(Color.WHITE);
        cover.setClickable(true);
        cover.setFocusable(true);
        TextView label = Ui.text(activity, "输入PIN码", 17, Ui.INK);
        label.setGravity(Gravity.CENTER);
        cover.addView(label, new FrameLayout.LayoutParams(-1, -1));
        mask = cover;
      }
      if (mask.getParent() != content) {
        if (mask.getParent() instanceof ViewGroup) ((ViewGroup) mask.getParent()).removeView(mask);
        content.addView(mask, new ViewGroup.LayoutParams(-1, -1));
      }
      mask.bringToFront();
    }

    void reveal() {
      if (mask != null && mask.getParent() instanceof ViewGroup)
        ((ViewGroup) mask.getParent()).removeView(mask);
    }
  }

  private static final class PinPrompt {
    final Activity activity;
    final boolean creating;
    final Dialog dialog;
    final List<Runnable> success = new ArrayList<>(), cancelled = new ArrayList<>();
    final LinearLayout root, body;
    TextView heading, description;
    Dots dots;
    String input = "", first = "";
    boolean checking, completed, confirmed;
    CancellationSignal biometric;

    PinPrompt(Activity activity, boolean creating, Runnable success, Runnable cancelled) {
      this.activity = activity;
      this.creating = creating;
      if (success != null) this.success.add(success);
      if (cancelled != null) this.cancelled.add(cancelled);
      dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
      root = Ui.column(activity);
      root.setBackgroundColor(0xfffafafa);
      if (creating) {
        LinearLayout header = Ui.row(activity);
        header.addView(Ui.iconButton(activity, "back", "返回", dialog::dismiss));
        TextView title = Ui.text(activity, "创建PIN码", 20, Ui.INK);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(activity, 56), 1);
        p.leftMargin = Ui.dp(activity, 16);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, p);
        root.addView(header);
      }
      body = Ui.column(activity);
      body.setGravity(Gravity.CENTER);
      Ui.padding(body, 24, 12);
      heading = Ui.bold(activity, "输入PIN码", 24);
      heading.setGravity(Gravity.CENTER);
      body.addView(heading);
      dots = new Dots(activity);
      LinearLayout.LayoutParams dotParams =
          new LinearLayout.LayoutParams(Ui.dp(activity, 136), Ui.dp(activity, 32));
      dotParams.topMargin = Ui.dp(activity, 12);
      body.addView(dots, dotParams);
      description = Ui.text(activity, creating ? "如果您忘记了PIN码，您将无法访问已锁定的文件夹。" : "", 13, 0xff666666);
      description.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
      detailParams.topMargin = Ui.dp(activity, 16);
      body.addView(description, detailParams);
      root.addView(
          body,
          new LinearLayout.LayoutParams(-1, creating ? 0 : Ui.dp(activity, 288), creating ? 1 : 0));
      if (!creating && biometricAvailable(activity)) {
        LinearLayout bioRow = Ui.row(activity);
        bioRow.setGravity(Gravity.CENTER);
        Ui.padding(bioRow, 16, 12);
        Switch toggle = new Switch(activity);
        tint(toggle);
        toggle.setChecked(preferences(activity).getBoolean("biometric", false));
        toggle.setOnCheckedChangeListener(
            (v, enabled) -> {
              if (enabled) showBiometric(true);
              else {
                if (biometric != null) biometric.cancel();
                preferences(activity).edit().putBoolean("biometric", false).apply();
              }
            });
        bioRow.addView(toggle);
        TextView label = Ui.text(activity, "使用生物识别", 17, Ui.INK);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = Ui.dp(activity, 8);
        bioRow.addView(label, p);
        root.addView(bioRow);
      }
      LinearLayout keys = Ui.column(activity);
      keys.setBackground(Ui.shape(Color.WHITE, Ui.dp(activity, 8)));
      String[] values = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "↻", "0", "⌫"};
      for (int y = 0; y < 4; y++) {
        LinearLayout row = Ui.row(activity);
        for (int x = 0; x < 3; x++) {
          String key = values[y * 3 + x];
          TextView button = Ui.text(activity, key, 28, 0xff242424);
          button.setGravity(Gravity.CENTER);
          button.setContentDescription(key.equals("↻") ? "清除PIN码" : key.equals("⌫") ? "退格" : key);
          button.setBackgroundResource(android.R.drawable.list_selector_background);
          button.setOnClickListener(v -> type(key));
          row.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(activity, 65), 1));
        }
        keys.addView(row);
      }
      root.addView(keys);
      dialog.setOnDismissListener(
          d -> {
            if (biometric != null) biometric.cancel();
            if (prompts.get(activity) == this) prompts.remove(activity);
            if (!completed) for (Runnable task : new ArrayList<>(this.cancelled)) task.run();
          });
    }

    void show() {
      if (creating) Ui.dialogInsets(dialog, root);
      else dialog.setContentView(root);
      dialog.show();
      Window window = dialog.getWindow();
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      if (creating) window.setLayout(-1, -1);
      else {
        GradientDrawable background = Ui.shape(0xfffafafa, Ui.dp(activity, 20));
        background.setCornerRadii(
            new float[] {
              Ui.dp(activity, 20),
              Ui.dp(activity, 20),
              Ui.dp(activity, 20),
              Ui.dp(activity, 20),
              0,
              0,
              0,
              0
            });
        root.setBackground(background);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.BOTTOM);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.6f);
        window.setLayout(-1, -2);
      }
      if (!creating && preferences(activity).getBoolean("biometric", false))
        main.postDelayed(() -> showBiometric(false), 200);
    }

    void type(String key) {
      if (checking || confirmed) return;
      if (key.equals("↻")) input = "";
      else if (key.equals("⌫"))
        input = input.isEmpty() ? "" : input.substring(0, input.length() - 1);
      else if (input.length() < 4) input += key;
      dots.count = input.length();
      dots.invalidate();
      if (input.length() != 4) return;
      if (creating && first.isEmpty()) {
        first = input;
        input = "";
        heading.setText("确认PIN码");
        dots.count = 0;
        dots.invalidate();
        return;
      }
      if (creating && !first.equals(input)) {
        error("两次输入的PIN码不一致，请再试一次。");
        return;
      }
      if (!creating
          && preferences(activity).getLong("blocked_until", 0) > System.currentTimeMillis()) {
        error("尝试次数过多，请稍后重试。");
        return;
      }
      checking = true;
      String value = input;
      crypto.execute(
          () -> {
            boolean match = false;
            String[] record = null;
            try {
              if (creating) {
                record = pinRecord(value);
                match = true;
              } else match = verify(activity, value);
            } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            }
            boolean accepted = match;
            String[] generated = record;
            main.post(
                () -> {
                  checking = false;
                  if (!dialog.isShowing() || activity.isDestroyed()) return;
                  if (accepted) {
                    if (creating) {
                      if (savePin(activity, generated)) done();
                      else error("无法保存PIN码，请重试。");
                    } else finish();
                  } else error(creating ? "无法保存PIN码，请重试。" : "PIN码不正确，请再试一次。");
                });
          });
    }

    void error(String message) {
      input = "";
      dots.count = 0;
      dots.invalidate();
      description.setText(message);
      dots.animate()
          .translationX(Ui.dp(activity, 8))
          .setDuration(60)
          .withEndAction(
              () ->
                  dots.animate()
                      .translationX(-Ui.dp(activity, 8))
                      .setDuration(80)
                      .withEndAction(() -> dots.animate().translationX(0).setDuration(60).start())
                      .start())
          .start();
    }

    void done() {
      confirmed = true;
      lockSession();
      root.removeAllViews();
      LinearLayout content = Ui.column(activity);
      content.setGravity(Gravity.CENTER);
      Ui.padding(content, 24, 24);
      ImageView image = new ImageView(activity);
      image.setImageResource(R.drawable.myflat_security_lock);
      content.addView(
          image, new LinearLayout.LayoutParams(Ui.dp(activity, 180), Ui.dp(activity, 180)));
      TextView label = Ui.bold(activity, "PIN码已更新", 24);
      label.setGravity(Gravity.CENTER);
      content.addView(label);
      TextView detail = Ui.text(activity, "您可以随时更改或删除您的PIN码。", 17, 0xff666666);
      detail.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.topMargin = Ui.dp(activity, 12);
      content.addView(detail, p);
      root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
      TextView done = button(activity, "完成", true, this::finish);
      LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 44));
      doneParams.setMargins(Ui.dp(activity, 20), 0, Ui.dp(activity, 20), Ui.dp(activity, 24));
      root.addView(done, doneParams);
    }

    void finish() {
      completed = true;
      dialog.dismiss();
      if (started > 0)
        for (Runnable task : new ArrayList<>(success)) if (!activity.isFinishing()) task.run();
    }

    void showBiometric(boolean enableAfterSuccess) {
      if (Build.VERSION.SDK_INT < 28 || !dialog.isShowing() || !biometricAvailable(activity))
        return;
      if (biometric != null) biometric.cancel();
      biometric = new CancellationSignal();
      BiometricPrompt prompt =
          new BiometricPrompt.Builder(activity)
              .setTitle("输入PIN码")
              .setDescription("使用生物识别验证身份后，才能继续")
              .setNegativeButton("使用PIN码", activity.getMainExecutor(), (d, which) -> {})
              .build();
      prompt.authenticate(
          biometric,
          activity.getMainExecutor(),
          new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
              if (!dialog.isShowing()) return;
              if (enableAfterSuccess)
                preferences(activity).edit().putBoolean("biometric", true).apply();
              finish();
            }
          });
    }
  }

  private static final class Dots extends View {
    int count;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Dots(Context context) {
      super(context);
      setContentDescription("PIN码输入");
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float spacing = getWidth() / 4f;
      for (int i = 0; i < 4; i++) {
        paint.setColor(i < count ? Ui.GREEN : 0xffe6e6e6);
        canvas.drawCircle(spacing * (i + 0.5f), getHeight() / 2f, Ui.dp(getContext(), 8), paint);
      }
    }
  }
}
