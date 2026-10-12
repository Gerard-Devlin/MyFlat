package dev.myflat.app;

import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.LongConsumer;

/** Folder destination screen shared by import and move operations. */
final class FolderPicker {
  private FolderPicker() {}

  static void show(Activity a, long initial, LongConsumer result) {
    Dialog dialog = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    long[] selected = {initial};
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout head = Ui.row(a);
    head.addView(Ui.iconButton(a, "back", "取消", dialog::dismiss));
    TextView title = Ui.bold(a, "请选择文件夹", 20);
    title.setGravity(Gravity.CENTER_VERTICAL);
    head.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1));
    LinearLayout content = Ui.column(a);
    TextView save =
        Ui.primary(
            a,
            "保存到所选文件夹",
            () -> {
              if (selected[0] > 0) {
                dialog.dismiss();
                result.accept(selected[0]);
              }
            });
    Runnable[] render = new Runnable[1];
    render[0] =
        () -> {
          content.removeAllViews();
          Ui.padding(content, 8, 12);
          java.util.List<DocumentStore.Folder> folders = MyFlatApp.of(a).store.folders();
          LinearLayout row = null;
          for (int i = 0; i < folders.size(); i++) {
            if (i % 3 == 0) {
              row = Ui.row(a);
              row.setGravity(Gravity.TOP);
              content.addView(row);
            }
            DocumentStore.Folder f = folders.get(i);
            LinearLayout cell = Ui.column(a);
            Ui.padding(cell, 4, 8);
            FrameLayout cover = new FrameLayout(a);
            cover.setBackground(Ui.shape(0xfff5f5f5, Ui.dp(a, 4)));
            java.util.List<DocumentStore.Document> docs =
                MyFlatApp.of(a).store.documents(f.id, "", false);
            String file = null;
            int count = 0;
            for (DocumentStore.Document d : docs) {
              if (file == null) file = d.cover;
              count += d.count;
            }
            if (file != null) {
              ImageView image = new ImageView(a);
              image.setScaleType(ImageView.ScaleType.FIT_CENTER);
              Ui.padding(image, 8, 8);
              cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
              try {
                Ui.thumbnail(image, MyFlatApp.of(a).store.file(file));
              } catch (Exception ignored) {
              }
            }
            TextView check = Ui.text(a, selected[0] == f.id ? "✓" : "", 18, Color.WHITE);
            android.graphics.drawable.GradientDrawable ring =
                Ui.shape(selected[0] == f.id ? Ui.GREEN : Color.TRANSPARENT, Ui.dp(a, 12));
            ring.setStroke(Ui.dp(a, 1), selected[0] == f.id ? Ui.GREEN : 0xffbbbbbb);
            check.setBackground(ring);
            check.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams cp =
                new FrameLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24), Gravity.TOP | Gravity.END);
            cp.setMargins(0, Ui.dp(a, 8), Ui.dp(a, 8), 0);
            cover.addView(check, cp);
            int width = (a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 40)) / 3;
            cell.addView(cover, new LinearLayout.LayoutParams(-1, Math.round(width * 1.4142f)));
            TextView name = Ui.bold(a, f.name, 14);
            Ui.padding(name, 0, 8);
            cell.addView(name);
            LinearLayout detail = Ui.row(a);
            Ui.Icon pageIcon = new Ui.Icon(a, "page_count");
            detail.addView(pageIcon, new LinearLayout.LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16)));
            detail.addView(Ui.text(a, " " + count, 12, Ui.MUTED));
            cell.addView(detail);
            cell.setContentDescription(f.name + (selected[0] == f.id ? "，已选中" : ""));
            cell.setOnClickListener(
                v -> {
                  selected[0] = f.id;
                  render[0].run();
                });
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
          }
          if (row != null)
            for (int i = folders.size() % 3; i > 0 && i < 3; i++)
              row.addView(new Space(a), new LinearLayout.LayoutParams(0, 1, 1));
          save.setAlpha(selected[0] > 0 ? 1 : .35f);
          save.setEnabled(selected[0] > 0);
        };
    head.addView(
        Ui.iconButton(
            a,
            "folder_add",
            "新建文件夹",
            () ->
                Ui.rename(
                    a,
                    "新建文件夹",
                    "",
                    name -> {
                      selected[0] = MyFlatApp.of(a).store.createFolder(name);
                      render[0].run();
                    })));
    root.addView(head);
    root.addView(Ui.line(a));
    ScrollView scroll = new ScrollView(a);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout footer = Ui.column(a);
    Ui.padding(footer, 16, 8);
    footer.addView(save, new LinearLayout.LayoutParams(-1, Ui.dp(a, 48)));
    root.addView(footer);
    render[0].run();
    Ui.dialogInsets(dialog, root);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
  }
}
