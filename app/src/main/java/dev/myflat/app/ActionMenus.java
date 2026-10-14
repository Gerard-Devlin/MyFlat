package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Shared action sheet and persistent toolbar customization. */
final class ActionMenus {
  record Item(String icon, String title, Runnable run) {}

  private ActionMenus() {}

  static List<Item> ordered(Activity a, String scope, List<Item> available) {
    String saved = a.getSharedPreferences("menus", 0).getString(scope, "");
    List<Item> result = new ArrayList<>();
    for (String key : saved.split("\\|"))
      for (Item item : available)
        if (item.title().equals(key) && !result.contains(item)) result.add(item);
    if (saved.isEmpty()) result.addAll(available);
    return result;
  }

  static void show(Activity a, String scope, List<Item> available, Runnable updated) {
    Dialog dialog = new Dialog(a);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout card = Ui.column(a);
    GradientDrawable bg = Ui.shape(Color.WHITE, Ui.dp(a, 20));
    float r = Ui.dp(a, 20);
    bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
    card.setBackground(bg);
    card.setClipToOutline(true);
    LinearLayout header = Ui.row(a);
    Ui.padding(header, 24, 0);
    header.addView(Ui.bold(a, "更多", 18), new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(
        Ui.iconButton(
            a,
            "edit",
            "编辑菜单",
            () -> {
              dialog.dismiss();
              edit(a, scope, available, updated);
            }));
    card.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(a, 72)));
    card.addView(Ui.line(a));
    LinearLayout grid =
        grid(
            a,
            ordered(a, scope, available),
            5,
            item -> {
              dialog.dismiss();
              item.run().run();
            });
    grid.setBackgroundColor(0xfffafafa);
    Ui.padding(grid, 4, 4);
    card.addView(grid);
    View bottom = Ui.spacer(a, 16);
    bottom.setBackgroundColor(0xfffafafa);
    card.addView(bottom);
    dialog.setContentView(card);
    Window w = dialog.getWindow();
    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    dialog.setOnShowListener(
        d -> {
          w.setLayout(-1, -2);
          w.setGravity(Gravity.BOTTOM);
          WindowManager.LayoutParams lp = w.getAttributes();
          lp.dimAmount = .6f;
          w.setAttributes(lp);
          AppAppearance.dialog(dialog, card);
        });
    dialog.show();
  }

  interface Click {
    void accept(Item item);
  }

  private static LinearLayout grid(Activity a, List<Item> items, int columns, Click click) {
    LinearLayout grid = Ui.column(a);
    LinearLayout row = null;
    for (int i = 0; i < items.size(); i++) {
      if (i % columns == 0) {
        row = Ui.row(a);
        grid.addView(row);
      }
      Item item = items.get(i);
      View cell = Ui.action(a, item.icon(), item.title(), () -> click.accept(item));
      cell.setMinimumHeight(Ui.dp(a, 80));
      row.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(a, 80), 1));
    }
    if (row != null)
      for (int i = items.size() % columns; i > 0 && i < columns; i++)
        row.addView(new Space(a), new LinearLayout.LayoutParams(0, 1, 1));
    return grid;
  }

  private static void edit(Activity a, String scope, List<Item> available, Runnable updated) {
    Dialog dialog = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    List<Item> order = new ArrayList<>(ordered(a, scope, available));
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(0xfffafafa);
    LinearLayout head = Ui.row(a);
    head.addView(Ui.iconButton(a, "back", "返回", dialog::dismiss));
    TextView title = Ui.bold(a, "动作设置", 20);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1);
    titleParams.leftMargin = Ui.dp(a, 16);
    head.addView(title, titleParams);
    head.setBackgroundColor(Color.WHITE);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setTypeface(android.graphics.Typeface.DEFAULT);
    root.addView(head);
    LinearLayout current = Ui.column(a);
    GradientDrawable currentBackground = Ui.shape(Color.WHITE, 0);
    float bottomRadius = Ui.dp(a, 20);
    currentBackground.setCornerRadii(
        new float[] {0, 0, 0, 0, bottomRadius, bottomRadius, bottomRadius, bottomRadius});
    current.setBackground(currentBackground);
    TextView using = Ui.bold(a, "使用菜单", 12);
    Ui.padding(using, 24, 0);
    using.setTextColor(0xff616161);
    using.setGravity(Gravity.CENTER_VERTICAL);
    current.addView(using, new LinearLayout.LayoutParams(-1, Ui.dp(a, 44)));
    LinearLayout slots = Ui.column(a);
    Ui.padding(slots, 12, 0);
    current.addView(slots);
    TextView description = Ui.text(a, "", 12, 0xff616161);
    description.setText(
        android.text.Html.fromHtml(
            "<font color='#30BFA5'>按住</font>功能表然后拖动即可移动。<br>您也可以<font color='#30BFA5'>更改顺序。</font>",
            android.text.Html.FROM_HTML_MODE_LEGACY));
    description.setGravity(Gravity.CENTER);
    Ui.padding(description, 24, 24);
    current.addView(description);
    root.addView(current);
    TextView more = Ui.bold(a, "提供菜单", 12);
    Ui.padding(more, 24, 0);
    more.setTextColor(0xff616161);
    more.setGravity(Gravity.CENTER_VERTICAL);
    root.addView(more, new LinearLayout.LayoutParams(-1, Ui.dp(a, 44)));
    LinearLayout extra = Ui.column(a);
    root.addView(extra, new LinearLayout.LayoutParams(-1, 0, 1));
    List<Item> active = new ArrayList<>(order);
    Runnable[] render = new Runnable[1];
    render[0] =
        () -> {
          slots.removeAllViews();
          extra.removeAllViews();
          LinearLayout slotRow = null;
          for (int atIndex = 0; atIndex < active.size(); atIndex++) {
            if (atIndex % 4 == 0) {
              slotRow = Ui.row(a);
              slots.addView(slotRow);
            }
            Item item = active.get(atIndex);
            View cell =
                menuCell(
                    a,
                    item,
                    false,
                    () -> {
                      if (active.size() > 4) {
                        active.remove(item);
                        render[0].run();
                      } else Toast.makeText(a, "菜单至少需要 4 个项目", Toast.LENGTH_SHORT).show();
                    });
            cell.setOnLongClickListener(
                v -> {
                  v.startDragAndDrop(
                      ClipData.newPlainText("menu", item.title()),
                      new View.DragShadowBuilder(v),
                      item,
                      0);
                  return true;
                });
            cell.setOnDragListener(
                (v, e) -> {
                  if (e.getAction() == DragEvent.ACTION_DROP) {
                    Item dragged = (Item) e.getLocalState();
                    int at = active.indexOf(item);
                    if (active.remove(dragged)) {
                      active.add(Math.min(at, active.size()), dragged);
                      render[0].run();
                    }
                    return true;
                  }
                  return true;
                });
            slotRow.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(a, 90), 1));
          }
          if (slotRow != null)
            for (int n = active.size() % 4; n > 0 && n < 4; n++)
              slotRow.addView(new Space(a), new LinearLayout.LayoutParams(0, 1, 1));
          List<Item> inactive = new ArrayList<>(available);
          inactive.removeAll(active);
          extra.addView(
              menuGrid(
                  a,
                  inactive,
                  item -> {
                    active.add(item);
                    render[0].run();
                  }));
        };
    render[0].run();
    LinearLayout footer = Ui.row(a);
    Ui.padding(footer, 24, 24);
    TextView reset =
        Ui.pill(
            a,
            "重启",
            true,
            () -> {
              active.clear();
              active.addAll(available);
              render[0].run();
            });
    reset.setBackground(Ui.shape(0xffdff5f1, Ui.dp(a, 24)));
    reset.setTextColor(0xff27a193);
    footer.addView(reset, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1));
    footer.addView(new Space(a), new LinearLayout.LayoutParams(Ui.dp(a, 8), 1));
    footer.addView(
        Ui.primary(
            a,
            "完成",
            () -> {
              List<String> names = new ArrayList<>();
              for (Item item : active) names.add(item.title());
              a.getSharedPreferences("menus", 0)
                  .edit()
                  .putString(scope, String.join("|", names))
                  .putInt(scope + "_count", active.size())
                  .apply();
              dialog.dismiss();
              updated.run();
            }),
        new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1));
    root.addView(footer);
    Ui.dialogInsets(dialog, root);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
  }

  private static View menuCell(Activity a, Item item, boolean addable, Runnable click) {
    FrameLayout outer = new FrameLayout(a);
    Ui.padding(outer, 4, 4);
    LinearLayout tile = Ui.column(a);
    tile.setGravity(Gravity.CENTER_HORIZONTAL);
    GradientDrawable border = Ui.shape(Color.TRANSPARENT, Ui.dp(a, 8));
    if (addable) border.setStroke(Ui.dp(a, 1), 0xffbdbdbd, Ui.dp(a, 2), Ui.dp(a, 2));
    else border.setStroke(Ui.dp(a, 1), 0xffbdbdbd);
    tile.setBackground(border);
    tile.addView(Ui.spacer(a, 16));
    Ui.Icon icon = new Ui.Icon(a, item.icon());
    tile.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
    tile.addView(Ui.spacer(a, 8));
    TextView label = Ui.text(a, item.title(), 12, Ui.INK);
    label.setGravity(Gravity.CENTER);
    label.setSingleLine(true);
    tile.addView(label, new LinearLayout.LayoutParams(-1, -2));
    outer.addView(tile, new FrameLayout.LayoutParams(-1, -1));
    outer.setContentDescription(item.title());
    outer.setOnClickListener(v -> click.run());
    return outer;
  }

  private static LinearLayout menuGrid(Activity a, List<Item> items, Click click) {
    LinearLayout grid = Ui.column(a);
    Ui.padding(grid, 12, 0);
    LinearLayout row = null;
    for (int i = 0; i < items.size(); i++) {
      if (i % 4 == 0) {
        row = Ui.row(a);
        grid.addView(row);
      }
      Item item = items.get(i);
      row.addView(
          menuCell(a, item, true, () -> click.accept(item)),
          new LinearLayout.LayoutParams(0, Ui.dp(a, 90), 1));
    }
    if (row != null)
      for (int i = items.size() % 4; i > 0 && i < 4; i++)
        row.addView(new Space(a), new LinearLayout.LayoutParams(0, 1, 1));
    return grid;
  }

  static void toolbar(
      Activity a, LinearLayout bar, String scope, List<Item> available, Runnable updated) {
    List<Item> items = ordered(a, scope, available);
    int count = Math.min(4, items.size());
    for (int i = 0; i < count; i++) {
      Item item = items.get(i);
      bar.addView(
          Ui.action(a, item.icon(), item.title(), item.run()),
          new LinearLayout.LayoutParams(0, Ui.dp(a, 72), 1));
    }
    bar.addView(
        Ui.action(a, "more", "更多", () -> show(a, scope, available, updated)),
        new LinearLayout.LayoutParams(0, Ui.dp(a, 72), 1));
  }
}
