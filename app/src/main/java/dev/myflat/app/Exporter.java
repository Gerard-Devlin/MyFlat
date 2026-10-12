package dev.myflat.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Creates real image-only PDFs and ZIPs; no OCR or network path. */
public final class Exporter {
  public enum Format {
    PDF,
    JPEG,
    ZIP
  }

  public interface Progress {
    void update(int done, int total);
  }

  private Exporter() {}

  public static File create(
      Context c,
      String title,
      List<DocumentStore.Page> pages,
      Format format,
      boolean a4,
      Progress progress)
      throws IOException {
    if (pages.isEmpty()) throw new IOException("请选择已完成的页面");
    DocumentStore store = MyFlatApp.of(c).store;
    for (DocumentStore.Page page : pages)
      if (!"ready".equals(page.status)) throw new IOException("请等待页面处理完成后导出");
    if (format == Format.JPEG && pages.size() != 1) throw new IOException("多张图片请使用 ZIP 导出");
    File folder = new File(c.getCacheDir(), "exports");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建导出目录");
    String extension = format == Format.PDF ? ".pdf" : format == Format.ZIP ? ".zip" : ".jpg";
    File result = new File(folder, safeName(title) + "-" + System.currentTimeMillis() + extension);
    try {
      if (format == Format.JPEG) {
        try (InputStream in = new FileInputStream(store.file(pages.get(0).result));
            OutputStream out = new FileOutputStream(result)) {
          copy(in, out);
        }
        progress.update(1, 1);
      } else if (format == Format.ZIP) {
        try (ZipOutputStream zip =
            new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(result)))) {
          for (int i = 0; i < pages.size(); i++) {
            zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "%03d.jpg", i + 1)));
            try (InputStream in = new FileInputStream(store.file(pages.get(i).result))) {
              copy(in, zip);
            }
            zip.closeEntry();
            progress.update(i + 1, pages.size());
          }
        }
      } else {
        PdfDocument pdf = new PdfDocument();
        try {
          for (int i = 0; i < pages.size(); i++) {
            Bitmap bitmap = Images.decode(store.file(pages.get(i).result), 3600);
            int w, h;
            if (a4) {
              boolean landscape = bitmap.getWidth() > bitmap.getHeight();
              w = landscape ? 842 : 595;
              h = landscape ? 595 : 842;
            } else {
              float scale = Math.min(1f, 1440f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
              w = Math.max(1, Math.round(bitmap.getWidth() * scale));
              h = Math.max(1, Math.round(bitmap.getHeight() * scale));
            }
            PdfDocument.Page page =
                pdf.startPage(new PdfDocument.PageInfo.Builder(w, h, i + 1).create());
            Canvas canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            float scale = Math.min((float) w / bitmap.getWidth(), (float) h / bitmap.getHeight());
            float dw = bitmap.getWidth() * scale, dh = bitmap.getHeight() * scale;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(
                bitmap,
                null,
                new RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2),
                paint);
            pdf.finishPage(page);
            bitmap.recycle();
            progress.update(i + 1, pages.size());
          }
          try (OutputStream out = new BufferedOutputStream(new FileOutputStream(result))) {
            pdf.writeTo(out);
          }
        } finally {
          pdf.close();
        }
      }
      return result;
    } catch (IOException | RuntimeException ex) {
      result.delete();
      throw ex;
    }
  }

  public static String safeName(String title) {
    String name = DocumentStore.title(title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    return name.equals(".") || name.equals("..") ? "扫描文档" : name;
  }

  public static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] data = new byte[32768];
    int count;
    while ((count = in.read(data)) != -1) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导出已取消");
      out.write(data, 0, count);
    }
  }
}
