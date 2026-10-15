package dev.myflat.scanner;

import android.graphics.*;

/** CPU sampling keeps processing independent from GLES compute support. */
public final class ImageMath {
  private ImageMath() {}

  public static float clamp(float v) {
    return Math.max(0, Math.min(1, v));
  }

  public static int rgb(float r, float g, float b) {
    return Color.rgb(
        Math.round(clamp(r) * 255), Math.round(clamp(g) * 255), Math.round(clamp(b) * 255));
  }

  public static int sample(int[] p, int w, int h, float x, float y) {
    x = Math.max(0, Math.min(w - 1, x));
    y = Math.max(0, Math.min(h - 1, y));
    int x0 = (int) x, y0 = (int) y, x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
    float a = x - x0, b = y - y0;
    int p00 = p[y0 * w + x0], p10 = p[y0 * w + x1], p01 = p[y1 * w + x0], p11 = p[y1 * w + x1];
    int out = 0xff000000;
    for (int shift = 0; shift <= 16; shift += 8) {
      float top = ((p00 >> shift) & 255) * (1 - a) + ((p10 >> shift) & 255) * a;
      float bot = ((p01 >> shift) & 255) * (1 - a) + ((p11 >> shift) & 255) * a;
      out |= Math.round(top * (1 - b) + bot * b) << shift;
    }
    return out;
  }

  public static float field(float[] f, int w, int h, int c, float x, float y, int channel) {
    x = Math.max(0, Math.min(w - 1, x));
    y = Math.max(0, Math.min(h - 1, y));
    int x0 = (int) x, y0 = (int) y, x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
    float a = x - x0, b = y - y0;
    return (f[(y0 * w + x0) * c + channel] * (1 - a) + f[(y0 * w + x1) * c + channel] * a) * (1 - b)
        + (f[(y1 * w + x0) * c + channel] * (1 - a) + f[(y1 * w + x1) * c + channel] * a) * b;
  }

  public static int[] pixels(Bitmap bitmap) {
    int[] p = new int[bitmap.getWidth() * bitmap.getHeight()];
    bitmap.getPixels(p, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
    return p;
  }

  public static Bitmap bitmap(int[] p, int w, int h) {
    return Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888);
  }

  static float[] input(Bitmap bitmap, int w, int h, boolean gray) {
    int sw = bitmap.getWidth(), sh = bitmap.getHeight(), channels = gray ? 1 : 4;
    int[] p = new int[sw * Math.max(1, (sh + h - 1) / h + 1)];
    float[] out = new float[w * h * channels];
    for (int y = 0; y < h; y++) {
      int bandY = y * sh / h, bandEnd = Math.min(sh, Math.max(bandY + 1, (y + 1) * sh / h));
      bitmap.getPixels(p, 0, sw, 0, bandY, sw, bandEnd - bandY);
      for (int x = 0; x < w; x++) {
        int x0 = x * sw / w,
            x1 = Math.max(x0 + 1, (x + 1) * sw / w),
            y0 = y * sh / h,
            y1 = Math.max(y0 + 1, (y + 1) * sh / h);
        double r = 0, g = 0, b = 0;
        int n = 0;
        for (int yy = y0; yy < Math.min(sh, y1); yy++)
          for (int xx = x0; xx < Math.min(sw, x1); xx++) {
            int color = p[(yy - bandY) * sw + xx];
            r += (color >> 16) & 255;
            g += (color >> 8) & 255;
            b += color & 255;
            n++;
          }
        int i = (y * w + x) * channels;
        float rr = (float) (r / n / 255), gg = (float) (g / n / 255), bb = (float) (b / n / 255);
        if (gray) out[i] = .299f * rr + .587f * gg + .114f * bb;
        else {
          out[i] = rr;
          out[i + 1] = gg;
          out[i + 2] = bb;
          out[i + 3] = 1;
        }
      }
    }
    return out;
  }

  static Bitmap pad(Bitmap source) {
    int w = source.getWidth(),
        h = source.getHeight(),
        pw = Math.max(w, Math.round(h * .75f)),
        ph = Math.max(h, Math.round(w / .75f));
    if (pw == w && ph == h) return source;
    int dx = (pw - w) / 2, dy = (ph - h) / 2;
    Bitmap padded = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
    BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    Matrix transform = new Matrix();
    transform.setTranslate(dx, dy);
    shader.setLocalMatrix(transform);
    Paint paint = new Paint();
    paint.setShader(shader);
    new Canvas(padded).drawRect(0, 0, pw, ph, paint);
    return padded;
  }

  static Bitmap sharpen(Bitmap bitmap) {
    if (!bitmap.isMutable()) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
    int w = bitmap.getWidth(), h = bitmap.getHeight();
    int[] current = new int[w], next = new int[w], out = new int[w];
    int[] previousBlur = new int[w * 3], currentBlur = new int[w * 3], nextBlur = new int[w * 3];
    bitmap.getPixels(current, 0, w, 0, 0, w, 1);
    horizontalBlur(current, currentBlur);
    System.arraycopy(currentBlur, 0, previousBlur, 0, currentBlur.length);
    bitmap.getPixels(next, 0, w, 0, Math.min(1, h - 1), w, 1);
    horizontalBlur(next, nextBlur);
    for (int y = 0; y < h; y++) {
      if ((y & 31) == 0 && Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException();
      for (int x = 0; x < w; x++) {
        int i = x * 3, value = current[x];
        float r = previousBlur[i] + 2 * currentBlur[i] + nextBlur[i];
        float g = previousBlur[i + 1] + 2 * currentBlur[i + 1] + nextBlur[i + 1];
        float b = previousBlur[i + 2] + 2 * currentBlur[i + 2] + nextBlur[i + 2];
        out[x] =
            rgb(
                (2 * ((value >> 16) & 255) - r / 16) / 255,
                (2 * ((value >> 8) & 255) - g / 16) / 255,
                (2 * (value & 255) - b / 16) / 255);
      }
      bitmap.setPixels(out, 0, w, 0, y, w, 1);
      int[] oldPixels = current;
      current = next;
      next = oldPixels;
      int[] oldBlur = previousBlur;
      previousBlur = currentBlur;
      currentBlur = nextBlur;
      nextBlur = oldBlur;
      if (y + 2 < h) {
        bitmap.getPixels(next, 0, w, 0, y + 2, w, 1);
        horizontalBlur(next, nextBlur);
      } else {
        System.arraycopy(current, 0, next, 0, w);
        System.arraycopy(currentBlur, 0, nextBlur, 0, currentBlur.length);
      }
    }
    return bitmap;
  }

  private static void horizontalBlur(int[] pixels, int[] out) {
    int w = pixels.length;
    for (int x = 0; x < w; x++) {
      int left = pixels[Math.max(0, x - 1)],
          center = pixels[x],
          right = pixels[Math.min(w - 1, x + 1)];
      int i = x * 3;
      out[i] = ((left >> 16) & 255) + 2 * ((center >> 16) & 255) + ((right >> 16) & 255);
      out[i + 1] = ((left >> 8) & 255) + 2 * ((center >> 8) & 255) + ((right >> 8) & 255);
      out[i + 2] = (left & 255) + 2 * (center & 255) + (right & 255);
    }
  }

  public interface Mapping {
    void map(float[] points);
  }

  interface PixelSource {
    int width();

    int height();

    int[] read(Rect region);
  }

  /** Rasterizes small patches so the full source and result never need Java pixel-array copies. */
  static Bitmap raster(Bitmap source, int width, int height, Mapping mapping) {
    return raster(
        new PixelSource() {
          private int[] scratch;

          public int width() {
            return source.getWidth();
          }

          public int height() {
            return source.getHeight();
          }

          public int[] read(Rect r) {
            int count = r.width() * r.height();
            if (scratch == null || scratch.length < count) scratch = new int[count];
            source.getPixels(scratch, 0, r.width(), r.left, r.top, r.width(), r.height());
            return scratch;
          }
        },
        width,
        height,
        mapping);
  }

  static Bitmap raster(PixelSource source, int width, int height, Mapping mapping) {
    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    int sw = source.width(), sh = source.height();
    float[][] pointBuffers = new float[4][];
    int[][] outputBuffers = new int[4][];
    for (int ty = 0; ty < height; ty += 256)
      for (int tx = 0; tx < width; tx += 256) {
        if (Thread.currentThread().isInterrupted()) {
          result.recycle();
          throw new java.util.concurrent.CancellationException();
        }
        int tw = Math.min(256, width - tx), th = Math.min(256, height - ty);
        int slot = (tw < 256 ? 1 : 0) | (th < 256 ? 2 : 0);
        if (pointBuffers[slot] == null) {
          pointBuffers[slot] = new float[tw * th * 2];
          outputBuffers[slot] = new int[tw * th];
        }
        float[] points = pointBuffers[slot];
        for (int y = 0; y < th; y++)
          for (int x = 0; x < tw; x++) {
            int i = (y * tw + x) * 2;
            points[i] = tx + x;
            points[i + 1] = ty + y;
          }
        mapping.map(points);
        float left = sw - 1, top = sh - 1, right = 0, bottom = 0;
        for (int i = 0; i < points.length; i += 2) {
          points[i] = Math.max(0, Math.min(sw - 1, points[i]));
          points[i + 1] = Math.max(0, Math.min(sh - 1, points[i + 1]));
          left = Math.min(left, points[i]);
          right = Math.max(right, points[i]);
          top = Math.min(top, points[i + 1]);
          bottom = Math.max(bottom, points[i + 1]);
        }
        int bx = (int) left,
            by = (int) top,
            bw = Math.min(sw - 1, (int) Math.ceil(right) + 1) - bx + 1,
            bh = Math.min(sh - 1, (int) Math.ceil(bottom) + 1) - by + 1;
        int[] input = source.read(new Rect(bx, by, bx + bw, by + bh)), out = outputBuffers[slot];
        for (int i = 0; i < out.length; i++)
          out[i] = sample(input, bw, bh, points[i * 2] - bx, points[i * 2 + 1] - by);
        result.setPixels(out, 0, tw, tx, ty, tw, th);
      }
    return result;
  }

  /** Turns a completed page over without allocating another sensor-sized bitmap. */
  public static void rotate180InPlace(Bitmap image) {
    int width = image.getWidth(), height = image.getHeight();
    int[] top = new int[width], bottom = new int[width];
    for (int y = 0; y < (height + 1) / 2; y++) {
      int opposite = height - 1 - y;
      image.getPixels(top, 0, width, 0, y, width, 1);
      image.getPixels(bottom, 0, width, 0, opposite, width, 1);
      for (int x = 0; x < width / 2; x++) {
        int t = top[x];
        top[x] = top[width - 1 - x];
        top[width - 1 - x] = t;
        t = bottom[x];
        bottom[x] = bottom[width - 1 - x];
        bottom[width - 1 - x] = t;
      }
      image.setPixels(bottom, 0, width, 0, y, width, 1);
      if (opposite != y) image.setPixels(top, 0, width, 0, opposite, width, 1);
    }
  }

  public static Bitmap rotate(Bitmap b, int degrees) {
    Matrix m = new Matrix();
    m.postRotate(degrees);
    return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
  }

  public static Bitmap crop(Bitmap b, float[] corners) {
    float top = distance(corners, 0, 2),
        bottom = distance(corners, 6, 4),
        left = distance(corners, 0, 6),
        right = distance(corners, 2, 4);
    int w = Math.max(2, Math.round((top + bottom) / 2)),
        h = Math.max(2, Math.round((left + right) / 2));
    if ((long) w * h > Integer.MAX_VALUE / 4) throw new IllegalArgumentException("裁剪尺寸过大");
    float[] dest = {0, 0, w - 1, 0, w - 1, h - 1, 0, h - 1};
    Matrix map = new Matrix();
    if (!map.setPolyToPoly(dest, 0, corners, 0, 4)) throw new IllegalArgumentException("裁剪框无效");
    return raster(b, w, h, map::mapPoints);
  }

  private static float distance(float[] p, int a, int b) {
    return (float) Math.hypot(p[a] - p[b], p[a + 1] - p[b + 1]);
  }
}
