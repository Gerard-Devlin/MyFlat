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
  private final Set<Long> selectedFolders = new LinkedHashSet<>();
  private Integer colorFilter;
  private boolean coverPicking, folderGateRequested;
  private ScrollView currentScroll;
  private String renderedLocation = "";
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
    AppAppearance.prepare(this);
    super.onCreate(state);
    trash = getIntent().getBooleanExtra("trash", false);
    store = MyFlatApp.of(this).store;
    MyFlatApp.of(this)
        .io
        .submit(
            () -> {
              try {
                store.pruneTrash();
              } catch (IOException ignored) {
              }
            });
    if (state != null) {
      documentId = state.getString("document");
      folderId = state.getLong("folder");
      trash = state.getBoolean("trash");
      selecting = state.getBoolean("selecting");
      ArrayList<String> selection = state.getStringArrayList("selected");
      if (selection != null) selected.addAll(selection);
      long[] folderSelection = state.getLongArray("selectedFolders");
      if (folderSelection != null) for (long id : folderSelection) selectedFolders.add(id);
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
        && !getIntent().hasExtra("shared_imports")
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 3);
    MyFlatApp.of(this).startProcessing();
    consumeImport();
  }

  private void consumeImport() {
    ArrayList<String> shared = getIntent().getStringArrayListExtra("shared_imports");
    if (shared != null) {
      getIntent().removeExtra("shared_imports");
      List<Uri> uris = new ArrayList<>();
      for (String value : shared) uris.add(Uri.parse(value));
      if (!uris.isEmpty()) importOptions(uris);
    } else if (getIntent().getBooleanExtra("import", false)) {
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
    out.putBoolean("selecting", selecting);
    out.putStringArrayList("selected", new ArrayList<>(selected));
    out.putLongArray(
        "selectedFolders", selectedFolders.stream().mapToLong(Long::longValue).toArray());
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
    selectedFolders.clear();
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
    List<DocumentStore.Page> pages = new ArrayList<>();
    if (documentId != null) pages.addAll(store.pages(documentId));
    else {
      List<DocumentStore.Document> docs = store.documents(folderId, "", false);
      Collections.reverse(docs);
      for (DocumentStore.Document doc : docs) pages.addAll(store.pages(doc.id));
    }
    int sort = getPreferences(0).getInt("pages_sort_" + folderId, 3);
    if (sort < 2) pages.sort(Comparator.comparingLong(p -> p.created));
    if (sort == 0 || sort == 2) Collections.reverse(pages);
    if (colorFilter != null) pages.removeIf(p -> p.colorTag != colorFilter);
    return pages;
  }

  private int coverHeight() {
    float width =
        getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
    return Ui.dp(this, ((width - 16) / 3 - 8) * 1.4142f);
  }

  private void refresh() {
    if (isFinishing() || isDestroyed()) return;
    if (folderId > 0 && !FolderSecurity.isAccessible(this, folderId)) {
      if (!folderGateRequested) {
        folderGateRequested = true;
        root = Ui.root(this);
        FolderSecurity.require(
            this,
            folderId,
            () -> {
              folderGateRequested = false;
              refresh();
            },
            () -> {
              folderGateRequested = false;
              folderId = 0;
              documentId = null;
              refresh();
            });
      }
      return;
    }
    String location = folderId + ":" + documentId + ":" + trash;
    int scrollY =
        location.equals(renderedLocation) && currentScroll != null ? currentScroll.getScrollY() : 0;
    renderedLocation = location;
    DocumentStore.Document document = documentId == null ? null : store.document(documentId);
    if (documentId != null && document == null) documentId = null;
    root = Ui.root(this);
    LinearLayout header = Ui.row(this);
    Ui.padding(header, 0, 0);
    header.addView(
        Ui.iconButton(this, selecting ? "close" : "back", selecting ? "取消选择" : "返回", this::back));
    String title =
        selecting
            ? (selectionCount() == 0 ? "请选择" : "已选择" + selectionCount())
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
      AppAppearance.keepText(heading);
      heading.setSingleLine(true);
      heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
      heading.setGravity(Gravity.CENTER_VERTICAL);
      if (folderId > 0 && !selecting && !trash) {
        heading.setBackgroundResource(R.drawable.myflat_dash_underline);
        heading.setOnClickListener(v -> renameCurrent());
      }
      header.addView(heading, hp);
    }
    if (!trash && !selecting) header.addView(Ui.iconButton(this, "search", "搜索", this::search));
    if (trash && !selecting) {
      header.addView(
          headerText(
              "选择",
              () -> {
                selecting = true;
                refresh();
              }));
      header.addView(headerText("清除", this::emptyTrash));
    } else {
      View toolbarMenu = Ui.iconButton(this, "menu", "更多", () -> {});
      toolbarMenu.setOnClickListener(v -> toolbarMenu(v));
      header.addView(toolbarMenu);
    }
    header.setElevation(Ui.dp(this, 1));
    root.addView(header);
    if (trash) {
      TextView notice = Ui.text(this, "回收站中的项目将在 30 天后完全删除。", 14, Ui.INK);
      Ui.padding(notice, 12, 12);
      root.addView(notice);
    }
    FrameLayout center = new FrameLayout(this);
    boolean folders = folderId == 0 && documentId == null && !trash && search.isEmpty();
    center.setBackgroundColor(folders || trash ? Color.WHITE : 0xfff5f5f5);
    root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
    ScrollView scroll = new ScrollView(this);
    currentScroll = scroll;
    scroll.post(() -> scroll.scrollTo(0, scrollY));
    scroll.setFillViewport(true);
    center.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout content = Ui.column(this);
    Ui.padding(content, 8, folders || trash ? 0 : 16);
    scroll.addView(content);
    if (folders) folders(content);
    else if (trash) trashGrid(content);
    else if (folderId == 0 && !search.isEmpty()) documents(content);
    else pages(content);
    if (!trash && !selecting) {
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
      addAction(bar, "restore", "恢复", this::restoreSelected);
      addAction(bar, "trash", "删除", this::deleteSelected);
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
    LibraryInputDialog.createFolder(this, id -> refresh());
  }

  private void deleteAction() {
    selecting = true;
    selected.clear();
    selectedFolders.clear();
    refresh();
  }

  private void folders(LinearLayout content) {
    List<DocumentStore.Folder> folders = new ArrayList<>(store.folders());
    int sort = getPreferences(0).getInt("folder_sort", 2);
    if (sort < 2) folders.sort(Comparator.comparing(f -> f.name));
    else if (sort >= 4) folders.sort(Comparator.comparingLong(f -> f.updated));
    if (sort == 1 || sort == 3 || sort == 5) Collections.reverse(folders);
    LinearLayout row = null;
    for (int i = 0; i < folders.size(); i++) {
      if (i % 3 == 0) {
        row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        content.addView(row);
      }
      DocumentStore.Folder folder = folders.get(i);
      LinearLayout card = Ui.column(this);
      card.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 16));
      FrameLayout cover = new FrameLayout(this);
      android.graphics.drawable.GradientDrawable border = Ui.shape(0xfff5f5f5, Ui.dp(this, 6));
      border.setStroke(Ui.dp(this, 1), 0xffeeeeee);
      cover.setBackground(border);
      boolean locked = FolderSecurity.locked(this, folder.id);
      List<DocumentStore.Document> docs =
          locked ? Collections.emptyList() : store.documents(folder.id, "", false);
      int count = 0;
      String thumbnail = null;
      for (DocumentStore.Document doc : docs) {
        count += doc.count;
        if (thumbnail == null) thumbnail = doc.cover;
      }
      if (!locked && folder.cover != null && !folder.cover.isEmpty()) {
        DocumentStore.Page custom = store.page(folder.cover);
        if (custom != null && custom.deleted == 0)
          thumbnail = "ready".equals(custom.status) ? custom.result : custom.original;
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
      if (locked) {
        TextView lock = Ui.text(this, "●", 24, 0xffbdbdbd);
        lock.setGravity(Gravity.CENTER);
        cover.addView(lock, new FrameLayout.LayoutParams(-1, -1));
      }
      if (selecting) addSelection(cover, selectedFolders.contains(folder.id), true);
      card.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight()));
      TextView name = Ui.bold(this, folder.name, 14);
      AppAppearance.keepText(name);
      name.setMaxLines(2);
      Ui.padding(name, 0, 8);
      card.addView(name);
      LinearLayout detail = Ui.row(this);
      Ui.Icon pages = new Ui.Icon(this, "page_count");
      pages.color = 0xffe1e1e1;
      detail.addView(pages, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
      detail.addView(Ui.text(this, locked ? "" : " " + count, 12, 0xff808080));
      card.addView(detail);
      card.setContentDescription(folder.name + "，" + count + "页");
      card.setOnClickListener(
          v -> {
            if (selecting) {
              if (!selectedFolders.add(folder.id)) selectedFolders.remove(folder.id);
              refresh();
            } else openFolder(folder.id);
          });
      card.setOnLongClickListener(
          v -> {
            FolderSecurity.require(
                this,
                folder.id,
                () -> {
                  selecting = true;
                  selectedFolders.add(folder.id);
                  refresh();
                },
                () -> {});
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
      Ui.padding(card, 4, 4);
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
      if (page.colorTag != 0) {
        View tag = new View(this);
        tag.setBackground(Ui.shape(page.colorTag, Ui.dp(this, 4)));
        FrameLayout.LayoutParams tp =
            new FrameLayout.LayoutParams(
                Ui.dp(this, 16), Ui.dp(this, 16), Gravity.TOP | Gravity.END);
        tp.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 8), 0);
        cover.addView(tag, tp);
      }
      if (selecting) addSelection(cover, selected.contains(page.id), false);
      card.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight()));
      TextView number = Ui.text(this, "" + (i + 1), 12, Ui.INK);
      number.setIncludeFontPadding(false);
      number.setGravity(Gravity.CENTER);
      Ui.padding(number, 0, 8);
      card.addView(number);
      row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
      card.setContentDescription("第 " + (i + 1) + " 页，" + page.status);
      card.setOnClickListener(
          v -> {
            if (coverPicking) {
              store.setFolderCover(folderId, page.id);
              coverPicking = false;
              selecting = false;
              refresh();
              return;
            }
            if (selecting) {
              if (!selected.add(page.id)) selected.remove(page.id);
              refresh();
            } else if ("ready".equals(page.status))
              startActivity(
                  new Intent(this, EditorActivity.class)
                      .putExtra("page", page.id)
                      .putExtra("folder", documentId == null ? folderId : 0L));
            else if ("error".equals(page.status))
              Ui.alert(this)
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
    Ui.padding(empty, 32, 64);
    TextView heading = Ui.text(this, trash ? "没有项目被删除" : "让我们开始扫描吧", 20, Ui.INK);
    heading.setGravity(Gravity.CENTER);
    empty.addView(heading);
    if (!trash) {
      TextView note = Ui.text(this, "点击相机按钮开始扫描。", 16, Ui.MUTED);
      note.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
      np.topMargin = Ui.dp(this, 16);
      empty.addView(note, np);
    }
    box.addView(empty, new LinearLayout.LayoutParams(-1, -2));
  }

  private void camera() {
    Intent intent =
        new Intent(this, CameraActivity.class).putExtra("folder", folderId > 0 ? folderId : 1);
    if (documentId != null) intent.putExtra("document", documentId);
    startActivity(intent);
  }

  private void search() {
    LibrarySearchDialog.show(this, folderId, this::openFolder);
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
    Intent next =
        new Intent(this, ImportActivity.class)
            .putStringArrayListExtra("uris", values)
            .putExtra("folder", folderId)
            .putExtra("document", documentId)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    next.setClipData(getIntent().getClipData());
    startActivityForResult(next, 103);
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
    if (documentId != null) {
      DocumentStore.Document doc = store.document(documentId);
      if (doc == null) return;
      LibraryInputDialog.show(
          this,
          "改名",
          "名称",
          doc.title,
          "确定",
          null,
          name -> {
            store.renameDocument(doc.id, name);
            refresh();
          });
      return;
    }
    if (folderId > 0) {
      LibraryInputDialog.renameFolder(this, folderId, folderName(), this::refresh);
      return;
    }
    if (selectedFolders.size() != 1) {
      selecting = true;
      refresh();
      return;
    }
    long id = selectedFolders.iterator().next();
    FolderSecurity.require(
        this,
        id,
        () -> {
          for (DocumentStore.Folder f : store.folders())
            if (f.id == id)
              LibraryInputDialog.renameFolder(
                  this,
                  id,
                  f.name,
                  () -> {
                    selecting = false;
                    selectedFolders.clear();
                    refresh();
                  });
        },
        () -> {});
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
    PopupMenu popup = Ui.popup(this, anchor);
    if (selecting) {
      boolean all =
          selectionCount()
              == (trash
                  ? store.trashPages().size() + store.folders(true).size()
                  : folderId == 0 ? store.folders().size() : visiblePages().size());
      popup
          .getMenu()
          .add(all ? "取消全选" : "全选")
          .setOnMenuItemClickListener(
              item -> {
                selectAll(!all);
                return true;
              });
    } else if (folderId == 0 && documentId == null) {
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
          .add("页面排序方式")
          .setOnMenuItemClickListener(
              item -> {
                sortPages();
                return true;
              });
      popup
          .getMenu()
          .add("显示标记的页面")
          .setOnMenuItemClickListener(
              item -> {
                filterTags();
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
          .add("更改封面")
          .setOnMenuItemClickListener(
              item -> {
                coverPicking = true;
                selecting = true;
                refresh();
                return true;
              });
      popup
          .getMenu()
          .add("变更资料夹名称")
          .setOnMenuItemClickListener(
              item -> {
                renameCurrent();
                return true;
              });
      if (folderId > 0) {
        popup
            .getMenu()
            .add(store.folderLocked(folderId) ? "解除安全文件夹" : "设置安全文件夹")
            .setOnMenuItemClickListener(
                item -> {
                  FolderSecurity.lockFolder(
                      this,
                      folderId,
                      () -> {
                        if (store.folderLocked(folderId)) {
                          folderId = 0;
                          documentId = null;
                          selected.clear();
                          selecting = false;
                        }
                        refresh();
                      });
                  return true;
                });
        popup
            .getMenu()
            .add("删除资料夹")
            .setOnMenuItemClickListener(
                item -> {
                  selectedFolders.clear();
                  selectedFolders.add(folderId);
                  deleteFolders();
                  return true;
                });
      }
    }
    if (!selecting && !trash) {
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
                selected.clear();
                selectedFolders.clear();
                FolderSecurity.clearProtection(this);
                refresh();
                return true;
              });
    }
    popup.show();
  }

  private void sortFolders() {
    String[] names = {"文件夹名称，升序", "文件夹名称，降序", "创建日期，升序", "创建日期，降序", "修改日期，升序", "修改日期，降序"};
    Ui.alert(this)
        .setTitle("文件夹排序方式")
        .setSingleChoiceItems(
            names,
            getPreferences(0).getInt("folder_sort", 2),
            (d, w) -> {
              getPreferences(0).edit().putInt("folder_sort", w).apply();
              d.dismiss();
              refresh();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void folderMenu(DocumentStore.Folder f) {
    Ui.alert(this)
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
                Ui.alert(this)
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
      Ui.alert(this)
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
    Ui.alert(this)
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
    Ui.alert(this)
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
    if (!trash && (folderId == 0 || !selectedFolders.isEmpty())) {
      deleteFolders();
      return;
    }
    if (selectionCount() == 0) {
      selecting = true;
      refresh();
      return;
    }
    List<String> ids = new ArrayList<>(selected);
    if (!trash) {
      confirm(
          "您确定要删除所选页面吗？",
          () -> {
            store.trashPages(ids);
            selected.clear();
            selecting = false;
            refresh();
          });
      return;
    }
    confirm(
        "彻底删除所选项目？",
        () ->
            MyFlatApp.of(this)
                .io
                .submit(
                    () -> {
                      try {
                        for (String id : ids) store.deletePage(id);
                        for (long id : new ArrayList<>(selectedFolders))
                          store.permanentlyDeleteFolder(id);
                        runOnUiThread(
                            () -> {
                              selected.clear();
                              selectedFolders.clear();
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
        "您确定要清空回收站吗？",
        () ->
            MyFlatApp.of(this)
                .io
                .submit(
                    () -> {
                      try {
                        for (DocumentStore.Page page : store.trashPages())
                          store.deletePage(page.id);
                        for (DocumentStore.Folder f : store.folders(true))
                          store.permanentlyDeleteFolder(f.id);
                        for (DocumentStore.Document d : store.documents(0, "", true))
                          store.permanentlyDelete(d.id);
                        runOnUiThread(this::refresh);
                      } catch (IOException ex) {
                        runOnUiThread(() -> Ui.error(this, ex));
                      }
                    }));
  }

  private void confirm(String message, Runnable run) {
    Ui.alert(this)
        .setMessage(message)
        .setPositiveButton("确认", (d, w) -> run.run())
        .setNegativeButton("取消", null)
        .show();
  }

  private int selectionCount() {
    return selected.size() + selectedFolders.size();
  }

  private TextView headerText(String name, Runnable run) {
    TextView view = Ui.text(this, name, 14, Ui.INK);
    view.setGravity(Gravity.CENTER);
    Ui.padding(view, 12, 0);
    view.setOnClickListener(v -> run.run());
    view.setLayoutParams(new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)));
    return view;
  }

  private void openFolder(long id) {
    FolderSecurity.require(
        this,
        id,
        () -> {
          folderId = id;
          documentId = null;
          search = "";
          selected.clear();
          selectedFolders.clear();
          selecting = false;
          colorFilter = null;
          refresh();
        },
        () -> {});
  }

  private void addSelection(FrameLayout cover, boolean checked, boolean folder) {
    android.graphics.drawable.GradientDrawable outline =
        Ui.shape(Color.TRANSPARENT, Ui.dp(this, folder ? 6 : 0));
    outline.setStroke(Ui.dp(this, checked ? 2 : 0), Ui.GREEN);
    View border = new View(this);
    border.setBackground(outline);
    cover.addView(border, new FrameLayout.LayoutParams(-1, -1));
    TextView mark = Ui.text(this, checked ? "✓" : "", 16, Color.WHITE);
    mark.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable ring =
        Ui.shape(checked ? Ui.GREEN : 0xccffffff, Ui.dp(this, 12));
    if (!checked) ring.setStroke(Ui.dp(this, 2), 0xffbdbdbd);
    mark.setBackground(ring);
    FrameLayout.LayoutParams cp =
        new FrameLayout.LayoutParams(
            Ui.dp(this, folder ? 24 : 20),
            Ui.dp(this, folder ? 24 : 20),
            (folder ? Gravity.TOP : Gravity.BOTTOM) | Gravity.END);
    cp.setMargins(0, Ui.dp(this, folder ? 12 : 8), Ui.dp(this, folder ? 12 : 8), Ui.dp(this, 8));
    cover.addView(mark, cp);
  }

  private void selectAll(boolean all) {
    selected.clear();
    selectedFolders.clear();
    if (all) {
      if (trash) {
        for (DocumentStore.Page p : store.trashPages()) selected.add(p.id);
        for (DocumentStore.Folder f : store.folders(true)) selectedFolders.add(f.id);
      } else if (folderId == 0 && documentId == null) {
        for (DocumentStore.Folder f : store.folders()) selectedFolders.add(f.id);
      } else for (DocumentStore.Page p : visiblePages()) selected.add(p.id);
    }
    refresh();
  }

  private void requireFolders(List<Long> ids, int at, Runnable allowed) {
    if (at >= ids.size()) {
      allowed.run();
      return;
    }
    FolderSecurity.require(this, ids.get(at), () -> requireFolders(ids, at + 1, allowed), () -> {});
  }

  private void deleteFolders() {
    if (selectedFolders.isEmpty()) {
      selecting = true;
      refresh();
      return;
    }
    List<Long> ids = new ArrayList<>(selectedFolders);
    if (ids.contains(1L)) {
      Toast.makeText(this, "您无法删除默认文件夹。", Toast.LENGTH_SHORT).show();
      return;
    }
    requireFolders(
        ids,
        0,
        () ->
            confirm(
                "您确定要删除所选文件夹吗？",
                () -> {
                  for (long id : ids) store.trashFolder(id, true);
                  selectedFolders.clear();
                  selected.clear();
                  selecting = false;
                  if (folderId > 0) {
                    folderId = 0;
                    documentId = null;
                    FolderSecurity.clearProtection(this);
                  }
                  refresh();
                }));
  }

  private void restoreSelected() {
    if (selectionCount() == 0) {
      selecting = true;
      refresh();
      return;
    }
    store.restorePages(new ArrayList<>(selected));
    for (long id : new ArrayList<>(selectedFolders)) store.trashFolder(id, false);
    selected.clear();
    selectedFolders.clear();
    selecting = false;
    refresh();
    MyFlatApp.of(this).startProcessing();
  }

  private void sortPages() {
    String[] names = {"创建日期，降序", "创建日期，升序", "页码，降序", "页码，升序"};
    Ui.alert(this)
        .setTitle("页面排序方式")
        .setSingleChoiceItems(
            names,
            getPreferences(0).getInt("pages_sort_" + folderId, 3),
            (d, w) -> {
              getPreferences(0).edit().putInt("pages_sort_" + folderId, w).apply();
              d.dismiss();
              refresh();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void filterTags() {
    String[] names = {"全部页面", "红色", "黄色", "绿色", "蓝色", "紫色", "无标记"};
    int[] colors = {0, 0xffe57373, 0xffffd54f, 0xff81c784, 0xff90caf9, 0xffce93d8, 0};
    Ui.alert(this)
        .setTitle("显示标记的页面")
        .setItems(
            names,
            (d, w) -> {
              colorFilter = w == 0 ? null : colors[w];
              refresh();
            })
        .show();
  }

  private void trashGrid(LinearLayout content) {
    List<DocumentStore.Folder> folders = store.folders(true);
    List<DocumentStore.Page> pages = new ArrayList<>(store.trashPages());
    pages.removeIf(
        p -> {
          DocumentStore.Document doc = store.document(p.document);
          return doc != null && !FolderSecurity.isAccessible(this, doc.folder);
        });
    if (folders.isEmpty() && pages.isEmpty()) {
      empty(content, "没有项目被删除", "", false);
      return;
    }
    LinearLayout row = null;
    int index = 0;
    for (DocumentStore.Folder folder : folders) {
      if (index++ % 3 == 0) {
        row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        content.addView(row);
      }
      LinearLayout cell = Ui.column(this);
      Ui.padding(cell, 4, 4);
      FrameLayout cover = new FrameLayout(this);
      cover.setBackgroundColor(0xfff5f5f5);
      Ui.Icon icon = new Ui.Icon(this, "empty_folder");
      icon.color = 0xffbdbdbd;
      cover.addView(
          icon, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));
      if (selecting) addSelection(cover, selectedFolders.contains(folder.id), true);
      cell.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight()));
      TextView title = Ui.text(this, folder.name, 13, Ui.INK);
      AppAppearance.keepText(title);
      Ui.padding(title, 0, 8);
      cell.addView(title);
      cell.setContentDescription(folder.name + "，已删除文件夹");
      cell.setOnClickListener(
          v -> {
            if (selecting) {
              if (!selectedFolders.add(folder.id)) selectedFolders.remove(folder.id);
              refresh();
            } else
              Ui.alert(this)
                  .setTitle(folder.name)
                  .setItems(
                      new String[] {"恢复", "彻底删除"},
                      (d, w) -> {
                        selectedFolders.clear();
                        selectedFolders.add(folder.id);
                        if (w == 0) restoreSelected();
                        else deleteSelected();
                      })
                  .show();
          });
      cell.setOnLongClickListener(
          v -> {
            selecting = true;
            selectedFolders.add(folder.id);
            refresh();
            return true;
          });
      row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    for (DocumentStore.Page page : pages) {
      if (index++ % 3 == 0) {
        row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        content.addView(row);
      }
      LinearLayout cell = Ui.column(this);
      Ui.padding(cell, 4, 4);
      FrameLayout cover = new FrameLayout(this);
      cover.setBackgroundColor(0xfff5f5f5);
      ImageView image = new ImageView(this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
      try {
        Ui.thumbnail(image, store.file("ready".equals(page.status) ? page.result : page.original));
      } catch (IOException ignored) {
      }
      if (selecting) addSelection(cover, selected.contains(page.id), true);
      cell.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight()));
      long date = page.deleted > 0 ? page.deleted : System.currentTimeMillis();
      TextView label =
          Ui.text(
              this,
              new java.text.SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
                  .format(new Date(date)),
              13,
              Ui.INK);
      label.setGravity(Gravity.CENTER);
      Ui.padding(label, 0, 8);
      cell.addView(label);
      cell.setContentDescription("已删除页面");
      cell.setOnClickListener(
          v -> {
            if (selecting) {
              if (!selected.add(page.id)) selected.remove(page.id);
              refresh();
            } else {
              selected.clear();
              selected.add(page.id);
              Ui.alert(this)
                  .setItems(
                      new String[] {"恢复", "彻底删除"},
                      (d, w) -> {
                        if (w == 0) restoreSelected();
                        else deleteSelected();
                      })
                  .show();
            }
          });
      cell.setOnLongClickListener(
          v -> {
            selecting = true;
            selected.add(page.id);
            refresh();
            return true;
          });
      row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    if (row != null)
      for (int n = index % 3; n > 0 && n < 3; n++)
        row.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
    content.addView(Ui.spacer(this, 86));
  }

  private void settings() {
    startActivity(new Intent(this, SettingsActivity.class));
  }

  private void export(Exporter.Format format) {
    if (documentId == null && folderId == 0) {
      if (selectedFolders.isEmpty()) {
        selecting = true;
        refresh();
        return;
      }
      List<Long> ids = new ArrayList<>(selectedFolders);
      requireFolders(
          ids,
          0,
          () -> {
            List<DocumentStore.Page> all = new ArrayList<>();
            for (long id : ids)
              for (DocumentStore.Document d : store.documents(id, "", false))
                all.addAll(store.pages(d.id));
            if (all.isEmpty()) {
              Toast.makeText(this, "请选择页面", Toast.LENGTH_SHORT).show();
              return;
            }
            String name =
                ids.size() == 1
                    ? store.folders().stream()
                        .filter(f -> f.id == ids.get(0))
                        .map(f -> f.name)
                        .findFirst()
                        .orElse("文件")
                    : "文件";
            ExportActivity.open(this, name, all, format);
          });
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
                      Ui.alert(this)
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
      coverPicking = false;
      selected.clear();
      selectedFolders.clear();
    } else if (documentId != null) documentId = null;
    else if (folderId > 0) {
      folderId = 0;
      colorFilter = null;
      FolderSecurity.clearProtection(this);
    } else if (trash) trash = false;
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
