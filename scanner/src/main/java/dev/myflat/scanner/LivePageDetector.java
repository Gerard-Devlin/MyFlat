package dev.myflat.scanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.tensorflow.lite.Interpreter;

/** Reuses one small interpreter on its owning worker thread for camera preview analysis. */
public final class LivePageDetector implements AutoCloseable {
  public static final class Result {
    public final PageGeometry.State state;
    public final List<float[]> outlines;

    Result(PageGeometry.State state, List<float[]> outlines) {
      this.state = state;
      this.outlines = Collections.unmodifiableList(outlines);
    }

    public static Result searching() {
      return new Result(PageGeometry.State.PAGE_INVALID, Collections.emptyList());
    }
  }

  private final ByteBuffer weights;
  private final Interpreter interpreter;
  private final ByteBuffer input = ModelRunner.buffer(144 * 192 * 16);
  private final ByteBuffer output = ModelRunner.buffer(5004);

  public LivePageDetector(Context context) throws IOException {
    byte[] data;
    try (InputStream in = context.getAssets().open("myflat/freeform_v3_2.tflite")) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] chunk = new byte[16384];
      int count;
      while ((count = in.read(chunk)) != -1) bytes.write(chunk, 0, count);
      data = bytes.toByteArray();
    }
    weights = ModelRunner.buffer(data.length);
    weights.put(data).rewind();
    interpreter =
        new Interpreter(weights, new Interpreter.Options().setNumThreads(2).setUseXNNPACK(true));
  }

  public Result detect(Bitmap frame, boolean doublePage, float split) {
    if (!doublePage) return detectPage(frame);
    int cut = Math.max(1, Math.min(frame.getWidth() - 1, Math.round(frame.getWidth() * split)));
    Bitmap left = Bitmap.createBitmap(frame, 0, 0, cut, frame.getHeight());
    Bitmap right = Bitmap.createBitmap(frame, cut, 0, frame.getWidth() - cut, frame.getHeight());
    try {
      Result a = detectPage(left), b = detectPage(right);
      List<float[]> outlines = new ArrayList<>();
      for (float[] edge : a.outlines)
        outlines.add(mapHalf(edge, 0, (float) cut / frame.getWidth()));
      for (float[] edge : b.outlines)
        outlines.add(
            mapHalf(
                edge, (float) cut / frame.getWidth(), (float) right.getWidth() / frame.getWidth()));
      PageGeometry.State state = a.state != PageGeometry.State.NORMAL ? a.state : b.state;
      if (outlines.size() != 2) state = PageGeometry.State.PAGE_INVALID;
      else if (state == PageGeometry.State.NORMAL) {
        float gutterLeft = outlines.get(0)[48], gutterRight = outlines.get(1)[0];
        if (Math.abs((gutterLeft + gutterRight) / 2 - split) > .06f)
          state = PageGeometry.State.MISALIGNED_GUTTER;
      }
      return new Result(state, outlines);
    } finally {
      left.recycle();
      right.recycle();
    }
  }

  private static float[] mapHalf(float[] edge, float start, float width) {
    float[] mapped = edge.clone();
    for (int i = 0; i < mapped.length; i += 2) mapped[i] = start + mapped[i] * width;
    return mapped;
  }

  private Result detectPage(Bitmap frame) {
    Bitmap padded = ImageMath.pad(frame);
    try {
      float[] pixels = ImageMath.input(padded, 144, 192, false);
      float min = 1, max = 0;
      for (int i = 0; i < pixels.length; i += 4) {
        float luma = pixels[i] * .299f + pixels[i + 1] * .587f + pixels[i + 2] * .114f;
        min = Math.min(min, luma);
        max = Math.max(max, luma);
      }
      if (max - min < .3f) return Result.searching();
      input.rewind();
      input.asFloatBuffer().put(pixels);
      output.rewind();
      interpreter.run(input, output);
      NativeScan.dof(output);
      float[] mesh = new float[1250];
      output.rewind();
      output.asFloatBuffer().get(mesh);
      float[] corners = {
        mesh[0], mesh[1], mesh[48], mesh[49], mesh[1200], mesh[1201], mesh[1248], mesh[1249]
      };
      Matrix matrix = new Matrix();
      if (!matrix.setPolyToPoly(
          new float[] {-.5f, -.5f, .5f, -.5f, -.5f, .5f, .5f, .5f}, 0, corners, 0, 4))
        return Result.searching();
      float[] values = new float[9], angles = new float[3];
      matrix.getValues(values);
      NativeScan.calcEulerAngleFromMatrix(values, angles);
      int dx = (padded.getWidth() - frame.getWidth()) / 2;
      int dy = (padded.getHeight() - frame.getHeight()) / 2;
      for (int i = 0; i < mesh.length; i += 2) {
        mesh[i] = ((mesh[i] + .5f) * padded.getWidth() - dx) / frame.getWidth();
        mesh[i + 1] = ((mesh[i + 1] * .75f + .5f) * padded.getHeight() - dy) / frame.getHeight();
      }
      PageGeometry.State state =
          PageGeometry.assess(
              mesh, angles[0], max - min, (float) frame.getWidth() / frame.getHeight());
      return state == PageGeometry.State.PAGE_INVALID
          ? Result.searching()
          : new Result(state, Collections.singletonList(PageGeometry.outline(mesh)));
    } finally {
      if (padded != frame) padded.recycle();
    }
  }

  @Override
  public void close() {
    interpreter.close();
  }
}
