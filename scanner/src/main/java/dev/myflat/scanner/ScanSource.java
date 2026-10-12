package dev.myflat.scanner;

import android.graphics.*;
import java.io.*;

/** Reads sensor-resolution image regions; orientation and page windows are virtual transforms. */
public final class ScanSource implements AutoCloseable {
  private final BitmapRegionDecoder decoder;
  private final Matrix toView = new Matrix(), toRaw = new Matrix();
  public final int width, height;

  public ScanSource(
      File file,
      int exif,
      int rotation,
      float left,
      float top,
      float right,
      float bottom,
      int pageRotation)
      throws IOException {
    decoder = BitmapRegionDecoder.newInstance(file.getPath(), false);
    if (decoder == null) throw new IOException("无法读取原始照片");
    int rawW = decoder.getWidth(), rawH = decoder.getHeight();
    switch (exif) {
      case 2:
        toView.setScale(-1, 1);
        break;
      case 3:
        toView.setRotate(180);
        break;
      case 4:
        toView.setScale(1, -1);
        break;
      case 5:
        toView.setRotate(90);
        toView.postScale(-1, 1);
        break;
      case 6:
        toView.setRotate(90);
        break;
      case 7:
        toView.setRotate(-90);
        toView.postScale(-1, 1);
        break;
      case 8:
        toView.setRotate(-90);
        break;
      default:
        break;
    }
    toView.postRotate(rotation);
    RectF bounds = new RectF(0, 0, rawW, rawH);
    toView.mapRect(bounds);
    toView.postTranslate(-bounds.left, -bounds.top);
    int fullW = Math.round(bounds.width()), fullH = Math.round(bounds.height());
    int x = Math.round(fullW * left), y = Math.round(fullH * top);
    int w = Math.max(1, Math.min(fullW - x, Math.round(fullW * (right - left))));
    int h = Math.max(1, Math.min(fullH - y, Math.round(fullH * (bottom - top))));
    toView.postTranslate(-x, -y);
    toView.postRotate(pageRotation);
    Matrix turn = new Matrix();
    turn.setRotate(pageRotation);
    bounds.set(0, 0, w, h);
    turn.mapRect(bounds);
    toView.postTranslate(-bounds.left, -bounds.top);
    width = Math.round(bounds.width());
    height = Math.round(bounds.height());
    if (!toView.invert(toRaw)) throw new IOException("页面方向无效");
  }

  public Bitmap preview(int edge) {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 1;
    while (Math.max(decoder.getWidth(), decoder.getHeight()) / options.inSampleSize > edge * 1.5f)
      options.inSampleSize *= 2;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap raw =
        decoder.decodeRegion(new Rect(0, 0, decoder.getWidth(), decoder.getHeight()), options);
    if (raw == null) throw new IllegalStateException("无法解码原图预览");
    float scale = Math.min(1f, (float) edge / Math.max(width, height));
    Bitmap result =
        Bitmap.createBitmap(
            Math.max(1, Math.round(width * scale)),
            Math.max(1, Math.round(height * scale)),
            Bitmap.Config.ARGB_8888);
    Matrix draw = new Matrix();
    draw.setScale(
        (float) decoder.getWidth() / raw.getWidth(), (float) decoder.getHeight() / raw.getHeight());
    draw.postConcat(toView);
    draw.postScale((float) result.getWidth() / width, (float) result.getHeight() / height);
    new Canvas(result).drawBitmap(raw, draw, new Paint(Paint.FILTER_BITMAP_FLAG));
    raw.recycle();
    return result;
  }

  public Bitmap render(int w, int h, ImageMath.Mapping mapping) {
    return ImageMath.raster(
        new ImageMath.PixelSource() {
          public int width() {
            return decoder.getWidth();
          }

          public int height() {
            return decoder.getHeight();
          }

          public int[] read(Rect rect) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap tile = decoder.decodeRegion(rect, options);
            if (tile == null) throw new IllegalStateException("无法读取原图区域");
            int[] pixels = new int[rect.width() * rect.height()];
            tile.getPixels(pixels, 0, rect.width(), 0, 0, rect.width(), rect.height());
            tile.recycle();
            return pixels;
          }
        },
        w,
        h,
        points -> {
          mapping.map(points);
          for (int i = 0; i < points.length; i++) points[i] += .5f;
          toRaw.mapPoints(points);
          for (int i = 0; i < points.length; i++) points[i] -= .5f;
        });
  }

  @Override
  public void close() {
    decoder.recycle();
  }
}
