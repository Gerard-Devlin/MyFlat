package dev.myflat.app;

import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** Thumbnail selection for a PDF's source folder. */
final class ExportPageSelection {
  static void show(
      Activity a,
      String name,
      List<DocumentStore.Page> pages,
      Collection<String> initial,
      Consumer<List<String>> selected) {
    Dialog d = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    LinkedHashSet<String> checked = new LinkedHashSet<>(initial);
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout head = Ui.row(a);
    Runnable cancel =
        () ->
            Ui.alert(a)
                .setMessage("不会保存更改。你确定现在退出？")
                .setNegativeButton("继续", null)
                .setPositiveButton("退出", (dialog, w) -> d.dismiss())
                .show();
    head.addView(Ui.iconButton(a, "close", "关闭", cancel));
    TextView title = Ui.text(a, "请选择", 20, Ui.INK);
    head.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1));
    TextView all = Ui.text(a, "全选", 14, Ui.INK);
    Ui.padding(all, 16, 12);
    head.addView(all);
    root.addView(head);
    ScrollView scroll = new ScrollView(a);
    LinearLayout grid = Ui.column(a);
    grid.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 80));
    scroll.addView(grid);
    FrameLayout middle = new FrameLayout(a);
    middle.setBackgroundColor(Ui.PAPER);
    middle.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    root.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));
    Map<String, TextView> marks = new HashMap<>();
    Runnable update =
        () -> {
          title.setText(checked.isEmpty() ? "请选择" : "已选 " + checked.size());
          all.setText(checked.size() == pages.size() ? "全不选" : "全选");
          for (Map.Entry<String, TextView> entry : marks.entrySet()) {
            boolean on = checked.contains(entry.getKey());
            entry.getValue().setText(on ? "✓" : "");
            android.graphics.drawable.GradientDrawable circle =
                Ui.shape(on ? Ui.GREEN : 0x77000000, Ui.dp(a, 12));
            circle.setStroke(Ui.dp(a, 1), Color.WHITE);
            entry.getValue().setBackground(circle);
          }
        };
    LinearLayout row = null;
    int width = (a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 16)) / 3;
    for (int i = 0; i < pages.size(); i++) {
      DocumentStore.Page p = pages.get(i);
      if (i % 3 == 0) {
        row = Ui.row(a);
        row.setGravity(Gravity.TOP);
        grid.addView(row);
      }
      LinearLayout cell = Ui.column(a);
      Ui.padding(cell, 4, 4);
      FrameLayout preview = new FrameLayout(a);
      ImageView image = new ImageView(a);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
      try {
        Ui.thumbnail(image, MyFlatApp.of(a).store.file(p.result));
      } catch (Exception ignored) {
      }
      TextView mark = Ui.text(a, "", 14, Color.WHITE);
      mark.setGravity(Gravity.CENTER);
      FrameLayout.LayoutParams mp =
          new FrameLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24), Gravity.TOP | Gravity.END);
      mp.setMargins(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), 0);
      preview.addView(mark, mp);
      marks.put(p.id, mark);
      cell.addView(preview, new LinearLayout.LayoutParams(-1, width - Ui.dp(a, 8)));
      TextView number = Ui.text(a, "" + (i + 1), 12, Ui.INK);
      number.setGravity(Gravity.CENTER);
      Ui.padding(number, 0, 8);
      cell.addView(number);
      cell.setContentDescription("第 " + (i + 1) + " 页");
      cell.setOnClickListener(
          v -> {
            if (!checked.remove(p.id)) checked.add(p.id);
            update.run();
          });
      cell.setOnLongClickListener(
          v -> {
            if (!checked.remove(p.id)) checked.add(p.id);
            update.run();
            return true;
          });
      row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    if (row != null)
      for (int i = pages.size() % 3; i > 0 && i < 3; i++)
        row.addView(new Space(a), new LinearLayout.LayoutParams(0, 1, 1));
    if (pages.isEmpty()) {
      TextView empty = Ui.text(a, "没有页面", 20, Ui.MUTED);
      empty.setGravity(Gravity.CENTER);
      middle.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    }
    all.setOnClickListener(
        v -> {
          if (checked.size() == pages.size()) checked.clear();
          else for (DocumentStore.Page p : pages) checked.add(p.id);
          update.run();
        });
    TextView done = Ui.bold(a, "选择", 14);
    done.setGravity(Gravity.CENTER);
    done.setTextColor(Color.WHITE);
    done.setBackground(Ui.shape(Ui.GREEN, Ui.dp(a, 8)));
    FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(-1, Ui.dp(a, 52), Gravity.BOTTOM);
    dp.setMargins(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 14));
    middle.addView(done, dp);
    done.setOnClickListener(
        v -> {
          List<String> result = new ArrayList<>();
          for (String id : initial) if (checked.contains(id)) result.add(id);
          for (DocumentStore.Page p : pages)
            if (checked.contains(p.id) && !result.contains(p.id)) result.add(p.id);
          selected.accept(result);
          d.dismiss();
        });
    d.setOnKeyListener(
        (dialog, key, e) -> {
          if (key == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            cancel.run();
            return true;
          }
          return false;
        });
    Ui.dialogInsets(d, root);
    d.show();
    d.getWindow().setLayout(-1, -1);
    update.run();
  }
}
