package dev.myflat.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;

final class Gallery {
  static void save(Context c, File file) throws IOException {
    if (Build.VERSION.SDK_INT < 29
        && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) return;
    ContentValues values = new ContentValues();
    values.put(
        MediaStore.Images.Media.DISPLAY_NAME, "MyFlat-" + System.currentTimeMillis() + ".jpg");
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    if (Build.VERSION.SDK_INT >= 29) {
      values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MyFlat");
      values.put(MediaStore.Images.Media.IS_PENDING, 1);
    }
    Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    if (uri == null) throw new IOException("无法保存到相册");
    try (InputStream in = new FileInputStream(file);
        OutputStream out = c.getContentResolver().openOutputStream(uri)) {
      if (out == null) throw new IOException("无法打开相册图片");
      Exporter.copy(in, out);
    } catch (IOException | RuntimeException ex) {
      c.getContentResolver().delete(uri, null, null);
      throw ex;
    }
    if (Build.VERSION.SDK_INT >= 29) {
      values.clear();
      values.put(MediaStore.Images.Media.IS_PENDING, 0);
      c.getContentResolver().update(uri, values, null, null);
    }
  }
}
