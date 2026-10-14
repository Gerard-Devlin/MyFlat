package dev.myflat.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Portable local backups merge restored documents without replacing existing user data. */
final class Backup {
  private static final int MAX_MANIFEST_BYTES = 20_000_000;
  private static final int MAX_PAGES = 100_000;
  private static final long MAX_IMAGE_BYTES = 1_000_000_000L;

  static final class Preview {
    final int folders, documents, pages;
    final long bytes;
    final List<String> folderNames;

    Preview(JSONObject manifest, long bytes) throws JSONException {
      JSONArray folderRows = manifest.getJSONArray("folders");
      folders = folderRows.length();
      documents = manifest.getJSONArray("documents").length();
      pages = manifest.getJSONArray("pages").length();
      this.bytes = bytes;
      List<String> names = new ArrayList<>();
      for (int i = 0; i < folderRows.length(); i++)
        names.add(folderRows.getJSONObject(i).getString("name"));
      folderNames = Collections.unmodifiableList(names);
    }
  }

  private static final class Snapshot {
    final JSONObject manifest;
    final Map<String, File> files;
    final Preview preview;

    Snapshot(JSONObject manifest, Map<String, File> files) throws JSONException {
      this.manifest = manifest;
      this.files = files;
      long bytes = manifest.toString().getBytes(StandardCharsets.UTF_8).length;
      for (File file : files.values()) bytes = Math.addExact(bytes, file.length());
      preview = new Preview(manifest, bytes);
    }
  }

  /** The default backup excludes locked folders as well as every kind of deleted item. */
  static void write(Context context, OutputStream output) throws IOException {
    write(context, output, false);
  }

  static void write(Context context, OutputStream output, boolean includeSecure)
      throws IOException {
    DocumentStore store = MyFlatApp.of(context).store;
    synchronized (store) {
      Snapshot snapshot = snapshot(store, includeSecure);
      try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(snapshot.manifest.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        for (Map.Entry<String, File> file : snapshot.files.entrySet()) {
          checkInterrupted();
          zip.putNextEntry(new ZipEntry("pages/" + file.getKey()));
          try (InputStream in = new FileInputStream(file.getValue())) {
            Exporter.copy(in, zip);
          }
          zip.closeEntry();
        }
      }
    }
  }

  /** Counts the same validated, accessible files that will be included in a backup. */
  static Preview inspect(Context context, boolean includeSecure) throws IOException {
    DocumentStore store = MyFlatApp.of(context).store;
    synchronized (store) {
      return snapshot(store, includeSecure).preview;
    }
  }

  /** Reads and validates the selected archive before presenting a restore confirmation. */
  static Preview inspect(Context context, Uri uri) throws IOException {
    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
      if (input == null) throw new IOException("无法打开备份文件");
      try (Archive archive = Archive.open(context, input)) {
        return archive.preview;
      }
    }
  }

  private static Snapshot snapshot(DocumentStore store, boolean includeSecure) throws IOException {
    try {
      SQLiteDatabase db = store.getReadableDatabase();
      String folderFilter = "deleted=0" + (includeSecure ? "" : " AND secure=0");
      String documentFilter =
          "trashed=0 AND deleted=0 AND folder IN (SELECT id FROM folders WHERE "
              + folderFilter
              + ")";
      JSONObject manifest =
          new JSONObject()
              .put("version", 1)
              .put("created", System.currentTimeMillis())
              .put("folders", rows(db, "folders", folderFilter, "id"))
              .put("documents", rows(db, "documents", documentFilter, "updated,id"))
              .put(
                  "pages",
                  rows(
                      db,
                      "pages",
                      "deleted=0 AND document IN (SELECT id FROM documents WHERE "
                          + documentFilter
                          + ")",
                      "document,position,id"));
      // Folder protection belongs to this device. A portable backup always restores unlocked.
      JSONArray folders = manifest.getJSONArray("folders");
      for (int i = 0; i < folders.length(); i++)
        folders.getJSONObject(i).put("secure", 0).put("deleted", 0);
      Map<String, File> files = new LinkedHashMap<>();
      JSONArray pages = manifest.getJSONArray("pages");
      for (int i = 0; i < pages.length(); i++) {
        JSONObject page = pages.getJSONObject(i);
        String original = filename(page.getString("original"));
        addFile(store, files, original);
        if ("ready".equals(page.getString("status")))
          addFile(store, files, filename(page.getString("result")));
      }
      return new Snapshot(manifest, files);
    } catch (JSONException | RuntimeException ex) {
      throw new IOException("无法分析备份数据", ex);
    }
  }

  private static JSONArray rows(SQLiteDatabase db, String table, String where, String order)
      throws JSONException {
    JSONArray rows = new JSONArray();
    try (Cursor cursor = db.query(table, null, where, null, null, null, order)) {
      while (cursor.moveToNext()) {
        JSONObject row = new JSONObject();
        for (int col = 0; col < cursor.getColumnCount(); col++) {
          Object value;
          if (cursor.isNull(col)) value = JSONObject.NULL;
          else if (cursor.getType(col) == Cursor.FIELD_TYPE_INTEGER) value = cursor.getLong(col);
          else if (cursor.getType(col) == Cursor.FIELD_TYPE_FLOAT) value = cursor.getDouble(col);
          else value = cursor.getString(col);
          row.put(cursor.getColumnName(col), value);
        }
        rows.put(row);
      }
    }
    return rows;
  }

  private static void addFile(DocumentStore store, Map<String, File> files, String name)
      throws IOException {
    if (files.containsKey(name)) return;
    checkInterrupted();
    File file = store.file(name);
    if (!file.isFile() || file.length() == 0 || file.length() > MAX_IMAGE_BYTES)
      throw new IOException("备份照片缺失或损坏");
    try (InputStream in = new FileInputStream(file)) {
      imageBounds(in);
    }
    files.put(name, file);
  }

  static void restore(Context context, InputStream input) throws IOException {
    DocumentStore store = MyFlatApp.of(context).store;
    try (Archive archive = Archive.open(context, input)) {
      if (archive.imageBytes > context.getFilesDir().getUsableSpace())
        throw new IOException("存储空间不足，无法恢复备份");
      synchronized (store) {
        SQLiteDatabase db = store.getWritableDatabase();
        List<File> created = new ArrayList<>();
        db.beginTransaction();
        boolean complete = false;
        try {
          Map<String, Long> folders = new HashMap<>();
          Map<String, String> documents = new HashMap<>();
          JSONArray rows = archive.manifest.getJSONArray("folders");
          for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            long id = store.createFolder(row.getString("name"));
            folders.put(key(row, "id"), id);
            ContentValues values = new ContentValues();
            values.put("created", row.optLong("created", System.currentTimeMillis()));
            values.put("updated", row.optLong("updated", System.currentTimeMillis()));
            values.put("secure", 0);
            values.put("deleted", 0);
            db.update("folders", values, "id=?", new String[] {Long.toString(id)});
          }
          rows = archive.manifest.getJSONArray("documents");
          for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            long destination = folders.get(key(row, "folder"));
            String id = store.createDocument(destination, row.getString("title"));
            documents.put(key(row, "id"), id);
          }
          rows = archive.manifest.getJSONArray("pages");
          for (int i = 0; i < rows.length(); i++) {
            checkInterrupted();
            JSONObject row = rows.getJSONObject(i);
            String document = documents.get(key(row, "document"));
            String id;
            try (InputStream in =
                archive.zip.getInputStream(
                    archive.zip.getEntry("pages/" + row.getString("original")))) {
              id = store.addSource(document, in, ScanSettings.parse(row.getString("settings")));
            }
            DocumentStore.Page page = store.page(id);
            created.add(store.file(page.original));
            ContentValues values = new ContentValues();
            values.put("position", row.getInt("position"));
            values.put("rotation", row.optInt("rotation"));
            values.put("colorTag", row.optInt("colorTag"));
            values.put("deleted", 0);
            if ("ready".equals(row.getString("status"))) {
              String resultName = row.getString("result");
              File file = store.file(page.result);
              created.add(file);
              try (InputStream in =
                      archive.zip.getInputStream(archive.zip.getEntry("pages/" + resultName));
                  OutputStream out = new FileOutputStream(file)) {
                Exporter.copy(in, out);
              }
              int[] dimensions = archive.dimensions.get(resultName);
              values.put("status", "ready");
              values.put("width", dimensions[0]);
              values.put("height", dimensions[1]);
            }
            db.update("pages", values, "id=?", new String[] {id});
          }
          // addSource touches document dates. Preserve archive dates only after its pages are
          // merged.
          rows = archive.manifest.getJSONArray("documents");
          for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            ContentValues values = new ContentValues();
            values.put("updated", row.optLong("updated", System.currentTimeMillis()));
            db.update("documents", values, "id=?", new String[] {documents.get(key(row, "id"))});
          }
          rows = archive.manifest.getJSONArray("folders");
          for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            ContentValues values = new ContentValues();
            values.put("updated", row.optLong("updated", System.currentTimeMillis()));
            db.update(
                "folders",
                values,
                "id=?",
                new String[] {Long.toString(folders.get(key(row, "id")))});
          }
          db.setTransactionSuccessful();
          complete = true;
        } finally {
          try {
            db.endTransaction();
          } catch (RuntimeException ex) {
            complete = false;
            throw ex;
          } finally {
            if (!complete) for (File file : created) file.delete();
          }
        }
      }
    } catch (JSONException | RuntimeException ex) {
      throw new IOException("备份文件无效", ex);
    }
  }

  private static final class Archive implements Closeable {
    final File file;
    final ZipFile zip;
    final JSONObject manifest;
    final Map<String, int[]> dimensions;
    final long imageBytes;
    final Preview preview;

    Archive(
        File file, ZipFile zip, JSONObject manifest, Map<String, int[]> dimensions, long imageBytes)
        throws JSONException {
      this.file = file;
      this.zip = zip;
      this.manifest = manifest;
      this.dimensions = dimensions;
      this.imageBytes = imageBytes;
      preview = new Preview(manifest, file.length());
    }

    static Archive open(Context context, InputStream input) throws IOException {
      File file = File.createTempFile("restore-", ".zip", context.getCacheDir());
      ZipFile zip = null;
      boolean ready = false;
      try {
        try (OutputStream out = new FileOutputStream(file)) {
          copyChecked(input, out, Long.MAX_VALUE);
        }
        zip = new ZipFile(file);
        validateEntries(zip);
        ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_MANIFEST_BYTES)
          throw new IOException("备份文件无效");
        JSONObject manifest;
        try (InputStream in = zip.getInputStream(entry)) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          copyChecked(in, bytes, MAX_MANIFEST_BYTES);
          byte[] data = bytes.toByteArray();
          verifyCrc(entry, data);
          manifest = new JSONObject(new String(data, StandardCharsets.UTF_8));
        }
        if (manifest.getInt("version") != 1) throw new IOException("不支持的备份版本");
        validateManifest(manifest);
        manifest = withoutTrash(manifest);
        Map<String, int[]> dimensions = new HashMap<>();
        long imageBytes = 0;
        JSONArray pages = manifest.getJSONArray("pages");
        for (int i = 0; i < pages.length(); i++) {
          JSONObject page = pages.getJSONObject(i);
          for (String field : new String[] {"original", "result"}) {
            if (field.equals("result") && !"ready".equals(page.getString("status"))) continue;
            String name = filename(page.getString(field));
            if (dimensions.containsKey(name)) continue;
            ZipEntry image = zip.getEntry("pages/" + name);
            if (image == null || image.isDirectory() || image.getSize() > MAX_IMAGE_BYTES)
              throw new IOException("备份照片缺失或损坏");
            try (InputStream in = zip.getInputStream(image)) {
              dimensions.put(name, imageBounds(in));
            }
            CRC32 crc = new CRC32();
            long bytes = 0;
            try (InputStream in = zip.getInputStream(image)) {
              byte[] buffer = new byte[32_768];
              int count;
              while ((count = in.read(buffer)) != -1) {
                checkInterrupted();
                bytes += count;
                if (bytes > MAX_IMAGE_BYTES) throw new IOException("备份照片过大");
                crc.update(buffer, 0, count);
              }
            }
            if (bytes == 0 || bytes != image.getSize() || crc.getValue() != image.getCrc())
              throw new IOException("备份照片损坏");
            imageBytes = Math.addExact(imageBytes, bytes);
          }
        }
        Archive archive = new Archive(file, zip, manifest, dimensions, imageBytes);
        ready = true;
        return archive;
      } catch (JSONException | RuntimeException ex) {
        throw new IOException("备份文件无效", ex);
      } finally {
        if (!ready) {
          if (zip != null) zip.close();
          file.delete();
        }
      }
    }

    @Override
    public void close() throws IOException {
      try {
        zip.close();
      } finally {
        file.delete();
      }
    }
  }

  private static void validateEntries(ZipFile zip) throws IOException {
    Set<String> names = new HashSet<>();
    Enumeration<? extends ZipEntry> entries = zip.entries();
    while (entries.hasMoreElements()) {
      checkInterrupted();
      ZipEntry entry = entries.nextElement();
      String name = entry.getName();
      if (!names.add(name) || names.size() > MAX_PAGES * 2 + 2) throw new IOException("备份目录无效");
      // Never extract arbitrary ZIP paths, even when the manifest does not reference them.
      if ("manifest.json".equals(name)) continue;
      if ("pages/".equals(name) && entry.isDirectory()) continue;
      if (!name.startsWith("pages/") || entry.isDirectory()) throw new IOException("备份文件路径无效");
      filename(name.substring(6));
    }
  }

  private static void validateManifest(JSONObject manifest) throws IOException, JSONException {
    JSONArray folders = manifest.getJSONArray("folders");
    JSONArray documents = manifest.getJSONArray("documents");
    JSONArray pages = manifest.getJSONArray("pages");
    if (folders.length() > 10_000 || documents.length() > MAX_PAGES || pages.length() > MAX_PAGES)
      throw new IOException("备份数据过多");
    Set<String> folderIds = new HashSet<>(),
        documentIds = new HashSet<>(),
        pageIds = new HashSet<>();
    for (int i = 0; i < folders.length(); i++) {
      JSONObject row = folders.getJSONObject(i);
      if (!folderIds.add(key(row, "id"))
          || row.getString("name").length() > 100
          || row.optLong("deleted") < 0) throw new IOException("备份文件夹无效");
    }
    for (int i = 0; i < documents.length(); i++) {
      JSONObject row = documents.getJSONObject(i);
      if (!documentIds.add(key(row, "id"))
          || !folderIds.contains(key(row, "folder"))
          || row.getString("title").length() > 100
          || row.optLong("deleted") < 0) throw new IOException("备份文档无效");
    }
    for (int i = 0; i < pages.length(); i++) {
      JSONObject row = pages.getJSONObject(i);
      if (!pageIds.add(key(row, "id"))
          || !documentIds.contains(key(row, "document"))
          || row.getInt("position") < 0
          || row.optLong("deleted") < 0) throw new IOException("备份页面无效");
      filename(row.getString("original"));
      filename(row.getString("result"));
      new JSONObject(row.getString("settings"));
      String status = row.getString("status");
      if (!Arrays.asList("ready", "queued", "processing", "error").contains(status))
        throw new IOException("备份页面状态无效");
      int rotation = row.optInt("rotation");
      if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)
        throw new IOException("备份页面方向无效");
    }
  }

  /** Older backups may include trash; it must never reappear as an active restored page. */
  private static JSONObject withoutTrash(JSONObject manifest) throws JSONException, IOException {
    JSONArray folders = new JSONArray(), documents = new JSONArray(), pages = new JSONArray();
    Set<String> folderIds = new HashSet<>(), documentIds = new HashSet<>();
    JSONArray rows = manifest.getJSONArray("folders");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.optLong("deleted") != 0) continue;
      folders.put(row);
      folderIds.add(key(row, "id"));
    }
    rows = manifest.getJSONArray("documents");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.optLong("deleted") != 0
          || row.optInt("trashed") != 0
          || !folderIds.contains(key(row, "folder"))) continue;
      documents.put(row);
      documentIds.add(key(row, "id"));
    }
    rows = manifest.getJSONArray("pages");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.optLong("deleted") != 0 || !documentIds.contains(key(row, "document"))) continue;
      pages.put(row);
    }
    return new JSONObject(manifest.toString())
        .put("folders", folders)
        .put("documents", documents)
        .put("pages", pages);
  }

  private static String key(JSONObject row, String field) throws IOException, JSONException {
    String key = row.getString(field);
    if (key.isEmpty() || key.length() > 200) throw new IOException("备份记录无效");
    return key;
  }

  private static String filename(String name) throws IOException {
    if (!name.matches("[a-zA-Z0-9_-]+\\.(jpg|jpeg|png|webp)")) throw new IOException("备份文件名无效");
    return name;
  }

  static int[] imageBounds(InputStream input) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeStream(input, null, options);
    if (options.outWidth < 1
        || options.outHeight < 1
        || options.outMimeType == null
        || !options.outMimeType.startsWith("image/")
        || (long) options.outWidth * options.outHeight > 1_000_000_000L)
      throw new IOException("备份照片无效");
    return new int[] {options.outWidth, options.outHeight};
  }

  private static void verifyCrc(ZipEntry entry, byte[] bytes) throws IOException {
    CRC32 crc = new CRC32();
    crc.update(bytes);
    if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc())
      throw new IOException("备份文件已损坏");
  }

  private static void copyChecked(InputStream input, OutputStream output, long limit)
      throws IOException {
    byte[] buffer = new byte[32_768];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      checkInterrupted();
      total += count;
      if (total > limit) throw new IOException("备份文件过大");
      output.write(buffer, 0, count);
    }
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("操作已取消");
  }
}
