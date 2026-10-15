package dev.myflat.scanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Random;

/** Checks optimized pixel kernels against the original scalar formulas and edge handling. */
public final class PixelKernelChecks {
  private PixelKernelChecks() {}

  public static void run(Context context) throws Exception {
    Random random = new Random(19381);
    for (int[] size : new int[][] {{1, 1}, {1, 19}, {23, 1}, {2, 2}, {37, 29}, {317, 269}}) {
      int w = size[0], h = size[1];
      int[] original = new int[w * h];
      for (int i = 0; i < original.length; i++)
        original[i] = 0xff000000 | random.nextInt(0x1000000);
      Bitmap photo = Bitmap.createBitmap(original, w, h, Bitmap.Config.ARGB_8888);
      Bitmap sharpened = ImageMath.sharpen(photo);
      int[] actual = ImageMath.pixels(sharpened), expected = new int[original.length];
      int[] weights = {1, 2, 1};
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
          float r = 0, g = 0, b = 0;
          for (int yy = -1; yy <= 1; yy++)
            for (int xx = -1; xx <= 1; xx++) {
              int p =
                  original[
                      Math.max(0, Math.min(h - 1, y + yy)) * w
                          + Math.max(0, Math.min(w - 1, x + xx))];
              int q = weights[xx + 1] * weights[yy + 1];
              r += ((p >> 16) & 255) * q;
              g += ((p >> 8) & 255) * q;
              b += (p & 255) * q;
            }
          int p = original[y * w + x];
          expected[y * w + x] =
              ImageMath.rgb(
                  (2 * ((p >> 16) & 255) - r / 16) / 255,
                  (2 * ((p >> 8) & 255) - g / 16) / 255,
                  (2 * (p & 255) - b / 16) / 255);
        }
      if (!Arrays.equals(expected, actual))
        throw new AssertionError("Sharpen pixel mismatch: " + w + "x" + h);
      sharpened.recycle();
    }
    int w = 601, h = 523;
    int[] original = new int[w * h];
    for (int i = 0; i < original.length; i++) original[i] = 0xff000000 | random.nextInt(0x1000000);
    Bitmap photo = Bitmap.createBitmap(original, w, h, Bitmap.Config.ARGB_8888);
    for (boolean transformed : new boolean[] {false, true}) {
      Bitmap result =
          ImageMath.raster(
              photo,
              w,
              h,
              points -> {
                if (transformed)
                  for (int i = 0; i < points.length; i += 2) {
                    points[i] = points[i] * .7f + 1.1f;
                    points[i + 1] = points[i + 1] * .8f + 2.2f;
                  }
              });
      int[] actual = ImageMath.pixels(result);
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
          int expected =
              transformed
                  ? ImageMath.sample(original, w, h, x * .7f + 1.1f, y * .8f + 2.2f)
                  : original[y * w + x];
          if (actual[y * w + x] != expected)
            throw new AssertionError("Raster mismatch at " + x + "," + y);
        }
      result.recycle();
    }
    checkJpegRegions(context, photo);
    try {
      Thread.currentThread().interrupt();
      try {
        ImageMath.sharpen(photo);
        throw new AssertionError("Sharpen ignored cancellation");
      } catch (java.util.concurrent.CancellationException expected) {
      }
    } finally {
      Thread.interrupted();
      photo.recycle();
    }
  }

  private static void checkJpegRegions(Context context, Bitmap image) throws Exception {
    File file = File.createTempFile("pixel-regions-", ".jpg", context.getCacheDir());
    try {
      try (FileOutputStream out = new FileOutputStream(file)) {
        image.compress(Bitmap.CompressFormat.JPEG, 95, out);
      }
      Bitmap originalJpeg = BitmapFactory.decodeFile(file.getPath());
      try {
        for (boolean warped : new boolean[] {false, true}) {
          ImageMath.Mapping map =
              points -> {
                if (warped)
                  for (int i = 0; i < points.length; i += 2) {
                    points[i] = points[i] * .7f + 1.1f;
                    points[i + 1] = points[i + 1] * .8f + 2.2f;
                  }
              };
          Bitmap cached;
          try (ScanSource source = new ScanSource(file, 1, 0, 0, 0, 1, 1, 0)) {
            cached = source.render(image.getWidth(), image.getHeight(), map);
          }
          // The full original JPEG is the color reference: region decoders can introduce
          // crop-boundary chroma differences that are absent from the original photograph.
          Bitmap expected =
              ImageMath.raster(
                  originalJpeg,
                  image.getWidth(),
                  image.getHeight(),
                  points -> {
                    map.map(points);
                    for (int i = 0; i < points.length; i++) points[i] += .5f;
                    for (int i = 0; i < points.length; i++) points[i] -= .5f;
                  });
          if (!Arrays.equals(ImageMath.pixels(cached), ImageMath.pixels(expected)))
            throw new AssertionError("JPEG cache differs from original full-resolution pixels");
          cached.recycle();
          expected.recycle();
        }
      } finally {
        originalJpeg.recycle();
      }
    } finally {
      file.delete();
    }
  }
}
