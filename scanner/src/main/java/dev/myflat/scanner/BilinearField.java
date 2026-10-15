package dev.myflat.scanner;

/** Reuses interpolation coordinates without changing the scalar field's floating-point order. */
final class BilinearField {
  private final float[] field, weight, inverse;
  private final int[] left, right;
  private final int width, height, channels, outputWidth;

  BilinearField(float[] field, int width, int height, int channels, int outputWidth) {
    if (width < 1
        || height < 1
        || channels < 1
        || outputWidth < 1
        || (long) width * height * channels != field.length)
      throw new IllegalArgumentException("Invalid interpolation field");
    this.field = field;
    this.width = width;
    this.height = height;
    this.channels = channels;
    this.outputWidth = outputWidth;
    left = new int[outputWidth];
    right = new int[outputWidth];
    weight = new float[outputWidth];
    inverse = new float[outputWidth];
    for (int x = 0; x < outputWidth; x++) {
      float gx = (float) x / Math.max(1, outputWidth - 1) * (width - 1);
      gx = Math.max(0, Math.min(width - 1, gx));
      int x0 = (int) gx;
      left[x] = x0 * channels;
      right[x] = Math.min(x0 + 1, width - 1) * channels;
      weight[x] = gx - x0;
      inverse[x] = 1 - weight[x];
    }
  }

  void row(int y, int outputHeight, float[] out) {
    float gy = (float) y / Math.max(1, outputHeight - 1) * (height - 1);
    gy = Math.max(0, Math.min(height - 1, gy));
    int y0 = (int) gy, y1 = Math.min(y0 + 1, height - 1);
    float b = gy - y0, inverseB = 1 - b;
    int top = y0 * width * channels, bottom = y1 * width * channels;
    for (int x = 0; x < outputWidth; x++) {
      int l = left[x], r = right[x], target = x * channels;
      float a = weight[x], inverseA = inverse[x];
      for (int c = 0; c < channels; c++)
        out[target + c] =
            (field[top + l + c] * inverseA + field[top + r + c] * a) * inverseB
                + (field[bottom + l + c] * inverseA + field[bottom + r + c] * a) * b;
    }
  }

  static void pair(
      float[] field, int width, int height, float x, float y, float[] out, int offset) {
    x = Math.max(0, Math.min(width - 1, x));
    y = Math.max(0, Math.min(height - 1, y));
    int x0 = (int) x, y0 = (int) y;
    int x1 = Math.min(x0 + 1, width - 1), y1 = Math.min(y0 + 1, height - 1);
    float a = x - x0, b = y - y0, inverseA = 1 - a, inverseB = 1 - b;
    int p00 = (y0 * width + x0) * 2, p10 = (y0 * width + x1) * 2;
    int p01 = (y1 * width + x0) * 2, p11 = (y1 * width + x1) * 2;
    out[offset] =
        (field[p00] * inverseA + field[p10] * a) * inverseB
            + (field[p01] * inverseA + field[p11] * a) * b;
    out[offset + 1] =
        (field[p00 + 1] * inverseA + field[p10 + 1] * a) * inverseB
            + (field[p01 + 1] * inverseA + field[p11 + 1] * a) * b;
  }
}
