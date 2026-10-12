package dev.myflat.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only, grant-based access to explicitly exported cache files. */
public final class ShareProvider extends ContentProvider {
  public static Uri uri(android.content.Context context, File file) {
    try {
      File root = new File(context.getCacheDir(), "exports").getCanonicalFile();
      String path = file.getCanonicalPath();
      if (!path.startsWith(root.getPath() + File.separator))
        throw new IllegalArgumentException("Invalid export");
      Uri.Builder builder =
          new Uri.Builder().scheme("content").authority(context.getPackageName() + ".files");
      String relative = path.substring(root.getPath().length() + 1);
      for (String part : relative.split(java.util.regex.Pattern.quote(File.separator)))
        builder.appendPath(part);
      return builder.build();
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid export", ex);
    }
  }

  private File resolve(Uri uri) throws FileNotFoundException {
    try {
      File root = new File(getContext().getCacheDir(), "exports").getCanonicalFile();
      java.util.List<String> parts = uri.getPathSegments();
      if (parts.isEmpty() || parts.size() > 2) throw new FileNotFoundException();
      File file = root;
      for (String part : parts) {
        if (part.isEmpty()
            || part.equals(".")
            || part.equals("..")
            || part.indexOf('/') >= 0
            || part.indexOf('\\') >= 0) throw new FileNotFoundException();
        file = new File(file, part);
      }
      file = file.getCanonicalFile();
      if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile())
        throw new FileNotFoundException();
      return file;
    } catch (IOException ex) {
      throw new FileNotFoundException("Export no longer available");
    }
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read-only export");
    return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public String getType(Uri uri) {
    String name = uri.getLastPathSegment();
    return name != null && name.endsWith(".pdf")
        ? "application/pdf"
        : name != null && name.endsWith(".zip") ? "application/zip" : "image/jpeg";
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      File file = resolve(uri);
      String[] cols =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor cursor = new MatrixCursor(cols);
      Object[] row = new Object[cols.length];
      for (int i = 0; i < cols.length; i++)
        row[i] =
            OpenableColumns.DISPLAY_NAME.equals(cols[i])
                ? file.getName()
                : OpenableColumns.SIZE.equals(cols[i]) ? file.length() : null;
      cursor.addRow(row);
      return cursor;
    } catch (FileNotFoundException ex) {
      return null;
    }
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
