package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.net.Uri;

final class Support {
  static void translation(Activity a) {
    open(a, "https://github.com/Gerard-Devlin/MyFlat/issues/new?labels=translation");
  }

  static void business(Activity a) {
    open(a, "https://github.com/Gerard-Devlin/MyFlat/issues/new?labels=contact");
  }

  private static void open(Activity a, String url) {
    try {
      a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException ex) {
      Ui.error(a, ex);
    }
  }

  static void show(Activity a) {
    Ui.alert(a)
        .setTitle("客户支持")
        .setItems(
            new String[] {"扫描指南", "反馈问题", "关于 MyFlat"},
            (d, w) -> {
              if (w == 0) new ScanGuideDialog(a, 0, () -> {}).show();
              else if (w == 1)
                a.startActivity(
                    new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Gerard-Devlin/MyFlat/issues")));
              else
                Ui.alert(a)
                    .setTitle("MyFlat")
                    .setMessage("免费开源的本地扫描工具")
                    .setPositiveButton("关闭", null)
                    .show();
            })
        .show();
  }
}
