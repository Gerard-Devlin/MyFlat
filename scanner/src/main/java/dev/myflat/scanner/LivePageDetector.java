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
    public final boolean stacked;
    public final float split;
    public final int pageType;
    public int pageRotation, outputRotation;
    public float windowSplit = .5f;
    private final double score;

    Result(PageGeometry.State state, List<float[]> outlines) {
      this(state, outlines, false, .5f, 0, Double.POSITIVE_INFINITY);
    }

    Result(
        PageGeometry.State state,
        List<float[]> outlines,
        boolean stacked,
        float split,
        int pageType,
        double score) {
      this.state = state;
      this.outlines = Collections.unmodifiableList(outlines);
      this.stacked = stacked;
      this.split = split;
      this.pageType = pageType;
      this.score = score;
    }

    public static Result searching() {
      return new Result(PageGeometry.State.PAGE_INVALID, Collections.emptyList());
    }
  }

  private Result previousPair;
  private int preferredRotation, previousRotation;
  private final float[] previousLuma = new float[192];
  private boolean hasLuma;
  private float frameMotion = 1;
  private final ByteBuffer weights;
  private final Interpreter interpreter;
  private final ByteBuffer classifierWeights;
  private final Interpreter classifier;
  private final ByteBuffer classification = ModelRunner.buffer(20);
  private final ByteBuffer input = ModelRunner.buffer(144 * 192 * 16);
  private final ByteBuffer output = ModelRunner.buffer(5004);

  public LivePageDetector(Context context) throws IOException {
    weights = readModel(context, "freeform_v3_2");
    interpreter =
        new Interpreter(weights, new Interpreter.Options().setNumThreads(2).setUseXNNPACK(true));
    classifierWeights = readModel(context, "optp_v3_3");
    classifier =
        new Interpreter(
            classifierWeights, new Interpreter.Options().setNumThreads(2).setUseXNNPACK(true));
  }

  private static ByteBuffer readModel(Context context, String name) throws IOException {
    byte[] data;
    try (InputStream in = context.getAssets().open("myflat/" + name + ".tflite")) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] chunk = new byte[16384];
      int count;
      while ((count = in.read(chunk)) != -1) bytes.write(chunk, 0, count);
      data = bytes.toByteArray();
    }
    ByteBuffer weights = ModelRunner.buffer(data.length);
    weights.put(data).rewind();
    return weights;
  }

  /** Two overlapping page windows match the model's training transform (9/16, offset 7/16). */
  public Result detect(Bitmap frame, boolean doublePage, float split) {
    return detect(frame, doublePage, split, 0);
  }

  /** The camera supplies its device orientation; imported JPEGs are already EXIF-oriented. */
  public Result detect(Bitmap frame, boolean doublePage, float split, int rotationPreference) {
    preferredRotation = (rotationPreference % 360 + 360) % 360;
    int type = classify(frame);
    if (!doublePage) {
      Result page = detectPage(frame, true);
      return new Result(page.state, page.outlines, false, split, type, 0);
    }
    if (previousPair != null
        && previousRotation == preferredRotation
        && frameMotion < .035f
        && !(type == 3 && !previousPair.stacked || type == 4 && previousPair.stacked)) {
      int rotation = previousPair.pageRotation - (previousPair.stacked ? -90 : 0);
      Result tracked =
          pair(frame, previousPair.stacked, previousPair.windowSplit, split, type, rotation);
      if (tracked.score < 10) {
        previousPair = tracked;
        return tracked;
      }
    }
    Result best = null;
    for (boolean stacked : new boolean[] {true, false})
      for (int rotation : new int[] {0, 180}) {
        Result candidate = pair(frame, stacked, split, split, type, rotation);
        if (best == null || candidate.score < best.score) best = candidate;
      }
    if (best.score >= 10)
      for (float position : new float[] {.4f, .6f})
        for (boolean stacked : new boolean[] {true, false})
          for (int rotation : new int[] {0, 180}) {
            if (type == 3 && !stacked || type == 4 && stacked) continue;
            Result candidate = pair(frame, stacked, position, split, type, rotation);
            if (candidate.score < best.score) best = candidate;
          }
    if (best.score >= 10)
      return new Result(
          PageGeometry.State.PAGE_INVALID,
          Collections.emptyList(),
          best.stacked,
          split,
          type,
          best.score);
    previousPair = best;
    previousRotation = preferredRotation;
    return best;
  }

  private int classify(Bitmap frame) {
    Bitmap padded = ImageMath.pad(frame);
    try {
      input.rewind();
      float[] pixels = ImageMath.input(padded, 144, 192, false);
      float change = 0;
      for (int i = 0; i < previousLuma.length; i++) {
        int offset = i * (144 * 192 / previousLuma.length) * 4;
        float luma =
            pixels[offset] * .299f + pixels[offset + 1] * .587f + pixels[offset + 2] * .114f;
        change += Math.abs(luma - previousLuma[i]);
        previousLuma[i] = luma;
      }
      frameMotion = hasLuma ? change / previousLuma.length : 1;
      hasLuma = true;
      input.asFloatBuffer().put(pixels);
      classification.rewind();
      classifier.run(input, classification);
      int best = 0;
      for (int i = 1; i < 5; i++)
        if (classification.getFloat(i * 4) > classification.getFloat(best * 4)) best = i;
      return best;
    } finally {
      if (padded != frame) padded.recycle();
    }
  }

  private Result pair(
      Bitmap frame, boolean stacked, float split, float alignment, int type, int rotation) {
    int edge = stacked ? frame.getHeight() : frame.getWidth();
    int end = Math.max(1, Math.min(edge, Math.round(edge * (split + 1f / 16))));
    int start = Math.max(0, Math.min(edge - 1, Math.round(edge * (split - 1f / 16))));
    Bitmap first =
        stacked
            ? Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), end)
            : Bitmap.createBitmap(frame, 0, 0, end, frame.getHeight());
    Bitmap second =
        stacked
            ? Bitmap.createBitmap(frame, 0, start, frame.getWidth(), edge - start)
            : Bitmap.createBitmap(frame, start, 0, edge - start, frame.getHeight());
    int degrees = (stacked ? -90 : 0) + rotation;
    Bitmap a = degrees == 0 ? first : ImageMath.rotate(first, degrees);
    Bitmap b = degrees == 0 ? second : ImageMath.rotate(second, degrees);
    try {
      Result pa = detectPage(a, false), pb = detectPage(b, false);
      if (pa.outlines.isEmpty() || pb.outlines.isEmpty())
        return new Result(
            PageGeometry.State.PAGE_INVALID, Collections.emptyList(), stacked, split, type, 1000);
      float[] left = mapPage(pa.outlines.get(0), stacked, 0, (float) end / edge, rotation);
      float[] right =
          mapPage(
              pb.outlines.get(0),
              stacked,
              (float) start / edge,
              (float) (edge - start) / edge,
              rotation);
      // First page's right edge and second page's left edge are both the book spine.
      double mismatch = 0, center = 0;
      for (int k = 0; k <= 24; k++) {
        int ai = (rotation == 0 ? (24 + k) % 96 : (96 - k) % 96) * 2;
        int bi = (rotation == 0 ? (96 - k) % 96 : (24 + k) % 96) * 2;
        float dx = (left[ai] - right[bi]) * frame.getWidth();
        float dy = (left[ai + 1] - right[bi + 1]) * frame.getHeight();
        mismatch += Math.hypot(dx, dy) / (stacked ? frame.getWidth() : frame.getHeight());
        center += stacked ? (left[ai + 1] + right[bi + 1]) / 2 : (left[ai] + right[bi]) / 2;
      }
      mismatch /= 25;
      center /= 25;
      double offset = Math.abs(center - alignment);
      PageGeometry.State state = pa.state != PageGeometry.State.NORMAL ? pa.state : pb.state;
      if (mismatch > .04) state = PageGeometry.State.PAGE_INVALID;
      else if (offset > 7f / 80 && state == PageGeometry.State.NORMAL)
        state = PageGeometry.State.MISALIGNED_GUTTER;
      double score = mismatch * 5 + offset + (state == PageGeometry.State.PAGE_INVALID ? 10 : 0);
      if (type == 3 && !stacked || type == 4 && stacked) score += .1;
      int preferred =
          stacked
              ? (preferredRotation == 90 || preferredRotation == 180 ? 90 : 270)
              : (preferredRotation == 180 || preferredRotation == 270 ? 180 : 0);
      if ((degrees + 360) % 360 != preferred) score += .35;
      Result result =
          new Result(state, Arrays.asList(left, right), stacked, (float) center, type, score);
      result.pageRotation = degrees;
      result.outputRotation = (degrees + 360) % 360 == preferred ? 0 : 180;
      result.windowSplit = split;
      return result;
    } finally {
      if (a != first) a.recycle();
      if (b != second) b.recycle();
      if (first != frame) first.recycle();
      if (second != frame) second.recycle();
    }
  }

  private static float[] mapPage(
      float[] edge, boolean stacked, float start, float extent, int rotation) {
    float[] mapped = edge.clone();
    for (int i = 0; i < mapped.length; i += 2) {
      float x = rotation == 0 ? edge[i] : 1 - edge[i],
          y = rotation == 0 ? edge[i + 1] : 1 - edge[i + 1];
      mapped[i] = stacked ? 1 - y : start + x * extent;
      mapped[i + 1] = stacked ? start + x * extent : y;
    }
    return mapped;
  }

  private Result detectPage(Bitmap frame, boolean pad) {
    Bitmap padded = pad ? ImageMath.pad(frame) : frame;
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
    classifier.close();
  }
}
