package dev.myflat.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import dev.myflat.scanner.ImageMath;
import dev.myflat.scanner.LivePageDetector;
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
    return importUris(c, document, uris, options, java.util.Collections.emptyMap(), progress);
  }

  public static int importUris(
      Context c,
      String document,
      List<Uri> uris,
      ScanSettings options,
      java.util.Map<String, Integer> rotations,
      Progress progress)
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
                int angle = rotations.getOrDefault(uri.toString(), 0);
                Bitmap oriented = angle == 0 ? bitmap : ImageMath.rotate(bitmap, angle);
                count += add(c, store, document, oriented, options);
                if (oriented != bitmap) oriented.recycle();
                bitmap.recycle();
                progress.update("已导入 PDF 第 " + (i + 1) + " / " + renderer.getPageCount() + " 页");
              }
            }
          }
        }
      } else {
        ScanSettings settings = ScanSettings.parse(options.json());
        settings.sourceRotation = rotations.getOrDefault(uri.toString(), 0);
        count += addUri(c, store, document, uri, settings);
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
    Bitmap preview =
        Bitmap.createScaledBitmap(
            bitmap,
            Math.max(
                1,
                bitmap.getWidth()
                    * Math.min(1600, Math.max(bitmap.getWidth(), bitmap.getHeight()))
                    / Math.max(bitmap.getWidth(), bitmap.getHeight())),
            Math.max(
                1,
                bitmap.getHeight()
                    * Math.min(1600, Math.max(bitmap.getWidth(), bitmap.getHeight()))
                    / Math.max(bitmap.getWidth(), bitmap.getHeight())),
            true);
    LivePageDetector.Result result;
    try (LivePageDetector detector = new LivePageDetector(c)) {
      result = detector.detect(preview, true, options.split);
    } finally {
      if (preview != bitmap) preview.recycle();
    }
    ScanSettings[] pages =
        spreadSettings(
            options,
            result.stacked,
            result.pageRotation,
            result.windowSplit,
            result.outputRotation);
    for (ScanSettings page : pages) store.addPage(document, bitmap, page);
    MyFlatApp.of(c).startProcessing();
    return 2;
  }

  public static int addUri(
      Context c, DocumentStore store, String document, Uri uri, ScanSettings options)
      throws IOException {
    if (!options.doublePage) {
      try (InputStream in = c.getContentResolver().openInputStream(uri)) {
        if (in == null) throw new IOException("无法打开图片");
        store.addSource(document, in, options);
      }
      MyFlatApp.of(c).startProcessing();
      return 1;
    }
    Bitmap preview = Images.decode(c, uri, 1600);
    if (options.sourceRotation != 0) {
      Bitmap rotated = ImageMath.rotate(preview, options.sourceRotation);
      if (rotated != preview) preview.recycle();
      preview = rotated;
    }
    LivePageDetector.Result result;
    try (LivePageDetector detector = new LivePageDetector(c)) {
      result = detector.detect(preview, true, options.split);
    } finally {
      preview.recycle();
    }
    ScanSettings[] pages =
        spreadSettings(
            options,
            result.stacked,
            result.pageRotation,
            result.windowSplit,
            result.outputRotation);
    for (ScanSettings page : pages)
      try (InputStream in = c.getContentResolver().openInputStream(uri)) {
        if (in == null) throw new IOException("无法打开图片");
        store.addSource(document, in, page);
      }
    MyFlatApp.of(c).startProcessing();
    return 2;
  }

  static ScanSettings[] spreadSettings(
      ScanSettings options, boolean stacked, int rotation, float position, int outputRotation) {
    ScanSettings first = ScanSettings.parse(options.json()),
        second = ScanSettings.parse(options.json());
    first.doublePage = second.doublePage = false;
    float split = position;
    if (stacked) {
      first.sourceBottom = Math.min(1, split + 1f / 16);
      second.sourceTop = Math.max(0, split - 1f / 16);
    } else {
      first.sourceRight = Math.min(1, split + 1f / 16);
      second.sourceLeft = Math.max(0, split - 1f / 16);
    }
    // Rotation is applied after taking each overlapping page window, as in live detection.
    first.pageRotation = second.pageRotation = rotation;
    first.outputRotation = second.outputRotation = outputRotation;
    int readingRotation = (rotation + outputRotation + 360) % 360;
    if (readingRotation == 90 || readingRotation == 180) {
      ScanSettings temp = first;
      first = second;
      second = temp;
    }
    return options.rightFirst
        ? new ScanSettings[] {second, first}
        : new ScanSettings[] {first, second};
  }
}
