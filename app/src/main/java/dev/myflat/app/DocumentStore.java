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
    public long folder, updated;
    public int count, pending;
    public boolean trashed;
  }

  public static final class Page {
    public String id, document, original, result, settings, status, error;
    public int position, rotation, width, height;
  }

  private final File root;

  public DocumentStore(Context context) {
    super(context, "myflat.db", null, 2);
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
    db.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)");
    db.execSQL("INSERT INTO folders(id,name) VALUES(1,'默认文件夹')");
    db.execSQL(
        "CREATE TABLE documents(id TEXT PRIMARY KEY,title TEXT NOT NULL,folder INTEGER NOT NULL"
            + " REFERENCES folders(id),updated INTEGER NOT NULL,trashed INTEGER NOT NULL DEFAULT"
            + " 0)");
    db.execSQL(
        "CREATE TABLE pages(id TEXT PRIMARY KEY,document TEXT NOT NULL REFERENCES documents(id) ON"
            + " DELETE CASCADE,position INTEGER NOT NULL,original TEXT NOT NULL,result TEXT NOT"
            + " NULL,settings TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL DEFAULT"
            + " '',rotation INTEGER NOT NULL DEFAULT 0,width INTEGER NOT NULL DEFAULT 0,height"
            + " INTEGER NOT NULL DEFAULT 0)");
    db.execSQL("CREATE INDEX pages_document ON pages(document,position)");
    db.execSQL("CREATE INDEX pages_status ON pages(status)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) db.execSQL("UPDATE folders SET name='默认文件夹' WHERE id=1 AND name='收件箱'");
  }

  public File file(String relative) throws IOException {
    File file = new File(root, relative).getCanonicalFile();
    if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator))
      throw new IOException("Invalid document path");
    return file;
  }

  public synchronized List<Folder> folders() {
    List<Folder> list = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT id,name FROM folders ORDER BY id", null)) {
      while (c.moveToNext()) list.add(new Folder(c.getLong(0), c.getString(1)));
    }
    return list;
  }

  public long createFolder(String name) {
    ContentValues values = new ContentValues();
    values.put("name", title(name));
    return getWritableDatabase().insertOrThrow("folders", null, values);
  }

  public void renameFolder(long id, String name) {
    ContentValues v = new ContentValues();
    v.put("name", title(name));
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
    String filter = "d.trashed=?";
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
        "SELECT d.id,d.title,d.folder,d.updated,d.trashed,(SELECT count(*) FROM pages p WHERE"
            + " p.document=d.id),(SELECT p.result FROM pages p WHERE p.document=d.id AND"
            + " p.status='ready' ORDER BY position LIMIT 1),(SELECT count(*) FROM pages p WHERE"
            + " p.document=d.id AND p.status IN ('queued','processing')) FROM documents d WHERE "
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
        d.count = c.getInt(5);
        d.cover = c.getString(6);
        d.pending = c.getInt(7);
        list.add(d);
      }
    }
    return list;
  }

  public Document document(String id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,title,folder,updated,trashed FROM documents WHERE id=?",
                new String[] {id})) {
      if (!c.moveToFirst()) return null;
      Document d = new Document();
      d.id = c.getString(0);
      d.title = c.getString(1);
      d.folder = c.getLong(2);
      d.updated = c.getLong(3);
      d.trashed = c.getInt(4) != 0;
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
    getWritableDatabase().update("documents", v, "id=?", new String[] {id});
  }

  public List<Page> pages(String doc) {
    List<Page> pages = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM pages WHERE document=? ORDER BY position,id", new String[] {doc})) {
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
    return p;
  }

  public synchronized String addPage(String doc, Bitmap bitmap, ScanSettings settings)
      throws IOException {
    if (document(doc) == null) throw new IOException("目标文档已被删除");
    String id = UUID.randomUUID().toString(),
        original = id + "-original.jpg",
        result = id + "-result.jpg";
    saveJpeg(file(original), bitmap, 98);
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
                + " AND d.trashed=0 ORDER BY d.updated,p.position LIMIT 1",
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
    if (page(page.id) == null) return;
    saveJpeg(file(page.result), result, quality);
    ContentValues v = new ContentValues();
    v.put("status", "ready");
    v.put("error", "");
    v.put("width", result.getWidth());
    v.put("height", result.getHeight());
    getWritableDatabase().update("pages", v, "id=?", new String[] {page.id});
    touch(page.document);
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
    List<Page> list = pages(doc);
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
