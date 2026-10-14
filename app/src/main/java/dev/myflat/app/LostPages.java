package dev.myflat.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;

/** Finds valid image files that no longer have a page record. */
final class LostPages {
  static final class Preview {
    final int pages;
    final List<File[]> files;

    Preview(List<File[]> files) {
      this.files = Collections.unmodifiableList(files);
      pages = files.size();
    }
  }

  static Preview inspect(Context context) throws IOException {
    DocumentStore store = MyFlatApp.of(context).store;
    synchronized (store) {
      Set<String> known = new HashSet<>();
      try (Cursor c =
          store.getReadableDatabase().rawQuery("SELECT original,result FROM pages", null)) {
        while (c.moveToNext()) {
          known.add(c.getString(0));
          known.add(c.getString(1));
        }
      }
      File root = new File(context.getFilesDir(), "pages").getCanonicalFile();
      File[] files = root.listFiles();
      List<File[]> found = new ArrayList<>();
      Set<String> handled = new HashSet<>();
      if (files == null) return new Preview(found);
      Arrays.sort(files, Comparator.comparing(File::getName));
      for (File file : files) {
        String name = file.getName();
        if (known.contains(name)
            || handled.contains(name)
            || !file.isFile()
            || !name.matches("[a-zA-Z0-9_-]+\\.(jpg|jpeg|png|webp)")
            || !file.getCanonicalFile().getParentFile().equals(root)) continue;
        try (InputStream in = new FileInputStream(file)) {
          Backup.imageBounds(in);
        } catch (IOException ex) {
          continue;
        }
        File original = file, result = file;
        if (name.endsWith("-original.jpg")) {
          File pair = new File(root, name.replace("-original.jpg", "-result.jpg"));
          if (pair.isFile() && !known.contains(pair.getName()) && valid(pair)) result = pair;
        } else if (name.endsWith("-result.jpg")) {
          File pair = new File(root, name.replace("-result.jpg", "-original.jpg"));
          if (pair.isFile() && !known.contains(pair.getName()) && valid(pair)) original = pair;
        }
        handled.add(original.getName());
        handled.add(result.getName());
        found.add(new File[] {original, result});
      }
      return new Preview(found);
    }
  }

  private static boolean valid(File file) {
    try (InputStream in = new FileInputStream(file)) {
      Backup.imageBounds(in);
      return true;
    } catch (IOException ex) {
      return false;
    }
  }

  static int restore(Context context, Preview preview) throws IOException {
    DocumentStore store = MyFlatApp.of(context).store;
    synchronized (store) {
      SQLiteDatabase db = store.getWritableDatabase();
      Set<String> known = new HashSet<>();
      try (Cursor c = db.rawQuery("SELECT original,result FROM pages", null)) {
        while (c.moveToNext()) {
          known.add(c.getString(0));
          known.add(c.getString(1));
        }
      }
      List<File> created = new ArrayList<>();
      int recovered = 0;
      db.beginTransaction();
      try {
        String document = null;
        for (File[] pair : preview.files) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("恢复已取消");
          if (known.contains(pair[0].getName()) || known.contains(pair[1].getName())) continue;
          File original = store.file(pair[0].getName()), result = store.file(pair[1].getName());
          if (!original.equals(pair[0].getCanonicalFile())
              || !result.equals(pair[1].getCanonicalFile())
              || !valid(original)
              || !valid(result)) continue;
          if (document == null) document = store.createDocument(1, "恢复的页面");
          String id = UUID.randomUUID().toString();
          if (original.equals(result)) {
            result = store.file(id + "-result.jpg");
            created.add(result);
            try (InputStream in = new FileInputStream(original);
                OutputStream out = new FileOutputStream(result)) {
              Exporter.copy(in, out);
            }
          }
          int[] bounds;
          try (InputStream in = new FileInputStream(result)) {
            bounds = Backup.imageBounds(in);
          }
          ContentValues values = new ContentValues();
          values.put("id", id);
          values.put("document", document);
          values.put("position", recovered);
          values.put("original", original.getName());
          values.put("result", result.getName());
          values.put("settings", ScanSettings.defaults(context).json());
          values.put("status", "ready");
          values.put("width", bounds[0]);
          values.put("height", bounds[1]);
          values.put("created", original.lastModified());
          db.insertOrThrow("pages", null, values);
          known.add(original.getName());
          known.add(result.getName());
          recovered++;
        }
        db.setTransactionSuccessful();
        return recovered;
      } catch (RuntimeException | IOException ex) {
        for (File file : created) file.delete();
        if (ex instanceof IOException) throw (IOException) ex;
        throw new IOException("无法恢复页面", ex);
      } finally {
        db.endTransaction();
      }
    }
  }
}
