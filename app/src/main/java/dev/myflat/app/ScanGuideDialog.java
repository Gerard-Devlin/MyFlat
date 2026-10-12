package dev.myflat.app;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;

/** Five-step first-run walkthrough, also available from the camera's quick menu. */
final class ScanGuideDialog extends Dialog {
  private static final int[] IMAGES = {
    R.drawable.guide_original,
    R.drawable.guide_flattened,
    R.drawable.guide_enhanced,
    R.drawable.guide_finished
  };
  private static final String[] CAPTIONS = {"原始图片", "弯曲校正", "增强", "去除手指"};
  private int step;
  private final LinearLayout card;
  private final Runnable complete;

  ScanGuideDialog(Activity activity, int step, Runnable complete) {
    super(activity);
    this.step = Math.max(0, Math.min(4, step));
    this.complete = complete;
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    setCanceledOnTouchOutside(false);
    setCancelable(false);
    card = Ui.column(activity);
    card.setGravity(Gravity.CENTER_HORIZONTAL);
    card.setBackground(Ui.shape(Color.WHITE, Ui.dp(activity, 14)));
    Ui.padding(card, 20, 20);
    setContentView(card);
    render();
  }

  int step() {
    return step;
  }

  @Override
  public void show() {
    super.show();
    Window window = getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    int width = getContext().getResources().getDisplayMetrics().widthPixels;
    int height = getContext().getResources().getDisplayMetrics().heightPixels;
    window.setLayout(
        Math.round(width * .875f), Math.min(Ui.dp(getContext(), 560), Math.round(height * .76f)));
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams flags = window.getAttributes();
    flags.dimAmount = .6f;
    window.setAttributes(flags);
  }

  private void render() {
    Context c = getContext();
    card.removeAllViews();
    TextView title = Ui.bold(c, "MyFlat 扫描指南", 22);
    title.setGravity(Gravity.CENTER);
    card.addView(title, new LinearLayout.LayoutParams(-1, -2));
    TextView subtitle = Ui.text(c, "这是 MyFlat 扫描的工作原理！", 14, Ui.INK);
    subtitle.setGravity(Gravity.CENTER);
    Ui.padding(subtitle, 0, 10);
    card.addView(subtitle);
    if (step < 4) {
      ImageView image = image(c, IMAGES[step]);
      image.setContentDescription(CAPTIONS[step] + "示例");
      card.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
      card.addView(new Steps(c, step), new LinearLayout.LayoutParams(-1, Ui.dp(c, 38)));
      TextView caption = Ui.bold(c, CAPTIONS[step], 18);
      caption.setGravity(Gravity.CENTER);
      card.addView(caption, new LinearLayout.LayoutParams(-1, Ui.dp(c, 40)));
    } else {
      LinearLayout comparison = Ui.row(c);
      comparison.setGravity(Gravity.CENTER);
      comparison.addView(example(c, IMAGES[0], "处理前"), new LinearLayout.LayoutParams(0, -1, 1));
      comparison.addView(example(c, IMAGES[3], "处理后"), new LinearLayout.LayoutParams(0, -1, 1));
      card.addView(comparison, new LinearLayout.LayoutParams(-1, 0, 1));
      TextView tip = Ui.text(c, "保持足够的距离，让书页四个角都在画面内。\n按住书页时，把手指放在边缘，避免遮挡文字。", 14, Ui.INK);
      tip.setGravity(Gravity.CENTER);
      Ui.padding(tip, 0, 14);
      card.addView(tip);
    }
    TextView next = Ui.bold(c, step == 4 ? "开始扫描" : "下一步", 18);
    next.setTextColor(Color.WHITE);
    next.setGravity(Gravity.CENTER);
    next.setBackground(Ui.shape(Ui.GREEN, Ui.dp(c, 28)));
    next.setOnClickListener(
        v -> {
          if (step < 4) {
            step++;
            render();
          } else {
            complete.run();
            dismiss();
          }
        });
    card.addView(next, new LinearLayout.LayoutParams(-1, Ui.dp(c, 48)));
  }

  private ImageView image(Context c, int resource) {
    ImageView image = new ImageView(c);
    image.setImageResource(resource);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    return image;
  }

  private LinearLayout example(Context c, int resource, String label) {
    LinearLayout box = Ui.column(c);
    Ui.padding(box, 4, 4);
    TextView title = Ui.bold(c, label, 14);
    title.setGravity(Gravity.CENTER);
    box.addView(title);
    box.addView(image(c, resource), new LinearLayout.LayoutParams(-1, 0, 1));
    return box;
  }

  @Override
  public void onBackPressed() {
    if (step > 0) {
      step--;
      render();
    } else super.onBackPressed();
  }

  private static final class Steps extends View {
    final int step;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Steps(Context c, int step) {
      super(c);
      this.step = step;
      setContentDescription("第 " + (step + 1) + " 步，共 4 步");
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float spacing = getWidth() * .22f,
          start = (getWidth() - spacing * 3) / 2,
          cy = getHeight() / 2f;
      for (int i = 0; i < 3; i++) {
        paint.setColor(0xffdddddd);
        for (float x = start + i * spacing + Ui.dp(getContext(), 10);
            x < start + (i + 1) * spacing - Ui.dp(getContext(), 8);
            x += Ui.dp(getContext(), 5)) canvas.drawCircle(x, cy, Ui.dp(getContext(), 1), paint);
      }
      for (int i = 0; i < 4; i++) {
        paint.setColor(i <= step ? Ui.GREEN : 0xffdedede);
        canvas.drawCircle(start + i * spacing, cy, Ui.dp(getContext(), 6), paint);
      }
    }
  }
}
