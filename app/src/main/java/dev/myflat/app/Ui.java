package dev.myflat.app;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;

/** Shared native components and bounded, asynchronous thumbnails. */
final class Ui {
  static final int INK = 0xff000000,
      MUTED = 0xff5c6c7d,
      GREEN = 0xff30bfa5,
      PAPER = 0xfff5f5f5,
      LINE = 0xffeeeeee;
  private static final ExecutorService thumbnails = Executors.newFixedThreadPool(2);
  private static final LruCache<String, Bitmap> cache =
      new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };

  static int dp(Context c, float value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  static LinearLayout column(Context c) {
    LinearLayout v = new AppearanceLayout(c);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  static LinearLayout row(Context c) {
    LinearLayout v = new AppearanceLayout(c);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  static TextView text(Context c, String value, int size, int color) {
    TextView v = new LocalizedText(c);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setFontFeatureSettings("kern");
    return v;
  }

  static TextView bold(Context c, String value, int size) {
    TextView v = text(c, value, size, INK);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  static GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }

  static void padding(View v, int horizontal, int vertical) {
    int x = dp(v.getContext(), horizontal), y = dp(v.getContext(), vertical);
    v.setPadding(x, y, x, y);
  }

  static LinearLayout root(Activity activity) {
    LinearLayout root = column(activity);
    root.setBackgroundColor(Color.WHITE);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    activity.setContentView(root);
    root.requestApplyInsets();
    return root;
  }

  static ImageView logo(Context c) {
    ImageView image = new ImageView(c);
    image.setImageResource(R.drawable.myflat_mark);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    return image;
  }

  static View line(Context c) {
    View v = new View(c);
    v.setBackgroundColor(LINE);
    v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 1)));
    return v;
  }

  static View spacer(Context c, int height) {
    View v = new View(c);
    v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, height)));
    return v;
  }

  static LinearLayout action(Context c, String icon, String label, Runnable click) {
    LinearLayout box = column(c);
    box.setGravity(Gravity.CENTER);
    box.setMinimumHeight(dp(c, 64));
    Icon image = new Icon(c, icon);
    box.addView(image, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
    TextView text = text(c, label, 12, INK);
    text.setGravity(Gravity.CENTER);
    box.addView(spacer(c, 4));
    box.addView(text);
    box.setContentDescription(AppAppearance.text(c, label));
    box.setBackgroundResource(android.R.drawable.list_selector_background);
    box.setOnClickListener(v -> click.run());
    return box;
  }

  static void dialogInsets(Dialog dialog, View root) {
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else
            view.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    dialog.setContentView(root);
    AppAppearance.dialog(dialog, root);
    root.requestApplyInsets();
  }

  static void thumbnail(ImageView image, android.net.Uri uri) {
    String key = uri.toString();
    image.setTag(key);
    Bitmap cached = cache.get(key);
    if (cached != null) {
      image.setImageBitmap(cached);
      return;
    }
    thumbnails.submit(
        () -> {
          try {
            Bitmap bitmap;
            if ("application/pdf".equals(image.getContext().getContentResolver().getType(uri))) {
              try (android.os.ParcelFileDescriptor fd =
                      image.getContext().getContentResolver().openFileDescriptor(uri, "r");
                  android.graphics.pdf.PdfRenderer pdf = new android.graphics.pdf.PdfRenderer(fd);
                  android.graphics.pdf.PdfRenderer.Page page = pdf.openPage(0)) {
                float scale = 500f / Math.max(page.getWidth(), page.getHeight());
                bitmap =
                    Bitmap.createBitmap(
                        Math.max(1, Math.round(page.getWidth() * scale)),
                        Math.max(1, Math.round(page.getHeight() * scale)),
                        Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(
                    bitmap,
                    null,
                    null,
                    android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
              }
            } else bitmap = Images.decode(image.getContext(), uri, 500);
            cache.put(key, bitmap);
            image.post(
                () -> {
                  if (key.equals(image.getTag())) image.setImageBitmap(bitmap);
                });
          } catch (Exception ignored) {
          }
        });
  }

  static View iconButton(Context c, String icon, String description, Runnable click) {
    FrameLayout frame = new FrameLayout(c);
    Icon view = new Icon(c, icon);
    frame.addView(view, new FrameLayout.LayoutParams(dp(c, 24), dp(c, 24), Gravity.CENTER));
    int hitSize = icon.equals("back") ? 56 : 48;
    frame.setLayoutParams(new LinearLayout.LayoutParams(dp(c, hitSize), dp(c, hitSize)));
    frame.setContentDescription(AppAppearance.text(c, description));
    frame.setBackgroundResource(android.R.drawable.list_selector_background);
    frame.setOnClickListener(v -> click.run());
    return frame;
  }

  static TextView pill(Context c, String label, boolean selected, Runnable click) {
    TextView v = text(c, label, 13, selected ? Color.WHITE : INK);
    v.setGravity(Gravity.CENTER);
    padding(v, 16, 9);
    v.setBackground(shape(selected ? GREEN : PAPER, dp(c, 24)));
    v.setOnClickListener(w -> click.run());
    return v;
  }

  static TextView primary(Context c, String label, Runnable click) {
    TextView v = pill(c, label, true, click);
    v.setTextSize(15);
    v.setMinimumHeight(dp(c, 48));
    return v;
  }

  static CheckBox check(Context c, String label, boolean checked) {
    CheckBox b = new CheckBox(c);
    b.setText(AppAppearance.text(c, label));
    b.setTextSize(15);
    b.setTextColor(INK);
    b.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
    b.setChecked(checked);
    b.setMinimumHeight(dp(c, 44));
    return b;
  }

  static void thumbnail(ImageView image, File file) {
    String key = file.getPath() + ":" + file.lastModified();
    image.setTag(key);
    image.setImageDrawable(null);
    Bitmap bitmap = cache.get(key);
    if (bitmap != null) {
      image.setImageBitmap(bitmap);
      return;
    }
    thumbnails.submit(
        () -> {
          try {
            Bitmap b = Images.decode(file, 500);
            cache.put(key, b);
            image.post(
                () -> {
                  if (key.equals(image.getTag())) image.setImageBitmap(b);
                });
          } catch (Exception ignored) {
          }
        });
  }

  static PopupMenu popup(Context context, View anchor) {
    return new PopupMenu(context, anchor) {
      @Override
      public void show() {
        Menu menu = getMenu();
        for (int i = 0; i < menu.size(); i++) {
          MenuItem item = menu.getItem(i);
          item.setTitle(AppAppearance.localized(context, item.getTitle()));
        }
        super.show();
      }
    };
  }

  static AlertDialog.Builder alert(Context c) {
    return new AlertDialog.Builder(c) {
      @Override
      public AlertDialog create() {
        AlertDialog dialog = super.create();
        dialog.setOnShowListener(
            d -> AppAppearance.dialog(dialog, dialog.getWindow().getDecorView()));
        return dialog;
      }

      @Override
      public AlertDialog show() {
        AlertDialog dialog = super.show();
        AppAppearance.dialog(dialog, dialog.getWindow().getDecorView());
        return dialog;
      }
    };
  }

  static void error(Context c, Throwable ex) {
    alert(c)
        .setTitle(AppAppearance.text(c, "未能完成"))
        .setMessage(ex.getMessage() == null ? ex.toString() : ex.getMessage())
        .setPositiveButton(AppAppearance.text(c, "知道了"), null)
        .show();
  }

  static EditText input(Context c, String value, String hint) {
    EditText text = new EditText(c);
    text.setSingleLine(true);
    text.setText(value);
    text.setHint(AppAppearance.text(c, hint));
    text.setTextSize(16);
    text.setSelectAllOnFocus(true);
    return text;
  }

  interface Named {
    void accept(String value);
  }

  static void rename(Context c, String title, String current, Named save) {
    EditText input = input(c, current, "名称");
    LinearLayout body = column(c);
    padding(body, 24, 8);
    body.addView(input);
    alert(c)
        .setTitle(AppAppearance.text(c, title))
        .setView(body)
        .setNegativeButton(AppAppearance.text(c, "取消"), null)
        .setPositiveButton(
            AppAppearance.text(c, "保存"), (d, w) -> save.accept(input.getText().toString()))
        .show();
  }

  // This app uses the framework Material theme and framework widgets throughout.
  @android.annotation.SuppressLint("AppCompatCustomView")
  static final class LocalizedText extends TextView {
    private CharSequence source;
    private boolean restoring;

    LocalizedText(Context c) {
      super(c);
    }

    @Override
    public void setText(CharSequence value, BufferType type) {
      if (!restoring) source = value;
      super.setText(
          AppAppearance.keepsText(this) ? value : AppAppearance.localized(getContext(), value),
          type);
    }

    void restoreSource() {
      restoring = true;
      super.setText(source, BufferType.NORMAL);
      restoring = false;
    }
  }

  private static final class AppearanceLayout extends LinearLayout {
    AppearanceLayout(Context c) {
      super(c);
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      AppAppearance.watch(this);
    }
  }

  static final class Icon extends View {
    private final android.graphics.drawable.Drawable drawable;
    int color = INK;

    Icon(Context c, String name) {
      super(c);
      int id;
      switch (name) {
        case "move":
          id = R.drawable.myflat_ic_move;
          break;
        case "gallery":
          id = R.drawable.myflat_ic_gallery;
          break;
        case "rescan":
          id = R.drawable.myflat_ic_rescan;
          break;
        case "handwriting":
          id = R.drawable.myflat_ic_handwriting;
          break;
        case "edit":
          id = R.drawable.myflat_ic_edit;
          break;
        case "rename":
          id = R.drawable.myflat_ic_rename;
          break;
        case "sort":
          id = R.drawable.myflat_ic_sort;
          break;
        case "back":
          id = R.drawable.myflat_ic_back;
          break;
        case "camera":
          id = R.drawable.myflat_ic_camera;
          break;
        case "folder":
          id = R.drawable.myflat_ic_folder;
          break;
        case "folder_add":
          id = R.drawable.myflat_ic_folder_add;
          break;
        case "import":
          id = R.drawable.myflat_ic_import;
          break;
        case "pdf":
          id = R.drawable.myflat_ic_pdf;
          break;
        case "zip":
          id = R.drawable.myflat_ic_zip;
          break;
        case "trash":
          id = R.drawable.myflat_ic_trash;
          break;
        case "more":
          id = R.drawable.myflat_ic_more;
          break;
        case "menu":
          id = R.drawable.myflat_ic_menu;
          break;
        case "search":
          id = R.drawable.myflat_ic_search;
          break;
        case "crop":
          id = R.drawable.myflat_ic_crop;
          break;
        case "rotate":
          id = R.drawable.myflat_ic_rotate;
          break;
        case "rotate_left":
          id = R.drawable.myflat_ic_rotate_left;
          break;
        case "rotate_right":
          id = R.drawable.myflat_ic_rotate_right;
          break;
        case "share":
          id = R.drawable.myflat_ic_share;
          break;
        case "check":
          id = R.drawable.myflat_ic_check;
          break;
        case "plus":
          id = R.drawable.myflat_ic_plus;
          break;
        case "compare":
          id = R.drawable.myflat_ic_compare;
          break;
        case "erase":
          id = R.drawable.myflat_ic_erase;
          break;
        case "effects":
          id = R.drawable.myflat_ic_effects;
          break;
        case "chevron_up":
          id = R.drawable.myflat_ic_chevron_up;
          break;
        case "chevron_down":
          id = R.drawable.myflat_ic_chevron_down;
          break;
        case "page_count":
          id = R.drawable.myflat_ic_page_count;
          break;
        case "empty_folder":
          id = R.drawable.myflat_ic_empty_folder;
          break;
        default:
          id = R.drawable.myflat_ic_more;
          break;
      }
      drawable = c.getDrawable(id).mutate();
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      drawable.setTint(AppAppearance.color(getContext(), color));
      drawable.setBounds(0, 0, getWidth(), getHeight());
      drawable.draw(canvas);
    }
  }
}
