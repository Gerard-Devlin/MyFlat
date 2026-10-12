package dev.myflat.app;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Portable local backups merge restored documents without replacing existing user data. */
final class Backup {
  static void write(Context context, OutputStream output) throws IOException {
    DocumentStore store = MyFlatApp.of(context).store;
    synchronized (store) {
      try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
        JSONObject manifest = new JSONObject().put("version", 1);
        for (String table : new String[] {"folders", "documents", "pages"}) {
          JSONArray rows = new JSONArray();
          try (Cursor cursor =
              store.getReadableDatabase().query(table, null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
              JSONObject row = new JSONObject();
              for (int col = 0; col < cursor.getColumnCount(); col++)
                row.put(
                    cursor.getColumnName(col),
                    cursor.getType(col) == Cursor.FIELD_TYPE_INTEGER
                        ? cursor.getLong(col)
                        : cursor.isNull(col) ? JSONObject.NULL : cursor.getString(col));
              rows.put(row);
            }
          }
          manifest.put(table, rows);
        }
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
        JSONArray pages = manifest.getJSONArray("pages");
        Set<String> added = new HashSet<>();
        for (int i = 0; i < pages.length(); i++)
          for (String key : new String[] {"original", "result"}) {
            File file = store.file(pages.getJSONObject(i).getString(key));
            if (!file.isFile() || !added.add(file.getName())) continue;
            zip.putNextEntry(new ZipEntry("pages/" + file.getName()));
            try (InputStream in = new FileInputStream(file)) {
              Exporter.copy(in, zip);
            }
            zip.closeEntry();
          }
      } catch (JSONException ex) {
        throw new IOException("无法创建备份", ex);
      }
    }
  }

  static void restore(Context context, InputStream input) throws IOException {
    DocumentStore store = MyFlatApp.of(context).store;
    File archive = File.createTempFile("restore-", ".zip", context.getCacheDir());
    List<File> created = new ArrayList<>();
    try {
      try (OutputStream out = new FileOutputStream(archive)) {
        Exporter.copy(input, out);
      }
      try (ZipFile zip = new ZipFile(archive)) {
        ZipEntry meta = zip.getEntry("manifest.json");
        if (meta == null || meta.getSize() > 20_000_000) throw new IOException("备份文件无效");
        JSONObject manifest;
        try (InputStream in = zip.getInputStream(meta)) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          byte[] buffer = new byte[8192];
          int n;
          while ((n = in.read(buffer)) != -1) {
            if (bytes.size() + n > 20_000_000) throw new IOException("备份文件无效");
            bytes.write(buffer, 0, n);
          }
          manifest = new JSONObject(bytes.toString("UTF-8"));
        }
        if (manifest.getInt("version") != 1) throw new IOException("不支持的备份版本");
        synchronized (store) {
          SQLiteDatabase db = store.getWritableDatabase();
          db.beginTransaction();
          boolean complete = false;
          try {
            Map<String, String> folders = new HashMap<>(), documents = new HashMap<>();
            JSONArray rows = manifest.getJSONArray("folders");
            for (int i = 0; i < rows.length(); i++) {
              JSONObject row = rows.getJSONObject(i);
              long id = store.createFolder(row.getString("name"));
              folders.put(row.getString("id"), Long.toString(id));
            }
            rows = manifest.getJSONArray("documents");
            for (int i = 0; i < rows.length(); i++) {
              JSONObject row = rows.getJSONObject(i);
              String destination = folders.get(row.getString("folder"));
              if (destination == null) throw new IOException("备份文件夹缺失");
              String id = store.createDocument(Long.parseLong(destination), row.getString("title"));
              documents.put(row.getString("id"), id);
              if (row.optInt("trashed") == 1) store.trash(id, true);
            }
            rows = manifest.getJSONArray("pages");
            if (rows.length() > 100000) throw new IOException("备份页数过多");
            for (int i = 0; i < rows.length(); i++) {
              JSONObject row = rows.getJSONObject(i);
              String doc = documents.get(row.getString("document"));
              if (doc == null) throw new IOException("备份文档缺失");
              String oldOriginal = row.getString("original");
              if (!oldOriginal.matches("[a-zA-Z0-9-]+\\.jpg")) throw new IOException("备份文件名无效");
              ZipEntry original = zip.getEntry("pages/" + oldOriginal);
              if (original == null) throw new IOException("备份原始照片缺失");
              String id;
              try (InputStream in = zip.getInputStream(original)) {
                id = store.addSource(doc, in, ScanSettings.parse(row.getString("settings")));
              }
              DocumentStore.Page page = store.page(id);
              created.add(store.file(page.original));
              ContentValues values = new ContentValues();
              values.put("position", row.getInt("position"));
              values.put("rotation", row.optInt("rotation"));
              String oldResult = row.getString("result");
              ZipEntry result =
                  oldResult.matches("[a-zA-Z0-9-]+\\.jpg")
                      ? zip.getEntry("pages/" + oldResult)
                      : null;
              if (result != null && "ready".equals(row.optString("status"))) {
                File file = store.file(page.result);
                created.add(file);
                try (InputStream in = zip.getInputStream(result);
                    OutputStream out = new FileOutputStream(file)) {
                  Exporter.copy(in, out);
                }
                values.put("status", "ready");
                values.put("width", row.getInt("width"));
                values.put("height", row.getInt("height"));
              }
              db.update("pages", values, "id=?", new String[] {id});
            }
            db.setTransactionSuccessful();
            complete = true;
          } finally {
            db.endTransaction();
            if (!complete) for (File file : created) file.delete();
          }
        }
      }
    } catch (JSONException | RuntimeException ex) {
      throw new IOException("备份文件无效", ex);
    } finally {
      archive.delete();
    }
  }
}
