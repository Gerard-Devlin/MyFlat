package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.StatFs;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Storage totals and confirmed cleanup of trash, cache and retained originals. */
final class StorageManagementDialog {
  interface Work {
    void run() throws Exception;
  }

  static void show(Activity a) {
    Dialog dialog = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout header = Ui.row(a);
    header.addView(Ui.iconButton(a, "back", "返回", dialog::dismiss));
    TextView title = Ui.text(a, "管理存储", 20, Ui.INK);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1);
    hp.leftMargin = Ui.dp(a, 16);
    header.addView(title, hp);
    root.addView(header);
    ScrollView scroll = new ScrollView(a);
    LinearLayout body = Ui.column(a);
    Ui.padding(body, 16, 16);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    DocumentStore store = MyFlatApp.of(a).store;
    Runnable[] render = new Runnable[1];
    render[0] =
        () -> {
          body.removeAllViews();
          long free = new StatFs(a.getFilesDir().getPath()).getAvailableBytes();
          LinearLayout space = Ui.row(a);
          Ui.padding(space, 16, 16);
          space.setBackground(
              Ui.shape(free < 300L * 1024 * 1024 ? 0xfffff8e1 : 0xfff5f5f5, Ui.dp(a, 12)));
          space.addView(Ui.bold(a, "设备上可用空间", 14), new LinearLayout.LayoutParams(0, -2, 1));
          space.addView(Ui.text(a, Formatter.formatFileSize(a, free), 14, Ui.INK));
          body.addView(space);
          TextView warning = Ui.text(a, "确保你至少有300MB的可用存储空间，以避免使用MyFlat时出现问题。", 12, Ui.MUTED);
          LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
          wp.setMargins(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), 0);
          body.addView(warning, wp);
          Set<String> trashed = new HashSet<>();
          try (android.database.Cursor c =
              store
                  .getReadableDatabase()
                  .rawQuery(
                      "SELECT p.original,p.result FROM pages p JOIN documents d ON p.document=d.id"
                          + " JOIN folders f ON f.id=d.folder WHERE p.deleted>0 OR d.deleted>0 OR"
                          + " d.trashed=1 OR f.deleted>0",
                      null)) {
            while (c.moveToNext()) {
              trashed.add(c.getString(0));
              trashed.add(c.getString(1));
            }
          }
          long trashBytes = 0;
          for (String name : trashed)
            try {
              trashBytes += store.file(name).length();
            } catch (IOException ignored) {
            }
          add(
              a,
              body,
              "垃圾桶",
              trashBytes,
              "文件夹和图片在删除后会保留在垃圾桶中30天。您可以使用[清空垃圾桶]将其永久删除。",
              "清空垃圾桶",
              () ->
                  confirm(
                      a,
                      "清空垃圾桶",
                      "您确定要永久删除垃圾桶中的文件夹和页面吗？",
                      () -> {
                        for (DocumentStore.Folder f : store.folders(true))
                          store.permanentlyDeleteFolder(f.id);
                        for (DocumentStore.Document d : store.documents(0, "", true))
                          store.permanentlyDelete(d.id);
                        for (DocumentStore.Page p : store.trashPages()) store.deletePage(p.id);
                      },
                      render[0]));
          add(
              a,
              body,
              "缓存",
              size(a.getCacheDir()),
              "按[清除缓存]不会删除MyFlat中的图像。",
              "清除缓存",
              () ->
                  confirm(
                      a,
                      "清除缓存",
                      "清除临时文件？扫描图像将保留。",
                      () -> deleteChildren(a.getCacheDir().getCanonicalFile()),
                      render[0]));
          long originals = 0;
          for (DocumentStore.Document d : store.documents(0, "", false))
            for (DocumentStore.Page p : store.pages(d.id))
              if (!p.original.equals(p.result))
                try {
                  originals += store.file(p.original).length();
                } catch (IOException ignored) {
                }
          add(
              a,
              body,
              "原始图像",
              originals,
              "使用[删除原始图像]时，MyFlat中的图像不会被删除。如果您希望将来对原始图像进行增强，请保留您的原始图像。",
              "删除原始图像",
              () -> {
                Runnable remove =
                    () ->
                        confirm(
                            a, "删除原始图像", "原始照片将永久删除。当前扫描结果会保留。", store::clearOriginals, render[0]);
                if (FolderSecurity.hasSecureFolders(a))
                  FolderSecurity.authenticate(a, remove, () -> {});
                else remove.run();
              });
        };
    render[0].run();
    Ui.dialogInsets(dialog, root);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
  }

  private static void add(
      Activity a,
      LinearLayout body,
      String title,
      long bytes,
      String description,
      String action,
      Runnable click) {
    body.addView(Ui.spacer(a, 24));
    LinearLayout card = Ui.column(a);
    Ui.padding(card, 16, 16);
    card.setBackground(Ui.shape(0xfff5f5f5, Ui.dp(a, 12)));
    LinearLayout head = Ui.row(a);
    head.addView(Ui.bold(a, title, 14), new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Ui.text(a, Formatter.formatFileSize(a, bytes), 14, Ui.INK));
    card.addView(head);
    card.addView(Ui.spacer(a, 12));
    TextView details = Ui.text(a, description, 12, Ui.MUTED);
    details.setLineSpacing(0, 1.2f);
    card.addView(details);
    body.addView(card);
    TextView button = Ui.bold(a, action, 14);
    button.setTextColor(Ui.GREEN);
    button.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
    Ui.padding(button, 16, 12);
    button.setOnClickListener(v -> click.run());
    body.addView(button, new LinearLayout.LayoutParams(-1, Ui.dp(a, 48)));
  }

  private static void confirm(
      Activity a, String title, String message, Work work, Runnable changed) {
    Ui.alert(a)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确定",
            (d, w) -> {
              ProgressDialog progress = ProgressDialog.show(a, "", "处理中…", true, false);
              MyFlatApp.of(a)
                  .io
                  .submit(
                      () -> {
                        try {
                          work.run();
                          a.runOnUiThread(
                              () -> {
                                progress.dismiss();
                                if (!a.isFinishing()) {
                                  changed.run();
                                  Toast.makeText(a, "完成", Toast.LENGTH_SHORT).show();
                                }
                              });
                        } catch (Exception ex) {
                          a.runOnUiThread(
                              () -> {
                                progress.dismiss();
                                Ui.error(a, ex);
                              });
                        }
                      });
            })
        .show();
  }

  private static long size(File f) {
    if (f.isFile()) return f.length();
    long total = 0;
    File[] children = f.listFiles();
    if (children != null) for (File child : children) total += size(child);
    return total;
  }

  private static void deleteChildren(File root) throws IOException {
    File[] files = root.listFiles();
    if (files == null) return;
    for (File file : files) {
      File target = file.getCanonicalFile();
      if (!target.toPath().startsWith(root.toPath())) throw new IOException("缓存路径无效");
      if (target.isDirectory()) deleteChildren(target);
      if (!target.delete() && target.exists()) throw new IOException("无法清除缓存");
    }
  }
}
