package dev.myflat.scanner;

import static org.junit.Assert.*;

import java.util.Random;
import org.junit.Test;

public final class BilinearFieldTest {
  @Test
  public void rowsMatchScalarSamplerBitForBit() {
    Random random = new Random(68471);
    for (int[] shape : new int[][] {{20, 20}, {320, 320}, {1, 17}, {13, 1}, {1, 1}})
      for (int channels : new int[] {1, 2, 4})
        for (int[] size : new int[][] {{1, 1}, {3, 7}, {683, 41}}) {
          int w = shape[0], h = shape[1], ow = size[0], oh = size[1];
          float[] field = new float[w * h * channels];
          for (int i = 0; i < field.length; i++) field[i] = random.nextFloat() * 4 - 2;
          BilinearField sampler = new BilinearField(field, w, h, channels, ow);
          float[] row = new float[ow * channels];
          for (int y = 0; y < oh; y++) {
            sampler.row(y, oh, row);
            for (int x = 0; x < ow; x++)
              for (int c = 0; c < channels; c++) {
                float gx = (float) x / Math.max(1, ow - 1) * (w - 1);
                float gy = (float) y / Math.max(1, oh - 1) * (h - 1);
                assertEquals(
                    Float.floatToIntBits(ImageMath.field(field, w, h, channels, gx, gy, c)),
                    Float.floatToIntBits(row[x * channels + c]));
              }
          }
        }
  }

  @Test
  public void pairedCoordinatesMatchScalarIncludingClampedEdges() {
    Random random = new Random(5911);
    for (int[] shape : new int[][] {{64, 64}, {320, 320}, {1, 13}, {19, 1}, {1, 1}}) {
      int w = shape[0], h = shape[1];
      float[] field = new float[w * h * 2];
      for (int i = 0; i < field.length; i++) field[i] = random.nextFloat() * 6 - 3;
      for (int i = 0; i < 2000; i++) {
        float x = random.nextFloat() * (w + 20) - 10, y = random.nextFloat() * (h + 20) - 10;
        float[] output = {7, 8, x, y, 9};
        BilinearField.pair(field, w, h, x, y, output, 2);
        assertEquals(
            Float.floatToIntBits(ImageMath.field(field, w, h, 2, x, y, 0)),
            Float.floatToIntBits(output[2]));
        assertEquals(
            Float.floatToIntBits(ImageMath.field(field, w, h, 2, x, y, 1)),
            Float.floatToIntBits(output[3]));
        assertEquals(7, output[0], 0);
        assertEquals(8, output[1], 0);
        assertEquals(9, output[4], 0);
      }
    }
  }
}
