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
    root.setBackgroundColor(0xfff5f5f5);
    LinearLayout head = Ui.row(a);
    head.addView(Ui.iconButton(a, "close", "取消", dialog::dismiss));
    TextView title = Ui.text(a, "请选择一个文件夹", 20, Ui.INK);
    head.setBackgroundColor(Color.WHITE);
    title.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1);
    hp.leftMargin = Ui.dp(a, 16);
    head.addView(title, hp);
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
          Ui.padding(content, 8, 8);
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
            cell.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 16));
            FrameLayout cover = new FrameLayout(a);
            android.graphics.drawable.GradientDrawable border = Ui.shape(0xfff5f5f5, Ui.dp(a, 8));
            border.setStroke(Ui.dp(a, 1), 0xffeeeeee);
            cover.setBackground(border);
            boolean locked = FolderSecurity.locked(a, f.id);
            java.util.List<DocumentStore.Document> docs =
                locked ? Collections.emptyList() : MyFlatApp.of(a).store.documents(f.id, "", false);
            String file = null;
            int count = 0;
            for (DocumentStore.Document d : docs) {
              if (file == null) file = d.cover;
              count += d.count;
            }
            if (!locked && f.cover != null && !f.cover.isEmpty()) {
              DocumentStore.Page custom = MyFlatApp.of(a).store.page(f.cover);
              if (custom != null && custom.deleted == 0)
                file = "ready".equals(custom.status) ? custom.result : custom.original;
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
            } else {
              Ui.Icon empty = new Ui.Icon(a, "empty_folder");
              empty.color = 0xffe1e1e1;
              cover.addView(
                  empty, new FrameLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40), Gravity.CENTER));
            }
            TextView check = Ui.text(a, selected[0] == f.id ? "✓" : "", 18, Color.WHITE);
            android.graphics.drawable.GradientDrawable ring =
                Ui.shape(selected[0] == f.id ? Ui.GREEN : Color.TRANSPARENT, Ui.dp(a, 12));
            ring.setStroke(Ui.dp(a, 2), selected[0] == f.id ? Ui.GREEN : 0xffbdbdbd);
            check.setBackground(ring);
            check.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams cp =
                new FrameLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24), Gravity.TOP | Gravity.END);
            cp.setMargins(0, Ui.dp(a, 12), Ui.dp(a, 12), 0);
            cover.addView(check, cp);
            int width =
                (a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 16)) / 3 - Ui.dp(a, 8);
            cell.addView(cover, new LinearLayout.LayoutParams(-1, Math.round(width * 1.4142f)));
            TextView name = Ui.bold(a, f.name, 14);
            AppAppearance.keepText(name);
            Ui.padding(name, 0, 8);
            cell.addView(name);
            LinearLayout detail = Ui.row(a);
            Ui.Icon pageIcon = new Ui.Icon(a, "page_count");
            detail.addView(pageIcon, new LinearLayout.LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16)));
            detail.addView(Ui.text(a, locked ? "" : " " + count, 12, Ui.MUTED));
            cell.addView(detail);
            cell.setContentDescription(f.name + (selected[0] == f.id ? "，已选中" : ""));
            cell.setOnClickListener(
                v -> {
                  FolderSecurity.require(
                      a,
                      f.id,
                      () -> {
                        selected[0] = f.id;
                        render[0].run();
                      },
                      () -> {});
                });
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
          }
          if (row != null)
            for (int i = folders.size() % 3; i > 0 && i < 3; i++)
              row.addView(new Space(a), new LinearLayout.LayoutParams(0, 1, 1));
          save.setAlpha(1);
          save.setEnabled(selected[0] > 0);
          save.setBackground(Ui.shape(selected[0] > 0 ? Ui.GREEN : 0xffe0e0e0, Ui.dp(a, 8)));
        };
    head.addView(
        Ui.iconButton(
            a,
            "folder_add",
            "新建文件夹",
            () ->
                LibraryInputDialog.createFolder(
                    a,
                    id -> {
                      selected[0] = id;
                      render[0].run();
                    })));
    root.addView(head);
    root.addView(Ui.line(a));
    ScrollView scroll = new ScrollView(a);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout footer = Ui.column(a);
    Ui.padding(footer, 8, 8);
    footer.setBackgroundColor(Color.WHITE);
    save.setTextSize(14);
    save.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    footer.addView(save, new LinearLayout.LayoutParams(-1, Ui.dp(a, 52)));
    root.addView(footer);
    render[0].run();
    Ui.dialogInsets(dialog, root);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
  }
}
