package dev.myflat.app;

import android.app.*;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.TextDelegate;

/** The illustrated first-run guide and its replay entry point. */
final class ScanGuideDialog extends Dialog {
  private static final int[] IMAGES = {
    R.drawable.guide_original, R.drawable.guide_flattened,
    R.drawable.guide_enhanced, R.drawable.guide_finished
  };
  private static final String[] CAPTIONS = {"原始图片", "弯曲校正", "增强", "手指移开"};
  private static final String[] TIPS = {
    "保持足够的距离\n以使书的四个角都可见。",
    "当您需要用手指按住书本时，\n请将手指放在边缘的中间。",
    "在黑暗环境中打开闪光灯。",
    "当书附近没有其他物体时，\n可以获得更好的扫描结果。"
  };
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
    card.setBackground(Ui.shape(Color.WHITE, Ui.dp(activity, 12)));
    Ui.padding(card, 24, 24);
    setContentView(card);
    render(false);
  }

  int step() {
    return step;
  }

  @Override
  public void show() {
    super.show();
    getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams flags = getWindow().getAttributes();
    flags.dimAmount = .6f;
    getWindow().setAttributes(flags);
    size();
  }

  private void size() {
    int width = getContext().getResources().getDisplayMetrics().widthPixels;
    int height = getContext().getResources().getDisplayMetrics().heightPixels;
    getWindow()
        .setLayout(
            Math.min(Ui.dp(getContext(), 360), width - Ui.dp(getContext(), 48)),
            step == 4 ? -2 : Math.min(Ui.dp(getContext(), 560), Math.round(height * .85f)));
  }

  private void render(boolean animate) {
    Context c = getContext();
    card.removeAllViews();
    TextView title = Ui.bold(c, "MyFlat秘密", 18);
    title.setGravity(Gravity.CENTER);
    card.addView(title, new LinearLayout.LayoutParams(-1, -2));
    if (step < 4) {
      card.addView(Ui.spacer(c, 16));
      TextView subtitle = Ui.text(c, "这是MyFlat扫描的工作原理！", 14, Ui.INK);
      subtitle.setGravity(Gravity.CENTER);
      card.addView(subtitle);
      card.addView(Ui.spacer(c, 24));
      FrameLayout preview = new FrameLayout(c);
      ImageView image = image(c, IMAGES[step]);
      image.setContentDescription(CAPTIONS[step] + "示例");
      preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
      if (step == 3 && animate) {
        LottieAnimationView confetti = animation(c, R.raw.guide_confetti);
        preview.addView(confetti, new FrameLayout.LayoutParams(-1, -1));
        confetti.playAnimation();
      }
      card.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
      card.addView(Ui.spacer(c, 16));
      LottieAnimationView steps = animation(c, R.raw.guide_steps);
      steps.setContentDescription("第 " + (step + 1) + " 步，共 4 步");
      card.addView(steps, new LinearLayout.LayoutParams(-1, Ui.dp(c, 36)));
      if (animate && step > 0) {
        steps.setMinAndMaxFrame((step - 1) * 16, (step - 1) * 16 + 15);
        steps.playAnimation();
      } else steps.setFrame(step * 16);
      TextView caption = Ui.text(c, CAPTIONS[step], 14, 0xff666666);
      caption.setGravity(Gravity.CENTER);
      card.addView(caption, new LinearLayout.LayoutParams(-1, Ui.dp(c, 24)));
      card.addView(Ui.spacer(c, 24));
    } else {
      card.addView(Ui.spacer(c, 24));
      LinearLayout comparison = Ui.row(c);
      comparison.setGravity(Gravity.CENTER);
      comparison.addView(example(c, IMAGES[0], "处理前"), new LinearLayout.LayoutParams(0, -2, 1));
      comparison.addView(new Space(c), new LinearLayout.LayoutParams(Ui.dp(c, 12), 1));
      comparison.addView(example(c, IMAGES[3], "处理后"), new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(comparison);
      card.addView(Ui.spacer(c, 24));
      LottieAnimationView tips = animation(c, R.raw.guide_tips);
      TextDelegate delegate = new TextDelegate(tips);
      for (int i = 0; i < TIPS.length; i++) delegate.setText("TIP" + (i + 1), TIPS[i]);
      tips.setTextDelegate(delegate);
      tips.setRepeatCount(-1);
      tips.setFrame(0);
      tips.playAnimation();
      tips.setContentDescription(String.join(" ", TIPS));
      card.addView(tips, new LinearLayout.LayoutParams(-1, Ui.dp(c, 50)));
      card.addView(Ui.spacer(c, 24));
    }
    TextView next = Ui.bold(c, step == 4 ? "关闭" : "下一步", 14);
    next.setTextColor(Color.WHITE);
    next.setGravity(Gravity.CENTER);
    next.setBackground(Ui.shape(Ui.GREEN, Ui.dp(c, 24)));
    next.setOnClickListener(
        v -> {
          if (step < 4) {
            step++;
            render(true);
          } else {
            complete.run();
            dismiss();
          }
        });
    card.addView(next, new LinearLayout.LayoutParams(-1, Ui.dp(c, 48)));
    if (isShowing()) size();
  }

  private LottieAnimationView animation(Context c, int resource) {
    LottieAnimationView view = new LottieAnimationView(c);
    view.setFontAssetDelegate(
        new com.airbnb.lottie.FontAssetDelegate() {
          @Override
          public android.graphics.Typeface fetchFont(String family) {
            return android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL);
          }
        });
    view.setAnimation(resource);
    return view;
  }

  private ImageView image(Context c, int resource) {
    ImageView image = new ImageView(c);
    image.setImageResource(resource);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setAdjustViewBounds(true);
    image.setBackground(Ui.shape(Color.TRANSPARENT, Ui.dp(c, 6)));
    image.setClipToOutline(true);
    return image;
  }

  private LinearLayout example(Context c, int resource, String label) {
    LinearLayout box = Ui.column(c);
    box.addView(image(c, resource), new LinearLayout.LayoutParams(-1, Ui.dp(c, 190)));
    box.addView(Ui.spacer(c, 8));
    TextView title = Ui.bold(c, label, 12);
    title.setTextColor(0xff666666);
    title.setGravity(Gravity.CENTER);
    box.addView(title);
    return box;
  }

  @Override
  public void onBackPressed() {
    if (step > 0) {
      step--;
      render(false);
    } else super.onBackPressed();
  }
}
