package dev.myflat.app;

import android.content.Context;
import android.widget.*;

/** Shared choices for imports, camera defaults and page reprocessing. */
final class EffectsForm {
  final LinearLayout view;
  private final CheckBox dewarp, enhance, fingers, straighten, handwriting, doublePage, rightFirst;
  private final SeekBar split;
  private final ScanSettings seed;

  EffectsForm(Context c, ScanSettings options, boolean allowSplit) {
    seed = ScanSettings.parse(options.json());
    view = Ui.column(c);
    Ui.padding(view, 20, 8);
    dewarp = Ui.check(c, "自动裁切与曲面展平", options.dewarp);
    enhance = Ui.check(c, "色彩增强 · 去阴影提白", options.enhance);
    fingers = Ui.check(c, "去除手指", options.fingers);
    straighten = Ui.check(c, "文字行拉直", options.straighten);
    handwriting = Ui.check(c, "手写消除", options.handwriting);
    view.addView(dewarp);
    view.addView(enhance);
    view.addView(fingers);
    view.addView(straighten);
    view.addView(handwriting);
    doublePage = Ui.check(c, "双页分开保存", options.doublePage);
    rightFirst = Ui.check(c, "双页按右页 → 左页排序", options.rightFirst);
    split = new SeekBar(c);
    split.setMax(40);
    split.setProgress(Math.round(options.split * 100) - 30);
    if (allowSplit) {
      view.addView(Ui.line(c));
      view.addView(doublePage);
      view.addView(rightFirst);
      view.addView(Ui.text(c, "书脊位置（左 30% — 右 70%）", 12, Ui.MUTED));
      view.addView(split);
    }
    TextView note = Ui.text(c, "所有处理均在设备本地完成，原图会保留。手写消除可能移除手写标注。", 12, Ui.MUTED);
    Ui.padding(note, 0, 12);
    view.addView(note);
  }

  ScanSettings value() {
    ScanSettings s = ScanSettings.parse(seed.json());
    s.dewarp = dewarp.isChecked();
    s.enhance = enhance.isChecked();
    s.fingers = fingers.isChecked();
    s.straighten = straighten.isChecked();
    s.handwriting = handwriting.isChecked();
    s.doublePage = doublePage.isChecked();
    s.rightFirst = rightFirst.isChecked();
    s.split = (split.getProgress() + 30) / 100f;
    return s;
  }
}
