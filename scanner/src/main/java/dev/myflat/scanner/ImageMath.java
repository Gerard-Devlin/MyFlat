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
    int[] p = pixels(bitmap);
    float[] out = new float[w * h * channels];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int x0 = x * sw / w,
            x1 = Math.max(x0 + 1, (x + 1) * sw / w),
            y0 = y * sh / h,
            y1 = Math.max(y0 + 1, (y + 1) * sh / h);
        double r = 0, g = 0, b = 0;
        int n = 0;
        for (int yy = y0; yy < Math.min(sh, y1); yy++)
          for (int xx = x0; xx < Math.min(sw, x1); xx++) {
            int color = p[yy * sw + xx];
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
    return out;
  }

  static Bitmap pad(Bitmap source) {
    int w = source.getWidth(),
        h = source.getHeight(),
        pw = Math.max(w, Math.round(h * .75f)),
        ph = Math.max(h, Math.round(w / .75f));
    if (pw == w && ph == h) return source;
    int dx = (pw - w) / 2, dy = (ph - h) / 2;
    int[] p = pixels(source), out = new int[pw * ph];
    for (int y = 0; y < ph; y++)
      for (int x = 0; x < pw; x++)
        out[y * pw + x] =
            p[Math.max(0, Math.min(h - 1, y - dy)) * w + Math.max(0, Math.min(w - 1, x - dx))];
    return bitmap(out, pw, ph);
  }

  static Bitmap sharpen(Bitmap bitmap) {
    int w = bitmap.getWidth(), h = bitmap.getHeight();
    int[] p = pixels(bitmap), out = new int[p.length];
    int[] k = {1, 2, 1};
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        float r = 0, g = 0, b = 0;
        for (int yy = -1; yy <= 1; yy++)
          for (int xx = -1; xx <= 1; xx++) {
            int
                v =
                    p[
                        Math.max(0, Math.min(h - 1, y + yy)) * w
                            + Math.max(0, Math.min(w - 1, x + xx))],
                q = k[xx + 1] * k[yy + 1];
            r += ((v >> 16) & 255) * q;
            g += ((v >> 8) & 255) * q;
            b += (v & 255) * q;
          }
        int v = p[y * w + x];
        out[y * w + x] =
            rgb(
                (2 * ((v >> 16) & 255) - r / 16) / 255,
                (2 * ((v >> 8) & 255) - g / 16) / 255,
                (2 * (v & 255) - b / 16) / 255);
      }
    return bitmap(out, w, h);
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
    if ((long) w * h > 16_000_000) throw new IllegalArgumentException("裁剪尺寸过大");
    float[] dest = {0, 0, w - 1, 0, w - 1, h - 1, 0, h - 1};
    Matrix map = new Matrix();
    if (!map.setPolyToPoly(dest, 0, corners, 0, 4)) throw new IllegalArgumentException("裁剪框无效");
    int[] p = pixels(b), out = new int[w * h];
    float[] row = new float[w * 2];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        row[x * 2] = x;
        row[x * 2 + 1] = y;
      }
      map.mapPoints(row);
      for (int x = 0; x < w; x++)
        out[y * w + x] = sample(p, b.getWidth(), b.getHeight(), row[x * 2], row[x * 2 + 1]);
    }
    return bitmap(out, w, h);
  }

  private static float distance(float[] p, int a, int b) {
    return (float) Math.hypot(p[a] - p[b], p[a + 1] - p[b + 1]);
  }
}
