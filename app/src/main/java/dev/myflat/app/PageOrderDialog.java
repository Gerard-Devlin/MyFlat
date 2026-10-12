package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.*;

/** The shared thumbnail grid used to drag pages into a new order. */
final class PageOrderDialog {
  interface Thumbnail<T> {
    void load(ImageView image, T item);
  }

  static <T> void show(
      Activity activity,
      String title,
      List<T> source,
      Thumbnail<T> thumbnail,
      Consumer<List<T>> save) {
    List<T> pages = new ArrayList<>(source);
    Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout root = Ui.column(activity);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout header = Ui.row(activity);
    header.addView(Ui.iconButton(activity, "back", "返回", dialog::dismiss));
    TextView heading = Ui.bold(activity, title, 20);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(heading, new LinearLayout.LayoutParams(0, Ui.dp(activity, 56), 1));
    header.addView(
        Ui.pill(
            activity,
            "完成",
            true,
            () -> {
              save.accept(pages);
              dialog.dismiss();
            }));
    root.addView(header);
    TextView tip = Ui.text(activity, "按住页面并拖动即可更改顺序。", 14, Ui.INK);
    tip.setGravity(Gravity.CENTER);
    tip.setBackgroundColor(0xfffff4bd);
    root.addView(tip, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 44)));
    ScrollView scroll = new ScrollView(activity);
    scroll.setBackgroundColor(0xffeeeeee);
    LinearLayout grid = Ui.column(activity);
    Ui.padding(grid, 8, 16);
    scroll.addView(grid);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Runnable[] render = new Runnable[1];
    render[0] =
        () -> {
          grid.removeAllViews();
          LinearLayout row = null;
          int width =
              (activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 40)) / 3;
          for (int i = 0; i < pages.size(); i++) {
            if (i % 3 == 0) {
              row = Ui.row(activity);
              row.setGravity(Gravity.TOP);
              grid.addView(row);
            }
            T page = pages.get(i);
            LinearLayout cell = Ui.column(activity);
            Ui.padding(cell, 4, 8);
            ImageView image = new ImageView(activity);
            image.setBackgroundColor(0xffdddddd);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumbnail.load(image, page);
            cell.addView(image, new LinearLayout.LayoutParams(-1, Math.round(width * 1.4142f)));
            TextView number = Ui.text(activity, "" + (i + 1), 14, Ui.INK);
            number.setGravity(Gravity.CENTER);
            Ui.padding(number, 0, 8);
            cell.addView(number);
            cell.setContentDescription("第 " + (i + 1) + " 页，长按移动");
            cell.setOnLongClickListener(
                v -> {
                  v.startDragAndDrop(
                      ClipData.newPlainText("page", ""), new View.DragShadowBuilder(v), page, 0);
                  return true;
                });
            cell.setOnDragListener(
                (v, event) -> {
                  if (event.getAction() == DragEvent.ACTION_DROP) {
                    Object dragged = event.getLocalState();
                    int from = pages.indexOf(dragged), to = pages.indexOf(page);
                    if (from >= 0 && to >= 0 && from != to) {
                      T moving = pages.remove(from);
                      pages.add(to, moving);
                      render[0].run();
                    }
                    return true;
                  }
                  return true;
                });
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
          }
          if (row != null)
            for (int i = pages.size() % 3; i > 0 && i < 3; i++)
              row.addView(new Space(activity), new LinearLayout.LayoutParams(0, 1, 1));
        };
    render[0].run();
    Ui.dialogInsets(dialog, root);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
  }
}
