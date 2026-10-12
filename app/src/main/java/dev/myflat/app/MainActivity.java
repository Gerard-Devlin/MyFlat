package dev.myflat.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Folder/document navigation. Processing and file IO live outside the activity. */
public final class MainActivity extends Activity {
  private static final int PICK = 101, SAVE = 102;
  private DocumentStore store;
  private LinearLayout root;
  private TextView status;
  private String documentId, search = "";
  private long folderId = 0;
  private boolean trash = false, selecting = false;
  private final Set<String> selected = new LinkedHashSet<>();
  private File pendingExport;
  private boolean receiverRegistered;
  private final BroadcastReceiver changed =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
          String stage = intent.getStringExtra("stage");
          if (status != null) {
            status.setText(stage == null ? "" : stage);
            status.setVisibility(
                intent.getIntExtra("progress", 0) == 100 ? View.GONE : View.VISIBLE);
          }
          if (intent.getIntExtra("progress", 0) == 100) refresh();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    trash = getIntent().getBooleanExtra("trash", false);
    store = MyFlatApp.of(this).store;
    if (state != null) {
      documentId = state.getString("document");
      folderId = state.getLong("folder");
      trash = state.getBoolean("trash");
      String path = state.getString("export");
      if (path != null) pendingExport = new File(path);
    }
    if (state == null) {
      folderId = getIntent().getLongExtra("folder", 0);
      documentId = getIntent().getStringExtra("document");
    }
    refresh();
    if (Build.VERSION.SDK_INT >= 33
        && !getIntent().getBooleanExtra("import", false)
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 3);
    MyFlatApp.of(this).startProcessing();
    consumeImport();
  }

  private void consumeImport() {
    if (getIntent().getBooleanExtra("import", false)) {
      getIntent().removeExtra("import");
      pick();
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    IntentFilter filter = new IntentFilter(ScanService.CHANGED);
    androidx.core.content.ContextCompat.registerReceiver(
        this, changed, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    receiverRegistered = true;
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (store != null) {
      refresh();
      MyFlatApp.of(this).startProcessing();
    }
  }

  @Override
  protected void onStop() {
    if (receiverRegistered) {
      unregisterReceiver(changed);
      receiverRegistered = false;
    }
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("document", documentId);
    out.putLong("folder", folderId);
    out.putBoolean("trash", trash);
    if (pendingExport != null) out.putString("export", pendingExport.getPath());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    folderId = intent.getLongExtra("folder", 0);
    documentId = intent.getStringExtra("document");
    selecting = false;
    selected.clear();
    trash = intent.getBooleanExtra("trash", false);
    refresh();
    consumeImport();
  }

  private String folderName() {
    for (DocumentStore.Folder folder : store.folders())
      if (folder.id == folderId) return folder.name;
    return "库";
  }

  private List<DocumentStore.Page> visiblePages() {
    if (documentId != null) return store.pages(documentId);
    List<DocumentStore.Document> docs = store.documents(folderId, search, false);
    Collections.reverse(docs);
    List<DocumentStore.Page> pages = new ArrayList<>();
    for (DocumentStore.Document doc : docs) pages.addAll(store.pages(doc.id));
    return pages;
  }

  private int coverHeight() {
    float width =
        getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
    return Ui.dp(this, (width - 32) / 3 * 1.4142f);
  }

  private void refresh() {
    if (isFinishing() || isDestroyed()) return;
    DocumentStore.Document document = documentId == null ? null : store.document(documentId);
    if (documentId != null && document == null) documentId = null;
    root = Ui.root(this);
    LinearLayout header = Ui.row(this);
    Ui.padding(header, 0, 0);
    header.addView(Ui.iconButton(this, "back", "返回", this::back));
    String title =
        selecting
            ? "已选 " + selected.size() + " 页"
            : document != null
                ? document.title
                : trash ? "回收站" : folderId > 0 ? folderName() : "MyFlat 库";
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
    hp.leftMargin = Ui.dp(this, 16);
    if (folderId == 0 && !trash && documentId == null && !selecting) {
      LinearLayout brand = Ui.row(this);
      ImageView mark = Ui.logo(this);
      mark.setContentDescription("MyFlat");
      brand.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
      Space gap = new Space(this);
      brand.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
      brand.addView(Ui.bold(this, "库", 20));
      header.addView(brand, hp);
    } else {
      TextView heading = Ui.text(this, title, 20, Ui.INK);
      heading.setSingleLine(true);
      heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
      heading.setGravity(Gravity.CENTER_VERTICAL);
      header.addView(heading, hp);
    }
    if (!trash) header.addView(Ui.iconButton(this, "search", "搜索文档名称", this::search));
    View toolbarMenu = Ui.iconButton(this, "menu", "更多", () -> {});
    toolbarMenu.setOnClickListener(v -> toolbarMenu(v));
    header.addView(toolbarMenu);
    header.setElevation(Ui.dp(this, 1));
    root.addView(header);
    FrameLayout center = new FrameLayout(this);
    boolean folders = folderId == 0 && documentId == null && !trash && search.isEmpty();
    center.setBackgroundColor(folders || trash ? Color.WHITE : Ui.PAPER);
    root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    center.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout content = Ui.column(this);
    Ui.padding(content, 8, folders ? 4 : 24);
    scroll.addView(content);
    if (folders) folders(content);
    else if (trash || (folderId == 0 && !search.isEmpty())) documents(content);
    else pages(content);
    if (!trash) {
      FrameLayout fab = new FrameLayout(this);
      fab.setBackground(Ui.shape(Ui.GREEN, Ui.dp(this, 28)));
      fab.setElevation(Ui.dp(this, 4));
      Ui.Icon icon = new Ui.Icon(this, folders ? "folder_add" : "camera");
      icon.color = Color.WHITE;
      fab.addView(
          icon, new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
      FrameLayout.LayoutParams fp =
          new FrameLayout.LayoutParams(
              Ui.dp(this, 56), Ui.dp(this, 56), Gravity.BOTTOM | Gravity.END);
      fp.setMargins(0, 0, Ui.dp(this, 16), Ui.dp(this, 16));
      center.addView(fab, fp);
      fab.setContentDescription(folders ? "新建文件夹" : "拍照扫描");
      fab.setOnClickListener(
          v -> {
            if (folders) createFolder();
            else camera();
          });
    }
    status = Ui.text(this, "", 12, Ui.MUTED);
    status.setGravity(Gravity.CENTER);
    status.setVisibility(View.GONE);
    root.addView(status);
    root.addView(Ui.line(this));
    LinearLayout bar = Ui.row(this);
    Ui.padding(bar, 8, 0);
    if (trash) {
      addAction(
          bar,
          "folder",
          "全部恢复",
          () -> {
            for (DocumentStore.Document d : store.documents(0, "", true)) store.trash(d.id, false);
            refresh();
          });
      addAction(bar, "trash", "清空回收站", this::emptyTrash);
    } else {
      ActionMenus.toolbar(this, bar, folders ? "library" : "pages", menuItems(), this::refresh);
    }
    root.addView(bar);
  }

  private void addAction(LinearLayout bar, String icon, String label, Runnable run) {
    bar.addView(
        Ui.action(this, icon, label, run), new LinearLayout.LayoutParams(0, Ui.dp(this, 72), 1));
  }

  private void createFolder() {
    Ui.rename(
        this,
        "新建文件夹",
        "",
        name -> {
          store.createFolder(name);
          refresh();
        });
  }

  private void deleteAction() {
    if (documentId != null || folderId > 0) {
      selecting = true;
      refresh();
      Toast.makeText(this, "长按选择需要删除的页面", Toast.LENGTH_SHORT).show();
    } else {
      List<DocumentStore.Folder> folders = store.folders();
      new AlertDialog.Builder(this)
          .setTitle("选择文件夹")
          .setItems(
              folders.stream().map(f -> f.name).toArray(String[]::new),
              (d, w) -> folderMenu(folders.get(w)))
          .show();
    }
  }

  private void folders(LinearLayout content) {
    List<DocumentStore.Folder> folders = new ArrayList<>(store.folders());
    int sort = getPreferences(0).getInt("folder_sort", 3);
    if (sort < 2) folders.sort(java.util.Comparator.comparing(f -> f.name));
    if (sort == 1 || sort == 2) Collections.reverse(folders);
    LinearLayout row = null;
    for (int i = 0; i < folders.size(); i++) {
      if (i % 3 == 0) {
        row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        content.addView(row);
      }
      DocumentStore.Folder folder = folders.get(i);
      LinearLayout card = Ui.column(this);
      Ui.padding(card, 4, 8);
      FrameLayout cover = new FrameLayout(this);
      android.graphics.drawable.GradientDrawable border = Ui.shape(0xfff5f5f5, Ui.dp(this, 6));
      border.setStroke(Ui.dp(this, 1), 0xffeeeeee);
      cover.setBackground(border);
      List<DocumentStore.Document> docs = store.documents(folder.id, "", false);
      int count = 0;
      String thumbnail = null;
      for (DocumentStore.Document doc : docs) {
        count += doc.count;
        if (thumbnail == null) thumbnail = doc.cover;
      }
      if (thumbnail != null) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Ui.padding(image, 8, 8);
        cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
        try {
          Ui.thumbnail(image, store.file(thumbnail));
        } catch (IOException ignored) {
        }
      } else {
        Ui.Icon empty = new Ui.Icon(this, "empty_folder");
        empty.color = 0xffe1e1e1;
        cover.addView(
            empty, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));
      }
      card.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight()));
      TextView name = Ui.bold(this, folder.name, 14);
      name.setMaxLines(2);
      Ui.padding(name, 0, 8);
      card.addView(name);
      LinearLayout detail = Ui.row(this);
      Ui.Icon pages = new Ui.Icon(this, "page_count");
      pages.color = 0xffe1e1e1;
      detail.addView(pages, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
      detail.addView(Ui.text(this, " " + count, 12, 0xff808080));
      card.addView(detail);
      card.setContentDescription(folder.name + "，" + count + "页");
      card.setOnClickListener(
          v -> {
            folderId = folder.id;
            documentId = null;
            refresh();
          });
      card.setOnLongClickListener(
          v -> {
            folderMenu(folder);
            return true;
          });
      row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
    }
    if (row != null)
      for (int i = folders.size() % 3; i > 0 && i < 3; i++)
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
  }

  private void documents(LinearLayout content) {
    List<DocumentStore.Document> docs = store.documents(folderId, search, trash);
    if (docs.isEmpty()) {
      empty(
          content,
          trash ? "回收站是空的" : "把纸张，收进 MyFlat",
          trash ? "删除的文档会先保留在这里。" : "拍一本书、导入照片，或添加 PDF。\n每一页都在本地处理。",
          !trash);
      return;
    }
    LinearLayout row = null;
    for (int i = 0; i < docs.size(); i++) {
      if (i % 3 == 0) {
        row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        content.addView(row);
      }
      DocumentStore.Document d = docs.get(i);
      LinearLayout card = Ui.column(this);
      Ui.padding(card, 5, 7);
      FrameLayout cover = new FrameLayout(this);
      cover.setBackground(Ui.shape(Color.WHITE, Ui.dp(this, 6)));
      cover.setElevation(Ui.dp(this, 1));
      ImageView image = new ImageView(this);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      Ui.padding(image, 8, 8);
      cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
      if (d.cover != null) {
        try {
          Ui.thumbnail(image, store.file(d.cover));
        } catch (IOException ignored) {
        }
      } else {
        Ui.Icon icon = new Ui.Icon(this, "folder");
        icon.color = 0xffc8d4cf;
        cover.addView(
            icon, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));
      }
      card.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight()));
      TextView title = Ui.bold(this, d.title, 13);
      title.setMaxLines(2);
      Ui.padding(title, 0, 7);
      card.addView(title);
      card.addView(Ui.text(this, d.count + " 页" + (d.pending > 0 ? " · 处理中" : ""), 11, Ui.MUTED));
      row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
      card.setContentDescription(d.title + "，" + d.count + "页");
      card.setOnClickListener(
          v -> {
            if (trash) documentMenu(d);
            else {
              documentId = d.id;
              selected.clear();
              selecting = false;
              refresh();
            }
          });
      card.setOnLongClickListener(
          v -> {
            documentMenu(d);
            return true;
          });
    }
    if (row != null)
      for (int i = docs.size() % 3; i > 0 && i < 3; i++)
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    content.addView(Ui.spacer(this, 86));
  }

  private void pages(LinearLayout content) {
    List<DocumentStore.Page> pages = visiblePages();
    if (pages.isEmpty()) {
      empty(content, "还没有扫描页", "拍照或导入，开始整理这份文档。", true);
      return;
    }
    LinearLayout row = null;
    for (int i = 0; i < pages.size(); i++) {
      if (i % 3 == 0) {
        row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        content.addView(row);
      }
      DocumentStore.Page page = pages.get(i);
      LinearLayout card = Ui.column(this);
      Ui.padding(card, 5, 8);
      FrameLayout cover = new FrameLayout(this);
      cover.setBackground(Ui.shape(selected.contains(page.id) ? 0xffb5eee4 : 0xffeeeeee, 0));
      ImageView image = new ImageView(this);
      Ui.padding(image, 0, 0);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
      try {
        Ui.thumbnail(image, store.file("ready".equals(page.status) ? page.result : page.original));
      } catch (IOException ignored) {
      }
      if (!"ready".equals(page.status)) {
        TextView overlay =
            Ui.text(this, "error".equals(page.status) ? "重试" : "处理中", 12, Color.WHITE);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xaa182624);
        cover.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
      }
      if (selected.contains(page.id)) {
        TextView check = Ui.text(this, "✓", 22, Ui.GREEN);
        check.setBackgroundColor(Color.WHITE);
        cover.addView(
            check,
            new FrameLayout.LayoutParams(
                Ui.dp(this, 26), Ui.dp(this, 28), Gravity.TOP | Gravity.END));
      }
      card.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight()));
      TextView number = Ui.bold(this, "" + (i + 1), 13);
      number.setGravity(Gravity.CENTER);
      Ui.padding(number, 0, 6);
      card.addView(number);
      row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
      card.setContentDescription("第 " + (i + 1) + " 页，" + page.status);
      card.setOnClickListener(
          v -> {
            if (selecting) {
              if (!selected.add(page.id)) selected.remove(page.id);
              refresh();
            } else if ("ready".equals(page.status))
              startActivity(
                  new Intent(this, EditorActivity.class)
                      .putExtra("page", page.id)
                      .putExtra("folder", documentId == null ? folderId : 0L));
            else if ("error".equals(page.status))
              new AlertDialog.Builder(this)
                  .setTitle("处理未完成")
                  .setMessage(page.error)
                  .setPositiveButton(
                      "重试",
                      (d, w) -> {
                        store.queue(page.id, ScanSettings.parse(page.settings), page.rotation);
                        MyFlatApp.of(this).startProcessing();
                        refresh();
                      })
                  .setNegativeButton("取消", null)
                  .show();
          });
      card.setOnLongClickListener(
          v -> {
            selecting = true;
            selected.add(page.id);
            refresh();
            return true;
          });
    }
    if (row != null)
      for (int i = pages.size() % 3; i > 0 && i < 3; i++)
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    content.addView(Ui.spacer(this, 86));
  }

  private void empty(LinearLayout box, String title, String body, boolean actions) {
    LinearLayout empty = Ui.column(this);
    empty.setGravity(Gravity.CENTER);
    Ui.padding(empty, 24, 48);
    ImageView icon = Ui.logo(this);
    empty.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 82)));
    empty.addView(Ui.spacer(this, 22));
    TextView heading = Ui.bold(this, title, 22);
    heading.setGravity(Gravity.CENTER);
    empty.addView(heading);
    TextView note = Ui.text(this, body, 14, Ui.MUTED);
    note.setGravity(Gravity.CENTER);
    note.setLineSpacing(Ui.dp(this, 5), 1);
    Ui.padding(note, 0, 16);
    empty.addView(note);
    if (actions) {
      empty.addView(
          Ui.primary(this, "开始扫描", this::camera),
          new LinearLayout.LayoutParams(Ui.dp(this, 180), Ui.dp(this, 48)));
      empty.addView(Ui.spacer(this, 10));
      empty.addView(Ui.pill(this, "从图片 / PDF 导入", false, this::pick));
    }
    box.addView(empty);
  }

  private void camera() {
    Intent intent =
        new Intent(this, CameraActivity.class).putExtra("folder", folderId > 0 ? folderId : 1);
    if (documentId != null) intent.putExtra("document", documentId);
    startActivity(intent);
  }

  private void search() {
    Ui.rename(
        this,
        "搜索文档名称",
        search,
        value -> {
          search = value.trim();
          refresh();
        });
  }

  private void pick() {
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "application/pdf"})
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(intent, PICK);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null) return;
    if (request == 103) {
      folderId = data.getLongExtra("folder", 1);
      documentId = null;
      refresh();
    } else if (request == PICK) {
      List<Uri> uris = new ArrayList<>();
      if (data.getClipData() != null)
        for (int i = 0; i < data.getClipData().getItemCount(); i++)
          uris.add(data.getClipData().getItemAt(i).getUri());
      else if (data.getData() != null) uris.add(data.getData());
      if (!uris.isEmpty()) importOptions(uris);
    } else if (request == SAVE && data.getData() != null && pendingExport != null) {
      File file = pendingExport;
      Uri uri = data.getData();
      ProgressDialog dialog = busy("正在保存文件");
      MyFlatApp.of(this)
          .io
          .submit(
              () -> {
                try (InputStream in = new FileInputStream(file);
                    OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                  if (out == null) throw new IOException("无法写入所选位置");
                  Exporter.copy(in, out);
                  runOnUiThread(
                      () -> {
                        dialog.dismiss();
                        Toast.makeText(this, "文件已保存", Toast.LENGTH_SHORT).show();
                      });
                } catch (Exception ex) {
                  runOnUiThread(
                      () -> {
                        dialog.dismiss();
                        Ui.error(this, ex);
                      });
                }
              });
    }
  }

  private void importOptions(List<Uri> uris) {
    ArrayList<String> values = new ArrayList<>();
    for (Uri uri : uris) values.add(uri.toString());
    startActivityForResult(
        new Intent(this, ImportActivity.class)
            .putStringArrayListExtra("uris", values)
            .putExtra("folder", folderId)
            .putExtra("document", documentId),
        103);
  }

  private ProgressDialog busy(String message) {
    ProgressDialog d = new ProgressDialog(this);
    d.setMessage(message);
    d.setCancelable(false);
    d.show();
    return d;
  }

  private void moreActions() {
    ActionMenus.show(
        this,
        folderId == 0 && documentId == null ? "library" : "pages",
        menuItems(),
        this::refresh);
  }

  private List<ActionMenus.Item> menuItems() {
    boolean folders = folderId == 0 && documentId == null;
    List<ActionMenus.Item> items = new ArrayList<>();
    items.add(new ActionMenus.Item("import", "导入", this::pick));
    if (!folders) items.add(new ActionMenus.Item("share", "分享", () -> export(Exporter.Format.PDF)));
    items.add(new ActionMenus.Item("pdf", "创建 PDF", () -> export(Exporter.Format.PDF)));
    if (folders)
      items.add(new ActionMenus.Item("zip", "创建 ZIP", () -> export(Exporter.Format.ZIP)));
    items.add(
        new ActionMenus.Item("trash", "删除", selecting ? this::deleteSelected : this::deleteAction));
    if (!folders)
      items.add(new ActionMenus.Item("zip", "创建 ZIP", () -> export(Exporter.Format.ZIP)));
    items.add(new ActionMenus.Item("rename", "改名", this::renameCurrent));
    if (!folders) {
      items.add(new ActionMenus.Item("folder", "移动", this::moveSelected));
      items.add(new ActionMenus.Item("sort", "调整页序", this::reorder));
    }
    return items;
  }

  private void renameCurrent() {
    if (folderId > 0) {
      Ui.rename(
          this,
          "改名",
          folderName(),
          name -> {
            store.renameFolder(folderId, name);
            refresh();
          });
    } else {
      List<DocumentStore.Folder> folders = store.folders();
      new AlertDialog.Builder(this)
          .setTitle("选择文件夹")
          .setItems(
              folders.stream().map(f -> f.name).toArray(String[]::new),
              (d, w) -> {
                DocumentStore.Folder f = folders.get(w);
                Ui.rename(
                    this,
                    "改名",
                    f.name,
                    name -> {
                      store.renameFolder(f.id, name);
                      refresh();
                    });
              })
          .show();
    }
  }

  private void moveSelected() {
    if (!selecting || selected.isEmpty()) {
      selecting = true;
      refresh();
      return;
    }
    FolderPicker.show(
        this,
        0,
        id -> {
          store.movePages(new ArrayList<>(selected), id);
          selecting = false;
          selected.clear();
          refresh();
        });
  }

  private void toolbarMenu(View anchor) {
    PopupMenu popup = new PopupMenu(this, anchor);
    if (folderId == 0 && documentId == null) {
      popup
          .getMenu()
          .add("文件夹排序方式")
          .setOnMenuItemClickListener(
              item -> {
                sortFolders();
                return true;
              });
    } else {
      popup
          .getMenu()
          .add("更改页面顺序")
          .setOnMenuItemClickListener(
              item -> {
                reorder();
                return true;
              });
      popup
          .getMenu()
          .add("选择页面")
          .setOnMenuItemClickListener(
              item -> {
                selecting = true;
                refresh();
                return true;
              });
      popup
          .getMenu()
          .add("改名")
          .setOnMenuItemClickListener(
              item -> {
                renameCurrent();
                return true;
              });
    }
    popup
        .getMenu()
        .add("客户支持")
        .setOnMenuItemClickListener(
            item -> {
              Support.show(this);
              return true;
            });
    popup
        .getMenu()
        .add("设置")
        .setOnMenuItemClickListener(
            item -> {
              settings();
              return true;
            });
    popup
        .getMenu()
        .add("回收站")
        .setOnMenuItemClickListener(
            item -> {
              trash = true;
              folderId = 0;
              documentId = null;
              refresh();
              return true;
            });
    popup.show();
  }

  private void sortFolders() {
    String[] names = {"名称（升序）", "名称（降序）", "创建时间（最新）", "创建时间（最早）"};
    new AlertDialog.Builder(this)
        .setTitle("文件夹排序方式")
        .setSingleChoiceItems(
            names,
            getPreferences(0).getInt("folder_sort", 3),
            (d, w) -> {
              getPreferences(0).edit().putInt("folder_sort", w).apply();
              d.dismiss();
              refresh();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void folderMenu(DocumentStore.Folder f) {
    new AlertDialog.Builder(this)
        .setTitle(f.name)
        .setItems(
            f.id == 1 ? new String[] {"重命名"} : new String[] {"重命名", "删除文件夹"},
            (d, w) -> {
              if (w == 0)
                Ui.rename(
                    this,
                    "文件夹名称",
                    f.name,
                    n -> {
                      store.renameFolder(f.id, n);
                      refresh();
                    });
              else
                new AlertDialog.Builder(this)
                    .setMessage("文件夹内文档将移到默认文件夹。")
                    .setPositiveButton(
                        "删除文件夹",
                        (a, b) -> {
                          store.deleteFolder(f.id);
                          folderId = 1;
                          refresh();
                        })
                    .setNegativeButton("取消", null)
                    .show();
            })
        .show();
  }

  private void documentMenu(DocumentStore.Document doc) {
    if (trash) {
      new AlertDialog.Builder(this)
          .setTitle(doc.title)
          .setItems(
              new String[] {"恢复文档", "永久删除"},
              (d, w) -> {
                if (w == 0) {
                  store.trash(doc.id, false);
                  refresh();
                } else
                  confirm(
                      "永久删除这份文档及原图？",
                      () ->
                          MyFlatApp.of(this)
                              .io
                              .submit(
                                  () -> {
                                    try {
                                      store.permanentlyDelete(doc.id);
                                      runOnUiThread(this::refresh);
                                    } catch (IOException ex) {
                                      runOnUiThread(() -> Ui.error(this, ex));
                                    }
                                  }));
              })
          .show();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(doc.title)
        .setItems(
            new String[] {"重命名", "移动到文件夹", "放入回收站"},
            (d, w) -> {
              if (w == 0)
                Ui.rename(
                    this,
                    "重命名",
                    doc.title,
                    n -> {
                      store.renameDocument(doc.id, n);
                      refresh();
                    });
              else if (w == 1) move(doc);
              else {
                store.trash(doc.id, true);
                refresh();
              }
            })
        .show();
  }

  private void move(DocumentStore.Document doc) {
    List<DocumentStore.Folder> folders = store.folders();
    new AlertDialog.Builder(this)
        .setTitle("移动到")
        .setItems(
            folders.stream().map(f -> f.name).toArray(String[]::new),
            (d, w) -> {
              store.moveDocument(doc.id, folders.get(w).id);
              refresh();
            })
        .show();
  }

  private void reorder() {
    List<DocumentStore.Page> pages = visiblePages();
    PageOrderDialog.show(
        this,
        "更改页面顺序",
        pages,
        (image, page) -> {
          try {
            Ui.thumbnail(
                image, store.file("ready".equals(page.status) ? page.result : page.original));
          } catch (IOException ignored) {
          }
        },
        order -> {
          List<String> ids = new ArrayList<>();
          for (DocumentStore.Page page : order) ids.add(page.id);
          store.setPageOrder(ids, folderId, documentId);
          selected.clear();
          selecting = false;
          refresh();
        });
  }

  private void deleteSelected() {
    if (selected.isEmpty()) return;
    List<String> ids = new ArrayList<>(selected);
    confirm(
        "删除所选 " + ids.size() + " 页及其原图？",
        () ->
            MyFlatApp.of(this)
                .io
                .submit(
                    () -> {
                      try {
                        for (String id : ids) store.deletePage(id);
                        runOnUiThread(
                            () -> {
                              selected.clear();
                              selecting = false;
                              refresh();
                            });
                      } catch (IOException ex) {
                        runOnUiThread(() -> Ui.error(this, ex));
                      }
                    }));
  }

  private void emptyTrash() {
    confirm(
        "永久清空回收站？",
        () ->
            MyFlatApp.of(this)
                .io
                .submit(
                    () -> {
                      try {
                        for (DocumentStore.Document d : store.documents(0, "", true))
                          store.permanentlyDelete(d.id);
                        runOnUiThread(this::refresh);
                      } catch (IOException ex) {
                        runOnUiThread(() -> Ui.error(this, ex));
                      }
                    }));
  }

  private void confirm(String message, Runnable run) {
    new AlertDialog.Builder(this)
        .setMessage(message)
        .setPositiveButton("确认", (d, w) -> run.run())
        .setNegativeButton("取消", null)
        .show();
  }

  private void settings() {
    startActivity(new Intent(this, SettingsActivity.class));
  }

  private void export(Exporter.Format format) {
    if (documentId == null && folderId == 0) {
      List<DocumentStore.Document> docs = store.documents(folderId, search, false);
      if (docs.isEmpty()) {
        Toast.makeText(this, "请先扫描或导入文档", Toast.LENGTH_SHORT).show();
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("选择要导出的文档")
          .setItems(
              docs.stream().map(d -> d.title).toArray(String[]::new),
              (d, w) -> {
                documentId = docs.get(w).id;
                refresh();
                export(format);
              })
          .show();
      return;
    }
    List<DocumentStore.Page> pages = new ArrayList<>();
    for (DocumentStore.Page p : visiblePages())
      if (!selecting || selected.contains(p.id)) pages.add(p);
    if (pages.isEmpty()) {
      Toast.makeText(this, "请选择页面", Toast.LENGTH_SHORT).show();
      return;
    }
    DocumentStore.Document doc = documentId == null ? null : store.document(documentId);
    String exportTitle = doc == null ? folderName() : doc.title;
    ExportActivity.open(this, exportTitle, pages, format);
  }

  private void startExport(
      String title, List<DocumentStore.Page> pages, Exporter.Format format, boolean a4) {
    ProgressDialog dialog = busy("正在生成文件");
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                File file =
                    Exporter.create(
                        this,
                        title,
                        pages,
                        format,
                        a4,
                        (done, total) ->
                            runOnUiThread(
                                () -> dialog.setMessage("正在导出 " + done + " / " + total + " 页")));
                runOnUiThread(
                    () -> {
                      dialog.dismiss();
                      pendingExport = file;
                      new AlertDialog.Builder(this)
                          .setTitle("文件已生成")
                          .setMessage(
                              file.getName()
                                  + "\n"
                                  + String.format(
                                      Locale.getDefault(), "%.1f MB", file.length() / 1048576d))
                          .setPositiveButton("保存到文件", (d, w) -> saveExport())
                          .setNeutralButton("分享", (d, w) -> share(file))
                          .setNegativeButton("完成", null)
                          .show();
                    });
              } catch (Exception ex) {
                runOnUiThread(
                    () -> {
                      dialog.dismiss();
                      Ui.error(this, ex);
                    });
              }
            });
  }

  private void saveExport() {
    if (pendingExport == null) return;
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(type(pendingExport))
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, pendingExport.getName());
    startActivityForResult(intent, SAVE);
  }

  private String type(File file) {
    return file.getName().endsWith(".pdf")
        ? "application/pdf"
        : file.getName().endsWith(".zip") ? "application/zip" : "image/jpeg";
  }

  private void share(File file) {
    Uri uri = ShareProvider.uri(this, file);
    Intent intent =
        new Intent(Intent.ACTION_SEND)
            .setType(type(file))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newRawUri("MyFlat", uri));
    startActivity(Intent.createChooser(intent, "分享扫描文档"));
  }

  private void back() {
    if (selecting) {
      selecting = false;
      selected.clear();
    } else if (documentId != null) documentId = null;
    else if (folderId > 0) folderId = 0;
    else if (trash) trash = false;
    else {
      finish();
      return;
    }
    refresh();
  }

  @Override
  public void onBackPressed() {
    back();
  }
}
