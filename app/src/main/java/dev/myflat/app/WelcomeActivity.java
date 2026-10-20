package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

/** First-entry terms review followed by the scan guide. */
public final class WelcomeActivity extends Activity {
  static final String TERMS =
      "MyFlat is a free local document scanner. Project-owned source code is provided under the MIT"
          + " License, without warranty. Third-party components and assets retain their own"
          + " licenses. You are responsible for the documents you scan and share.";
  static final String PRIVACY =
      "Scanning and image processing take place on your device. MyFlat does not upload scans or"
          + " collect an account profile. Camera access is used for scanning. Gallery copies,"
          + " backups and file sharing are performed when you enable them or choose the"
          + " corresponding action. External applications and websites handle data under their own"
          + " privacy policies.";

  @Override
  public void onCreate(Bundle state) {
    AppAppearance.prepare(this);
    super.onCreate(state);
    if (SharedImports.accepts(getIntent()) && SharedImports.collect(this, getIntent()).isEmpty()) {
      Toast.makeText(this, "请选择可读取的图片或 PDF", Toast.LENGTH_LONG).show();
      finish();
      return;
    }
    if (getSharedPreferences("welcome", 0).getBoolean("accepted", false)) {
      begin();
      return;
    }
    LinearLayout root = Ui.root(this);
    LinearLayout body = Ui.column(this);
    body.setPadding(Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 48), 0);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    body.addView(Ui.logo(this), new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
    body.addView(Ui.spacer(this, 28));
    body.addView(Ui.text(this, "请查看下面的使用条款和隐私政策。", 18, Ui.INK));
    body.addView(Ui.spacer(this, 28));
    body.addView(Ui.text(this, "版本 1.0", 16, Ui.MUTED));
    body.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1));
    row(body, "使用条款 (1.0)", () -> policy(this, "使用条款", TERMS));
    row(body, "隐私政策 (1.0)", () -> policy(this, "隐私政策", PRIVACY));
    body.addView(Ui.spacer(this, 48));
    FrameLayout footer = new FrameLayout(this);
    TextView accept = Ui.bold(this, "同意并开始", 14);
    accept.setGravity(Gravity.CENTER);
    accept.setTextColor(Color.WHITE);
    accept.setBackground(Ui.shape(Ui.GREEN, Ui.dp(this, 8)));
    accept.setOnClickListener(
        v -> {
          getSharedPreferences("welcome", 0).edit().putBoolean("accepted", true).apply();
          begin();
        });
    FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, Ui.dp(this, 48));
    fp.setMargins(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 24));
    footer.addView(accept, fp);
    root.addView(footer);
  }

  private void row(LinearLayout body, String title, Runnable click) {
    LinearLayout row = Ui.row(this);
    row.addView(Ui.text(this, title, 14, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
    TextView more = Ui.bold(this, "查看更多", 14);
    more.setTextColor(Ui.GREEN);
    Ui.padding(more, 12, 0);
    more.setGravity(Gravity.CENTER);
    more.setOnClickListener(v -> click.run());
    row.addView(more, new LinearLayout.LayoutParams(-2, Ui.dp(this, 36)));
    body.addView(row);
  }

  private void begin() {
    java.util.ArrayList<android.net.Uri> shared = SharedImports.collect(this, getIntent());
    Intent next = new Intent(this, shared.isEmpty() ? CameraActivity.class : MainActivity.class);
    if (!shared.isEmpty()) SharedImports.forward(next, shared);
    startActivity(next);
    finish();
  }

  static void policy(Activity a, String title, String text) {
    Dialog d = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout header = Ui.row(a);
    header.addView(Ui.iconButton(a, "back", "返回", d::dismiss));
    TextView heading = Ui.text(a, title, 20, Ui.INK);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1);
    hp.leftMargin = Ui.dp(a, 16);
    header.addView(heading, hp);
    root.addView(header);
    ScrollView scroll = new ScrollView(a);
    TextView body = Ui.text(a, text, 16, Ui.INK);
    Ui.padding(body, 24, 24);
    body.setTextIsSelectable(true);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Ui.dialogInsets(d, root);
    d.show();
    d.getWindow().setLayout(-1, -1);
  }
}
