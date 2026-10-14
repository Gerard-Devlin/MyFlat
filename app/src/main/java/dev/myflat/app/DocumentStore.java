package dev.myflat.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

/** App-private originals and results, with a recoverable SQLite processing queue. */
public final class DocumentStore extends SQLiteOpenHelper {
  public static final class Folder {
    public long id;
    public String name;
    public long created, updated, deleted;
    public String cover;
    public boolean secure;

    Folder(long id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public static final class Document {
    public String id, title, cover;
    public long folder, updated, deleted;
    public int count, pending;
    public boolean trashed;
  }

  public static final class Page {
    public String id, document, original, result, settings, status, error;
    public int position, rotation, width, height, colorTag;
    public long deleted, created;
  }

  private final File root;

  public DocumentStore(Context context) {
    super(context, "myflat.db", null, 3);
    root = new File(context.getFilesDir(), "pages");
    if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("无法创建文档目录");
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,created"
            + " INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT"
            + " NULL DEFAULT 0,secure INTEGER NOT NULL DEFAULT 0,cover TEXT NOT NULL DEFAULT '')");
    db.execSQL("INSERT INTO folders(id,name) VALUES(1,'默认文件夹')");
    db.execSQL(
        "CREATE TABLE documents(id TEXT PRIMARY KEY,title TEXT NOT NULL,folder INTEGER NOT NULL"
            + " REFERENCES folders(id),updated INTEGER NOT NULL,trashed INTEGER NOT NULL DEFAULT"
            + " 0,deleted INTEGER NOT NULL DEFAULT 0)");
    db.execSQL(
        "CREATE TABLE pages(id TEXT PRIMARY KEY,document TEXT NOT NULL REFERENCES documents(id) ON"
            + " DELETE CASCADE,position INTEGER NOT NULL,original TEXT NOT NULL,result TEXT NOT"
            + " NULL,settings TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL DEFAULT"
            + " '',rotation INTEGER NOT NULL DEFAULT 0,width INTEGER NOT NULL DEFAULT 0,height"
            + " INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,colorTag INTEGER NOT"
            + " NULL DEFAULT 0,created INTEGER NOT NULL DEFAULT 0)");
    db.execSQL("CREATE INDEX pages_document ON pages(document,position)");
    db.execSQL("CREATE INDEX pages_status ON pages(status)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) db.execSQL("UPDATE folders SET name='默认文件夹' WHERE id=1 AND name='收件箱'");
    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE folders ADD COLUMN created INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE folders ADD COLUMN updated INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE folders ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE folders ADD COLUMN secure INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE folders ADD COLUMN cover TEXT NOT NULL DEFAULT ''");
      db.execSQL("ALTER TABLE documents ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
      db.execSQL("UPDATE documents SET deleted=updated WHERE trashed=1");
      db.execSQL("ALTER TABLE pages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE pages ADD COLUMN colorTag INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE pages ADD COLUMN created INTEGER NOT NULL DEFAULT 0");
      db.execSQL(
          "UPDATE pages SET created=(SELECT updated FROM documents WHERE"
              + " documents.id=pages.document)");
    }
  }

  public File file(String relative) throws IOException {
    File file = new File(root, relative).getCanonicalFile();
    if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator))
      throw new IOException("Invalid document path");
    return file;
  }

  public synchronized List<Folder> folders() {
    return folders(false);
  }

  public synchronized List<Folder> folders(boolean trashed) {
    List<Folder> list = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,name,created,updated,deleted,secure,cover FROM folders WHERE deleted"
                    + (trashed ? ">0" : "=0")
                    + " ORDER BY id",
                null)) {
      while (c.moveToNext()) {
        Folder f = new Folder(c.getLong(0), c.getString(1));
        f.created = c.getLong(2);
        f.updated = c.getLong(3);
        f.deleted = c.getLong(4);
        f.secure = c.getInt(5) != 0;
        f.cover = c.getString(6);
        list.add(f);
      }
    }
    return list;
  }

  public long createFolder(String name) {
    ContentValues values = new ContentValues();
    values.put("name", title(name));
    values.put("created", System.currentTimeMillis());
    values.put("updated", System.currentTimeMillis());
    return getWritableDatabase().insertOrThrow("folders", null, values);
  }

  public void renameFolder(long id, String name) {
    ContentValues v = new ContentValues();
    v.put("name", title(name));
    v.put("updated", System.currentTimeMillis());
    getWritableDatabase().update("folders", v, "id=?", new String[] {"" + id});
  }

  public void deleteFolder(long id) {
    if (id == 1) throw new IllegalArgumentException("默认文件夹不能删除");
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("folder", 1);
      db.update("documents", v, "folder=?", new String[] {"" + id});
      db.delete("folders", "id=?", new String[] {"" + id});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public String createDocument(long folder, String name) {
    String id = UUID.randomUUID().toString();
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("title", title(name));
    v.put("folder", folder);
    v.put("updated", System.currentTimeMillis());
    getWritableDatabase().insertOrThrow("documents", null, v);
    return id;
  }

  public List<Document> documents(long folder, String query, boolean trash) {
    String filter =
        "d.trashed=? AND EXISTS(SELECT 1 FROM folders f WHERE f.id=d.folder AND f.deleted=0)";
    List<String> args = new ArrayList<>();
    args.add(trash ? "1" : "0");
    if (folder > 0 && !trash) {
      filter += " AND d.folder=?";
      args.add("" + folder);
    }
    if (query != null && !query.isEmpty()) {
      filter += " AND instr(lower(d.title),lower(?))>0";
      args.add(query);
    }
    List<Document> list = new ArrayList<>();
    String sql =
        "SELECT d.id,d.title,d.folder,d.updated,d.trashed,d.deleted,(SELECT count(*) FROM pages p"
            + " WHERE p.document=d.id AND p.deleted=0),(SELECT p.result FROM pages p WHERE"
            + " p.document=d.id AND p.status='ready' AND p.deleted=0 ORDER BY position LIMIT"
            + " 1),(SELECT count(*) FROM pages p WHERE p.document=d.id AND p.status IN"
            + " ('queued','processing') AND p.deleted=0) FROM documents d WHERE "
            + filter
            + " ORDER BY d.updated DESC";
    try (Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
      while (c.moveToNext()) {
        Document d = new Document();
        d.id = c.getString(0);
        d.title = c.getString(1);
        d.folder = c.getLong(2);
        d.updated = c.getLong(3);
        d.trashed = c.getInt(4) != 0;
        d.deleted = c.getLong(5);
        d.count = c.getInt(6);
        d.cover = c.getString(7);
        d.pending = c.getInt(8);
        list.add(d);
      }
    }
    return list;
  }

  public Document document(String id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,title,folder,updated,trashed,deleted FROM documents WHERE id=?",
                new String[] {id})) {
      if (!c.moveToFirst()) return null;
      Document d = new Document();
      d.id = c.getString(0);
      d.title = c.getString(1);
      d.folder = c.getLong(2);
      d.updated = c.getLong(3);
      d.trashed = c.getInt(4) != 0;
      d.deleted = c.getLong(5);
      return d;
    }
  }

  public void renameDocument(String id, String name) {
    ContentValues v = new ContentValues();
    v.put("title", title(name));
    v.put("updated", System.currentTimeMillis());
    getWritableDatabase().update("documents", v, "id=?", new String[] {id});
  }

  public void moveDocument(String id, long folder) {
    ContentValues v = new ContentValues();
    v.put("folder", folder);
    v.put("updated", System.currentTimeMillis());
    getWritableDatabase().update("documents", v, "id=?", new String[] {id});
  }

  public void trash(String id, boolean trash) {
    ContentValues v = new ContentValues();
    v.put("trashed", trash ? 1 : 0);
    v.put("deleted", trash ? System.currentTimeMillis() : 0);
    getWritableDatabase().update("documents", v, "id=?", new String[] {id});
  }

  public List<Page> pages(String doc) {
    List<Page> pages = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM pages WHERE document=? AND deleted=0 ORDER BY position,id",
                new String[] {doc})) {
      while (c.moveToNext()) pages.add(readPage(c));
    }
    return pages;
  }

  public Page page(String id) {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT * FROM pages WHERE id=?", new String[] {id})) {
      return c.moveToFirst() ? readPage(c) : null;
    }
  }

  private static Page readPage(Cursor c) {
    Page p = new Page();
    p.id = c.getString(c.getColumnIndexOrThrow("id"));
    p.document = c.getString(c.getColumnIndexOrThrow("document"));
    p.position = c.getInt(c.getColumnIndexOrThrow("position"));
    p.original = c.getString(c.getColumnIndexOrThrow("original"));
    p.result = c.getString(c.getColumnIndexOrThrow("result"));
    p.settings = c.getString(c.getColumnIndexOrThrow("settings"));
    p.status = c.getString(c.getColumnIndexOrThrow("status"));
    p.error = c.getString(c.getColumnIndexOrThrow("error"));
    p.rotation = c.getInt(c.getColumnIndexOrThrow("rotation"));
    p.width = c.getInt(c.getColumnIndexOrThrow("width"));
    p.height = c.getInt(c.getColumnIndexOrThrow("height"));
    p.deleted = c.getLong(c.getColumnIndexOrThrow("deleted"));
    p.colorTag = c.getInt(c.getColumnIndexOrThrow("colorTag"));
    p.created = c.getLong(c.getColumnIndexOrThrow("created"));
    return p;
  }

  public synchronized void setPageOrder(List<String> ids, long folder, String document) {
    if (ids.isEmpty()) return;
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      String target = document != null ? document : page(ids.get(0)).document;
      Set<String> sources = new HashSet<>();
      int index = 0;
      for (String id : ids) {
        Page p = page(id);
        if (p == null) continue;
        if (document != null && !document.equals(p.document))
          throw new IllegalArgumentException("页面不属于文档");
        if (document == null && document(p.document).folder != folder)
          throw new IllegalArgumentException("页面不属于文件夹");
        sources.add(p.document);
        ContentValues values = new ContentValues();
        values.put("document", target);
        values.put("position", index++);
        db.update("pages", values, "id=?", new String[] {id});
      }
      for (String id : sources)
        if (!id.equals(target) && pages(id).isEmpty())
          db.delete("documents", "id=?", new String[] {id});
      touch(target);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public synchronized void movePages(java.util.Collection<String> ids, long folder) {
    if (ids.isEmpty()) return;
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      String target =
          createDocument(
              folder,
              "扫描 "
                  + new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault())
                      .format(new Date()));
      int position = 0;
      Set<String> sources = new HashSet<>();
      for (String id : ids) {
        Page page = page(id);
        if (page == null) continue;
        sources.add(page.document);
        ContentValues values = new ContentValues();
        values.put("document", target);
        values.put("position", position++);
        db.update("pages", values, "id=?", new String[] {id});
      }
      for (String source : sources) {
        if (pages(source).isEmpty()) db.delete("documents", "id=?", new String[] {source});
        else touch(source);
      }
      touch(target);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public synchronized String addPage(String doc, Bitmap bitmap, ScanSettings settings)
      throws IOException {
    File temporary = File.createTempFile("page-", ".jpg", root);
    try {
      saveJpeg(temporary, bitmap, 100);
      try (InputStream in = new FileInputStream(temporary)) {
        return addSource(doc, in, settings);
      }
    } finally {
      temporary.delete();
    }
  }

  /** Keeps capture/import bytes intact; page transforms live in the queued settings. */
  public synchronized String addSource(String doc, InputStream source, ScanSettings settings)
      throws IOException {
    if (document(doc) == null) throw new IOException("目标文档已被删除");
    String id = UUID.randomUUID().toString(),
        original = id + "-original.jpg",
        result = id + "-result.jpg";
    try (OutputStream out = new FileOutputStream(file(original))) {
      Exporter.copy(source, out);
    } catch (IOException | RuntimeException ex) {
      file(original).delete();
      throw ex;
    }
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      int position;
      try (Cursor c =
          db.rawQuery(
              "SELECT coalesce(max(position),-1)+1 FROM pages WHERE document=?",
              new String[] {doc})) {
        c.moveToFirst();
        position = c.getInt(0);
      }
      ContentValues v = new ContentValues();
      v.put("id", id);
      v.put("document", doc);
      v.put("position", position);
      v.put("original", original);
      v.put("result", result);
      v.put("settings", settings.json());
      v.put("status", "queued");
      v.put("created", System.currentTimeMillis());
      db.insertOrThrow("pages", null, v);
      touch(doc);
      db.setTransactionSuccessful();
    } catch (RuntimeException ex) {
      file(original).delete();
      throw ex;
    } finally {
      db.endTransaction();
    }
    return id;
  }

  public synchronized Page nextJob() {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery(
            "SELECT p.* FROM pages p JOIN documents d ON d.id=p.document WHERE p.status='queued'"
                + " AND d.trashed=0 AND p.deleted=0 AND EXISTS(SELECT 1 FROM folders f WHERE"
                + " f.id=d.folder AND f.deleted=0) ORDER BY d.updated,p.position LIMIT 1",
            null)) {
      if (!c.moveToFirst()) {
        db.setTransactionSuccessful();
        return null;
      }
      Page p = readPage(c);
      ContentValues v = new ContentValues();
      v.put("status", "processing");
      db.update("pages", v, "id=?", new String[] {p.id});
      db.setTransactionSuccessful();
      return p;
    } finally {
      db.endTransaction();
    }
  }

  public void recoverQueue() {
    getWritableDatabase().execSQL("UPDATE pages SET status='queued' WHERE status='processing'");
  }

  public void retryErrors(String doc) {
    getWritableDatabase()
        .execSQL(
            "UPDATE pages SET status='queued',error='' WHERE document=? AND status='error'",
            new Object[] {doc});
  }

  public void queue(String id, ScanSettings options, int rotation) {
    ContentValues v = new ContentValues();
    v.put("settings", options.json());
    v.put("rotation", rotation);
    v.put("status", "queued");
    v.put("error", "");
    getWritableDatabase().update("pages", v, "id=?", new String[] {id});
  }

  public synchronized void complete(Page page, Bitmap result, int quality) throws IOException {
    // A user can delete a queued page while the interpreter is finishing it.
    Page current = page(page.id);
    if (current == null
        || current.deleted > 0
        || !current.original.equals(page.original)
        || !current.settings.equals(page.settings)
        || current.rotation != page.rotation) return;
    saveJpeg(file(page.result), result, quality);
    ContentValues v = new ContentValues();
    v.put("status", "ready");
    v.put("error", "");
    v.put("width", result.getWidth());
    v.put("height", result.getHeight());
    getWritableDatabase().update("pages", v, "id=?", new String[] {page.id});
    touch(page.document);
  }

  public synchronized void fail(Page snapshot, String error) {
    Page current = page(snapshot.id);
    if (current == null
        || current.deleted > 0
        || !current.original.equals(snapshot.original)
        || !current.settings.equals(snapshot.settings)
        || current.rotation != snapshot.rotation) return;
    fail(snapshot.id, error);
  }

  public void fail(String id, String error) {
    ContentValues v = new ContentValues();
    v.put("status", "error");
    v.put("error", error);
    getWritableDatabase().update("pages", v, "id=?", new String[] {id});
  }

  public void cancelPending() {
    getWritableDatabase()
        .execSQL(
            "UPDATE pages SET status='error',error='用户取消了处理，可重试' WHERE status IN"
                + " ('queued','processing')");
  }

  public synchronized void deletePage(String id) throws IOException {
    Page p = page(id);
    if (p == null) return;
    getWritableDatabase().delete("pages", "id=?", new String[] {id});
    file(p.original).delete();
    file(p.result).delete();
    touch(p.document);
  }

  public synchronized void permanentlyDelete(String doc) throws IOException {
    List<Page> list = allPages(doc);
    getWritableDatabase().delete("documents", "id=?", new String[] {doc});
    for (Page p : list) {
      file(p.original).delete();
      file(p.result).delete();
    }
  }

  public void reorder(String doc, String pageId, int target) {
    List<Page> list = pages(doc);
    Page moved = null;
    for (Page p : list) if (p.id.equals(pageId)) moved = p;
    if (moved == null) return;
    list.remove(moved);
    list.add(Math.max(0, Math.min(target, list.size())), moved);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (int i = 0; i < list.size(); i++) {
        ContentValues v = new ContentValues();
        v.put("position", i);
        db.update("pages", v, "id=?", new String[] {list.get(i).id});
      }
      touch(doc);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private void touch(String doc) {
    ContentValues v = new ContentValues();
    v.put("updated", System.currentTimeMillis());
    getWritableDatabase().update("documents", v, "id=?", new String[] {doc});
    getWritableDatabase()
        .execSQL(
            "UPDATE folders SET updated=? WHERE id=(SELECT folder FROM documents WHERE id=?)",
            new Object[] {System.currentTimeMillis(), doc});
  }

  public List<Page> allPages(String doc) {
    List<Page> result = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM pages WHERE document=? ORDER BY position,id", new String[] {doc})) {
      while (c.moveToNext()) result.add(readPage(c));
    }
    return result;
  }

  public List<Page> trashPages() {
    List<Page> result = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT p.* FROM pages p JOIN documents d ON p.document=d.id JOIN folders f ON"
                    + " f.id=d.folder WHERE f.deleted=0 AND (p.deleted>0 OR d.trashed=1) ORDER BY"
                    + " CASE WHEN p.deleted>0 THEN p.deleted ELSE d.deleted END DESC",
                null)) {
      while (c.moveToNext()) result.add(readPage(c));
    }
    return result;
  }

  public synchronized void trashPages(Collection<String> ids) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      long now = System.currentTimeMillis();
      for (String id : ids) {
        Page p = page(id);
        if (p == null) continue;
        ContentValues v = new ContentValues();
        v.put("deleted", now);
        db.update("pages", v, "id=?", new String[] {id});
        touch(p.document);
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public synchronized void restorePages(Collection<String> ids) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (String id : ids) {
        Page p = page(id);
        if (p == null) continue;
        Document doc = document(p.document);
        if (doc != null && doc.trashed)
          db.execSQL(
              "UPDATE pages SET deleted=CASE WHEN deleted=0 THEN ? ELSE deleted END WHERE"
                  + " document=?",
              new Object[] {doc.deleted > 0 ? doc.deleted : System.currentTimeMillis(), doc.id});
        ContentValues v = new ContentValues();
        v.put("deleted", 0);
        db.update("pages", v, "id=?", new String[] {id});
        trash(p.document, false);
        touch(p.document);
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void trashFolder(long id, boolean trashed) {
    if (id == 1 && trashed) throw new IllegalArgumentException("您无法删除默认文件夹。");
    ContentValues v = new ContentValues();
    v.put("deleted", trashed ? System.currentTimeMillis() : 0);
    getWritableDatabase().update("folders", v, "id=?", new String[] {"" + id});
  }

  public synchronized void permanentlyDeleteFolder(long id) throws IOException {
    if (id == 1) throw new IllegalArgumentException("您无法删除默认文件夹。");
    List<String> documents = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT id FROM documents WHERE folder=?", new String[] {"" + id})) {
      while (c.moveToNext()) documents.add(c.getString(0));
    }
    for (String doc : documents) permanentlyDelete(doc);
    getWritableDatabase().delete("folders", "id=?", new String[] {"" + id});
  }

  public void setFolderCover(long folder, String pageId) {
    Page p = page(pageId);
    if (p == null || document(p.document).folder != folder)
      throw new IllegalArgumentException("页面不属于文件夹");
    ContentValues v = new ContentValues();
    v.put("cover", pageId);
    getWritableDatabase().update("folders", v, "id=?", new String[] {"" + folder});
  }

  public synchronized void replaceScan(String id, File capture, ScanSettings settings)
      throws IOException {
    Page page = page(id);
    if (page == null || page.deleted > 0) throw new IOException("页面已被删除");
    String suffix = UUID.randomUUID().toString();
    String raw = id + "-" + suffix + "-original.jpg", result = id + "-" + suffix + "-result.jpg";
    File source = file(raw);
    try (InputStream in = new FileInputStream(capture);
        OutputStream out = new FileOutputStream(source)) {
      Exporter.copy(in, out);
    } catch (IOException ex) {
      source.delete();
      throw ex;
    }
    try {
      ContentValues v = new ContentValues();
      v.put("original", raw);
      v.put("result", result);
      v.put("settings", settings.json());
      v.put("status", "queued");
      v.put("error", "");
      v.put("rotation", 0);
      v.put("width", 0);
      v.put("height", 0);
      if (getWritableDatabase().update("pages", v, "id=? AND deleted=0", new String[] {id}) != 1)
        throw new IOException("页面已被删除");
    } catch (IOException | RuntimeException ex) {
      source.delete();
      throw ex;
    }
    file(page.original).delete();
    file(page.result).delete();
    touch(page.document);
  }

  public boolean folderLocked(long id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT secure FROM folders WHERE id=?", new String[] {"" + id})) {
      return c.moveToFirst() && c.getInt(0) != 0;
    }
  }

  public void setFolderLocked(long id, boolean locked) {
    ContentValues v = new ContentValues();
    v.put("secure", locked ? 1 : 0);
    getWritableDatabase().update("folders", v, "id=?", new String[] {"" + id});
  }

  public void clearFolderLocks() {
    getWritableDatabase().execSQL("UPDATE folders SET secure=0");
  }

  public void setColorTag(String id, int color) {
    ContentValues v = new ContentValues();
    v.put("colorTag", color);
    getWritableDatabase().update("pages", v, "id=?", new String[] {id});
  }

  /** Retains ready results while discarding only their separate original files. */
  public synchronized void clearOriginals() throws IOException {
    clearOriginals(null);
  }

  synchronized void clearOriginals(Collection<String> selected) throws IOException {
    SQLiteDatabase db = getWritableDatabase();
    List<String> originals = new ArrayList<>();
    db.beginTransaction();
    try {
      try (Cursor c =
          db.rawQuery(
              "SELECT p.id,p.original,p.result FROM pages p JOIN documents d ON p.document=d.id"
                  + " JOIN folders f ON f.id=d.folder WHERE p.deleted=0 AND d.trashed=0 AND"
                  + " d.deleted=0 AND f.deleted=0 AND p.status='ready' AND p.original!=p.result",
              null)) {
        while (c.moveToNext()) {
          if (selected != null && !selected.contains(c.getString(0))) continue;
          if (!file(c.getString(2)).isFile()) continue;
          ContentValues v = new ContentValues();
          v.put("original", c.getString(2));
          db.update("pages", v, "id=?", new String[] {c.getString(0)});
          originals.add(c.getString(1));
        }
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    for (String name : originals) {
      File original = file(name);
      if (!original.delete() && original.exists()) throw new IOException("无法删除原始图像");
    }
  }

  public synchronized void pruneTrash() throws IOException {
    long before = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
    for (Folder f : folders(true)) if (f.deleted < before) permanentlyDeleteFolder(f.id);
    for (Document d : documents(0, "", true)) {
      try (Cursor c =
          getReadableDatabase()
              .rawQuery("SELECT deleted FROM documents WHERE id=?", new String[] {d.id})) {
        if (c.moveToFirst() && c.getLong(0) > 0 && c.getLong(0) < before) permanentlyDelete(d.id);
      }
    }
    for (Page p : trashPages()) if (p.deleted > 0 && p.deleted < before) deletePage(p.id);
  }

  public static String title(String title) {
    String value = title == null ? "" : title.trim();
    return value.isEmpty() ? "未命名扫描" : value.substring(0, Math.min(value.length(), 100));
  }

  public static void saveJpeg(File file, Bitmap bitmap, int quality) throws IOException {
    AtomicFile atomic = new AtomicFile(file);
    FileOutputStream stream = null;
    try {
      stream = atomic.startWrite();
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream))
        throw new IOException("图像保存失败");
      atomic.finishWrite(stream);
    } catch (IOException | RuntimeException ex) {
      if (stream != null) atomic.failWrite(stream);
      throw ex;
    }
  }
}
