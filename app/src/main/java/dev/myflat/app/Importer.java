package dev.myflat.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.List;

/** SAF imports never require broad storage access. */
public final class Importer {
  public interface Progress {
    void update(String message);
  }

  private Importer() {}

  public static int importUris(
      Context c, String document, List<Uri> uris, ScanSettings options, Progress progress)
      throws IOException {
    DocumentStore store = MyFlatApp.of(c).store;
    int count = 0;
    for (Uri uri : uris) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已取消");
      String type = c.getContentResolver().getType(uri);
      if ("application/pdf".equals(type)) {
        try (ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(uri, "r")) {
          if (fd == null) throw new IOException("无法打开 PDF");
          try (PdfRenderer renderer = new PdfRenderer(fd)) {
            if (renderer.getPageCount() > 300) throw new IOException("单次最多导入 300 页，请分批导入");
            for (int i = 0; i < renderer.getPageCount(); i++) {
              if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已取消");
              try (PdfRenderer.Page page = renderer.openPage(i)) {
                float scale = Math.min(3f, 2400f / Math.max(page.getWidth(), page.getHeight()));
                Bitmap bitmap =
                    Bitmap.createBitmap(
                        Math.max(1, Math.round(page.getWidth() * scale)),
                        Math.max(1, Math.round(page.getHeight() * scale)),
                        Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                count += add(c, store, document, bitmap, options);
                bitmap.recycle();
                progress.update("已导入 PDF 第 " + (i + 1) + " / " + renderer.getPageCount() + " 页");
              }
            }
          }
        }
      } else {
        Bitmap bitmap = Images.decode(c, uri, 4096);
        count += add(c, store, document, bitmap, options);
        bitmap.recycle();
        progress.update("已导入 " + count + " 页");
      }
    }
    return count;
  }

  public static int add(
      Context c, DocumentStore store, String document, Bitmap bitmap, ScanSettings options)
      throws IOException {
    if (!options.doublePage) {
      store.addPage(document, bitmap, options);
      MyFlatApp.of(c).startProcessing();
      return 1;
    }
    int center = Math.round(bitmap.getWidth() * options.split),
        overlap = Math.max(1, bitmap.getWidth() / 60);
    Bitmap left =
        Bitmap.createBitmap(
            bitmap, 0, 0, Math.min(bitmap.getWidth(), center + overlap), bitmap.getHeight());
    Bitmap right =
        Bitmap.createBitmap(
            bitmap,
            Math.max(0, center - overlap),
            0,
            bitmap.getWidth() - Math.max(0, center - overlap),
            bitmap.getHeight());
    try {
      store.addPage(document, options.rightFirst ? right : left, options);
      store.addPage(document, options.rightFirst ? left : right, options);
    } finally {
      if (left != bitmap) left.recycle();
      if (right != bitmap) right.recycle();
    }
    MyFlatApp.of(c).startProcessing();
    return 2;
  }
}
