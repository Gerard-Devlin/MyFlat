package dev.myflat.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** Application-wide appearance and local caption dictionaries. Image pixels are never recolored. */
final class AppAppearance {
  static final String[][] LANGUAGES = {
    {"system", "系统默认"},
    {"en", "English"},
    {"ar", "العربية"},
    {"cs", "Čeština"},
    {"de", "Deutsch"},
    {"el", "Ελληνικά"},
    {"es-ES", "Español"},
    {"es", "Español (Latinoamérica)"},
    {"fa", "فارسی"},
    {"fr", "Français"},
    {"fr-CA", "Français (Canada)"},
    {"hi", "हिन्दी"},
    {"id", "Indonesia"},
    {"it", "Italiano"},
    {"ja", "日本語"},
    {"ko", "한국어"},
    {"ms", "Melayu"},
    {"nb", "Norsk bokmål"},
    {"nl", "Nederlands"},
    {"pl", "Polski"},
    {"pt", "Português"},
    {"pt-BR", "Português (Brasil)"},
    {"ru", "Pусский"},
    {"sk", "Slovenščina"},
    {"sv", "Svenska"},
    {"th", "ไทย"},
    {"tr", "Türkçe"},
    {"vi", "Tiếng Việt"},
    {"zh-CN", "中文 (中国)"},
    {"zh-TW", "中文 (台灣)"}
  };
  private static final Map<Activity, String> activities = new WeakHashMap<>();
  private static final Map<View, Boolean> watchers = new WeakHashMap<>();
  private static final Map<View, Boolean> verbatim = new WeakHashMap<>();
  private static final Map<TextView, TextState> captions = new WeakHashMap<>();
  private static final Map<String, Map<String, String>> dictionaries = new HashMap<>();
  private static final Map<String, ArrayList<String>> dictionaryKeys = new HashMap<>();
  private static final Handler main = new Handler(Looper.getMainLooper());

  private AppAppearance() {}

  private static SharedPreferences preferences(Context c) {
    return c.getApplicationContext().getSharedPreferences("appearance", Context.MODE_PRIVATE);
  }

  static int nightMode(Context c) {
    int value = preferences(c).getInt("night_mode", 0);
    return value < 0 || value > 2 ? 0 : value;
  }

  static String language(Context c) {
    return preferences(c).getString("language", "system");
  }

  static String nightLabel(Context c) {
    return text(c, new String[] {"系统默认", "关闭", "开启"}[nightMode(c)]);
  }

  static String languageLabel(Context c) {
    String tag = language(c);
    for (String[] item : LANGUAGES)
      if (item[0].equals(tag)) return item[0].equals("system") ? text(c, item[1]) : item[1];
    return text(c, "系统默认");
  }

  static void setNightMode(Activity activity, int mode) {
    if (mode < 0 || mode > 2) throw new IllegalArgumentException("Invalid night mode");
    if (nightMode(activity) == mode) return;
    preferences(activity).edit().putInt("night_mode", mode).apply();
    refreshActivities(activity);
  }

  static void setLanguage(Activity activity, String tag) {
    boolean supported = false;
    for (String[] item : LANGUAGES) if (item[0].equals(tag)) supported = true;
    if (!supported) throw new IllegalArgumentException("Unsupported language");
    if (language(activity).equals(tag)) return;
    preferences(activity).edit().putString("language", tag).apply();
    refreshActivities(activity);
  }

  private static void refreshActivities(Activity activity) {
    configure(activity.getApplicationContext());
    ArrayList<Activity> open = new ArrayList<>(activities.keySet());
    if (!open.contains(activity)) open.add(activity);
    for (Activity current : open)
      if (current != null && !current.isFinishing() && !current.isDestroyed())
        main.post(
            () -> {
              if (!current.isFinishing() && !current.isDestroyed()) current.recreate();
            });
  }

  static boolean isDark(Context c) {
    int mode = nightMode(c);
    return mode == 2
        || (mode == 0
            && (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
  }

  private static Locale selectedLocale(Context c) {
    String tag = language(c);
    return tag.equals("system")
        ? Resources.getSystem().getConfiguration().getLocales().get(0)
        : Locale.forLanguageTag(tag);
  }

  @SuppressWarnings("deprecation")
  private static void configure(Context c) {
    Resources resources = c.getResources();
    Configuration updated = new Configuration(resources.getConfiguration());
    Locale locale = selectedLocale(c);
    updated.setLocales(new LocaleList(locale));
    updated.setLayoutDirection(locale);
    updated.uiMode =
        (updated.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (isDark(c) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
    resources.updateConfiguration(updated, resources.getDisplayMetrics());
    Locale.setDefault(locale);
  }

  static void prepare(Activity activity) {
    configure(activity);
    activity.setTheme(isDark(activity) ? R.style.AppThemeDark : R.style.AppTheme);
    activities.put(activity, signature(activity));
  }

  static void resumed(Activity activity) {
    String next = signature(activity), previous = activities.get(activity);
    if (previous != null && !previous.equals(next)) {
      activity.recreate();
      return;
    }
    activities.put(activity, next);
    watch(activity.getWindow().getDecorView());
    if (!(activity instanceof CameraActivity)) window(activity.getWindow(), activity);
  }

  private static String signature(Context c) {
    return language(c) + ':' + selectedLocale(c).toLanguageTag() + ':' + isDark(c);
  }

  static void forgotten(Activity activity) {
    activities.remove(activity);
  }

  static void dialog(Dialog dialog, View root) {
    watch(root);
    if (fullScreen(dialog)) window(dialog.getWindow(), root.getContext());
    root.post(
        () -> {
          apply(root);
          if (fullScreen(dialog)) window(dialog.getWindow(), root.getContext());
        });
  }

  private static boolean fullScreen(Dialog dialog) {
    if (dialog.getWindow() == null) return false;
    android.view.WindowManager.LayoutParams p = dialog.getWindow().getAttributes();
    return p.width == ViewGroup.LayoutParams.MATCH_PARENT
        && p.height == ViewGroup.LayoutParams.MATCH_PARENT;
  }

  @SuppressWarnings("deprecation")
  private static void window(Window window, Context c) {
    if (window == null) return;
    int base = isDark(c) ? 0xff121212 : Color.WHITE;
    window.setStatusBarColor(base);
    window.setNavigationBarColor(base);
    int flags = window.getDecorView().getSystemUiVisibility();
    int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    window.getDecorView().setSystemUiVisibility(isDark(c) ? flags & ~light : flags | light);
  }

  /** Use for user-entered folder/document titles and other data that must remain verbatim. */
  static void keepText(TextView view) {
    verbatim.put(view, Boolean.TRUE);
    captions.remove(view);
    if (view instanceof Ui.LocalizedText) ((Ui.LocalizedText) view).restoreSource();
  }

  static boolean keepsText(View view) {
    return verbatim.containsKey(view);
  }

  static void watch(View root) {
    if (watchers.containsKey(root)) return;
    {
      watchers.put(root, Boolean.TRUE);
      class AppearanceObserver
          implements View.OnAttachStateChangeListener,
              android.view.ViewTreeObserver.OnGlobalLayoutListener {
        final java.lang.ref.WeakReference<View> target;

        AppearanceObserver(View view) {
          target = new java.lang.ref.WeakReference<>(view);
        }

        android.view.ViewTreeObserver observer;

        public void onViewAttachedToWindow(View view) {
          detach();
          observer = view.getViewTreeObserver();
          observer.addOnGlobalLayoutListener(this);
        }

        public void onViewDetachedFromWindow(View view) {
          detach();
        }

        private void detach() {
          if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
          observer = null;
        }

        public void onGlobalLayout() {
          View view = target.get();
          if (view == null) detach();
          else apply(view);
        }
      }
      AppearanceObserver listener = new AppearanceObserver(root);
      root.addOnAttachStateChangeListener(listener);
      if (root.isAttachedToWindow()) listener.onViewAttachedToWindow(root);
    }
    apply(root);
  }

  static void apply(View root) {
    Context c = root.getContext();
    boolean dark = isDark(c);
    root.setLayoutDirection(
        selectedLocale(c).getLanguage().matches("ar|fa")
            ? View.LAYOUT_DIRECTION_RTL
            : View.LAYOUT_DIRECTION_LTR);
    applyNode(root, dark);
  }

  private static void applyNode(View v, boolean dark) {
    if (!(v instanceof ImageView)) recolor(v.getBackground(), dark);
    if (v instanceof TextView) {
      TextView tv = (TextView) v;
      int before = tv.getCurrentTextColor(), after = color(tv.getContext(), before);
      if (before != after) tv.setTextColor(after);
      if (!(tv instanceof EditText) && !(tv instanceof Ui.LocalizedText) && !keepsText(tv)) {
        CharSequence value = tv.getText();
        TextState state = captions.get(tv);
        if (state == null || !value.toString().equals(state.rendered)) {
          state = new TextState(value);
          captions.put(tv, state);
        }
        CharSequence translated = localized(tv.getContext(), state.source);
        if (!value.toString().contentEquals(translated)) tv.setText(translated);
        state.rendered = translated.toString();
      }
      CharSequence hint = tv.getHint();
      if (hint != null) {
        String translated = text(tv.getContext(), hint.toString());
        if (!hint.toString().equals(translated)) tv.setHint(translated);
      }
    }
    CharSequence description = v.getContentDescription();
    if (description != null && !keepsText(v)) {
      String translated = text(v.getContext(), description.toString());
      if (!description.toString().equals(translated)) v.setContentDescription(translated);
    }
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) applyNode(group.getChildAt(i), dark);
    }
  }

  static int color(Context c, int value) {
    if (!isDark(c)) return value;
    int alpha = Color.alpha(value), rgb = value & 0xffffff, mapped = rgb;
    if (rgb == 0x000000
        || rgb == 0x121212
        || rgb == 0x171b26
        || rgb == 0x3a3a3c
        || rgb == 0x212121
        || rgb == 0x303030
        || rgb == 0x424242) mapped = 0xffffff;
    else if (rgb == 0x5c6c7d
        || rgb == 0x616161
        || rgb == 0x777777
        || rgb == 0x6a7886
        || rgb == 0x666666
        || rgb == 0x757575
        || rgb == 0x808080) mapped = 0xbdbdbd;
    else if (rgb == 0x8d8d93) mapped = 0x9e9e9e;
    else if (rgb == 0x30bfa5) mapped = 0x00b092;
    return (alpha << 24) | mapped;
  }

  private static int background(int value) {
    int rgb = value & 0xffffff, mapped = rgb;
    if (rgb == 0xffffff) mapped = 0x121212;
    else if (rgb == 0xfafafa) mapped = 0x212121;
    else if (rgb == 0xf5f5f5 || rgb == 0xf2f2f2 || rgb == 0xf1f3f8 || rgb == 0xf4f4f4)
      mapped = 0x303030;
    else if (rgb == 0xeeeeee || rgb == 0xe5e5e5) mapped = 0x424242;
    else if (rgb == 0x30bfa5) mapped = 0x00b092;
    else if (rgb == 0xd6f5ee || rgb == 0xd5f4ed || rgb == 0xd4f2ec) mapped = 0x1c3b35;
    return (value & 0xff000000) | mapped;
  }

  private static void recolor(Drawable drawable, boolean dark) {
    if (!dark || drawable == null) return;
    if (drawable instanceof ColorDrawable) {
      ColorDrawable color = (ColorDrawable) drawable;
      int value = color.getColor(), mapped = background(value);
      if (value != mapped) color.setColor(mapped);
    } else if (drawable instanceof GradientDrawable) {
      GradientDrawable shape = (GradientDrawable) drawable;
      if (shape.getColor() != null) {
        int value = shape.getColor().getDefaultColor(), mapped = background(value);
        if (value != mapped) shape.setColor(mapped);
      }
    }
  }

  static CharSequence localized(Context c, CharSequence value) {
    if (value == null) return "";
    String translated = text(c, value.toString());
    if (translated.equals(value.toString())) return value;
    if (translated.contains("<font") || translated.contains("<br"))
      return android.text.Html.fromHtml(translated, android.text.Html.FROM_HTML_MODE_LEGACY);
    return translated;
  }

  static String text(Context c, String value) {
    if (value == null || value.isEmpty()) return value;
    boolean chinese = false;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch >= 0x3400 && ch <= 0x9fff) {
        chinese = true;
        break;
      }
    }
    if (!chinese) return value;
    String tag = selectedLocale(c).toLanguageTag();
    if (tag.startsWith("zh") && !tag.contains("TW") && !tag.contains("HK")) return value;
    if (tag.startsWith("zh")) tag = "zh-TW";
    else if (!hasLanguage(tag)) {
      tag = selectedLocale(c).getLanguage();
      if (tag.equals("in")) tag = "id";
      if (!hasLanguage(tag)) tag = "en";
    }
    Map<String, String> dictionary = dictionary(c, tag);
    String exact = dictionary.get(value);
    if (exact != null) return exact;
    String trimmed = value.trim();
    exact = dictionary.get(trimmed);
    if (exact != null)
      return value.substring(0, value.indexOf(trimmed))
          + exact
          + value.substring(value.indexOf(trimmed) + trimmed.length());
    // Only UI captions reach this method. Retain punctuation and dynamic counts around captions.
    StringBuilder result = new StringBuilder();
    ArrayList<String> keys = dictionaryKeys.get(tag);
    for (int offset = 0; offset < value.length(); ) {
      String found = null;
      for (String key : keys)
        if (key.length() >= 2 && value.startsWith(key, offset)) {
          found = key;
          break;
        }
      if (found == null) result.append(value.charAt(offset++));
      else {
        result.append(dictionary.get(found));
        offset += found.length();
      }
    }
    return result.toString();
  }

  private static boolean hasLanguage(String tag) {
    for (String[] item : LANGUAGES) if (item[0].equals(tag)) return true;
    return false;
  }

  private static synchronized Map<String, String> dictionary(Context c, String tag) {
    Map<String, String> cached = dictionaries.get(tag);
    if (cached != null) return cached;
    Map<String, String> loaded = new HashMap<>();
    try (InputStream input = c.getAssets().open("locales/" + tag + ".json")) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
      JSONObject data = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      java.util.Iterator<String> keys = data.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        loaded.put(key, data.getString(key));
      }
    } catch (Exception ignored) {
    }
    ArrayList<String> keys = new ArrayList<>(loaded.keySet());
    keys.sort(Comparator.comparingInt(String::length).reversed());
    dictionaries.put(tag, loaded);
    dictionaryKeys.put(tag, keys);
    return loaded;
  }

  private static final class TextState {
    final CharSequence source;
    String rendered;

    TextState(CharSequence source) {
      this.source = source;
      this.rendered = source.toString();
    }
  }
}
