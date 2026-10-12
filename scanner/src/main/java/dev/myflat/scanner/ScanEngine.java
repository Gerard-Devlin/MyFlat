package dev.myflat.scanner;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Local scan pipeline. Original weights/native mesh decoder; CPU rasterization and compositing. */
public final class ScanEngine {
  public static final class Options {
    public boolean dewarp = true,
        enhance = true,
        fingers = true,
        straighten = false,
        handwriting = false;
    public int maxEdge = 2400;
  }

  public interface Progress {
    void update(String stage, int percent);
  }

  public interface Observer {
    void image(String stage, Bitmap image) throws IOException;
  }

  public static final class Result {
    public final Bitmap image;
    public final Map<String, Long> timings;
    public final String diagnostics;

    Result(Bitmap b, Map<String, Long> t, String d) {
      image = b;
      timings = Collections.unmodifiableMap(t);
      diagnostics = d;
    }
  }

  private final ModelRunner runner;

  public ScanEngine(Context context) {
    runner = new ModelRunner(context);
  }

  public Result process(Bitmap original, Options options, Progress progress, Observer observer)
      throws IOException {
    Map<String, Long> times = new LinkedHashMap<>();
    StringBuilder debug = new StringBuilder();
    long start = SystemClock.elapsedRealtime();
    Bitmap current = limit(original, options.maxEdge);
    checkCancelled();
    progress.update("准备图像", 5);
    if (options.dewarp) {
      long t = SystemClock.elapsedRealtime();
      current = dewarp(current, debug);
      times.put("dewarp", SystemClock.elapsedRealtime() - t);
      emit(observer, "01-dewarp", current);
    }
    progress.update("页面展平", 25);
    checkCancelled();
    if (options.straighten) {
      long t = SystemClock.elapsedRealtime();
      current = straighten(current);
      times.put("straighten", SystemClock.elapsedRealtime() - t);
      emit(observer, "02-straighten", current);
    }
    progress.update("色彩增强", 45);
    checkCancelled();
    // Masks in the reference pipeline use the pre-enhancement image to protect printed content.
    Bitmap maskSource = current;
    if (options.enhance) {
      long t = SystemClock.elapsedRealtime();
      current = enhance(current);
      times.put("enhance", SystemClock.elapsedRealtime() - t);
      emit(observer, "03-enhance", current);
    }
    progress.update("手指消除", 65);
    checkCancelled();
    if (options.fingers) {
      long t = SystemClock.elapsedRealtime();
      current = removeFingers(current, maskSource, debug);
      times.put("fingers", SystemClock.elapsedRealtime() - t);
      emit(observer, "04-fingers", current);
    }
    progress.update("手写消除", 85);
    checkCancelled();
    if (options.handwriting) {
      long t = SystemClock.elapsedRealtime();
      current = handwriting(current, progress);
      times.put("handwriting", SystemClock.elapsedRealtime() - t);
      emit(observer, "05-handwriting", current);
    }
    times.put("total", SystemClock.elapsedRealtime() - start);
    progress.update("处理完成", 100);
    return new Result(current, times, debug.toString());
  }

  private static void emit(Observer o, String stage, Bitmap b) throws IOException {
    if (o != null) o.image(stage, b);
  }

  private static void checkCancelled() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消处理");
  }

  private static Bitmap limit(Bitmap b, int max) {
    int edge = Math.max(b.getWidth(), b.getHeight());
    if (edge <= max) return b;
    float scale = (float) max / edge;
    return Bitmap.createScaledBitmap(
        b,
        Math.max(1, Math.round(b.getWidth() * scale)),
        Math.max(1, Math.round(b.getHeight() * scale)),
        true);
  }

  private Bitmap dewarp(Bitmap source, StringBuilder debug) throws IOException {
    Bitmap padded = ImageMath.pad(source);
    float[] prediction = runner.run("freeform_v3_2", ImageMath.input(padded, 144, 192, false));
    ByteBuffer model = ModelRunner.buffer(5004);
    model.asFloatBuffer().put(prediction);
    NativeScan.dof(model);
    ByteBuffer size = ModelRunner.buffer(8);
    NativeScan.calcFreeformOutputSize(padded.getWidth(), model, size);
    int w = size.getInt(0), h = size.getInt(4);
    if (w < 16 || h < 16 || w > 12000 || h > 12000)
      throw new IOException("展平模型返回无效尺寸：" + w + "×" + h);
    float ratio =
        Math.min(
            1f, Math.min(2400f / Math.max(w, h), (float) Math.sqrt(6_000_000d / ((long) w * h))));
    w = Math.max(16, Math.round(w * ratio));
    h = Math.max(16, Math.round(h * ratio));
    ByteBuffer remap = ModelRunner.buffer(32768),
        indices = ModelRunner.buffer(95256),
        positions = ModelRunner.buffer(32768);
    NativeScan.otr(model, remap);
    NativeScan.ibf(indices, positions);
    float[] uv = new float[8192], pos = new float[8192];
    remap.asFloatBuffer().get(uv);
    positions.asFloatBuffer().get(pos);
    // Arrange the native vertices into a canonical 64x64 destination grid. Native order is not
    // assumed.
    float[] grid = new float[8192];
    boolean[] seen = new boolean[4096];
    for (int i = 0; i < 4096; i++) {
      int x = Math.round((pos[i * 2] + 1) * 31.5f), y = Math.round((pos[i * 2 + 1] + 1) * 31.5f);
      if (x < 0 || x > 63 || y < 0 || y > 63) throw new IOException("无效展平网格");
      int q = y * 64 + x;
      grid[q * 2] = uv[i * 2] + .5f;
      grid[q * 2 + 1] = uv[i * 2 + 1] * .75f + .5f;
      seen[q] = true;
    }
    for (boolean found : seen) if (!found) throw new IOException("展平网格不完整");
    debug
        .append("padded=")
        .append(padded.getWidth())
        .append('x')
        .append(padded.getHeight())
        .append(" output=")
        .append(w)
        .append('x')
        .append(h)
        .append(" uv corners=");
    for (int i : new int[] {0, 63, 4032, 4095})
      debug.append(Arrays.toString(new float[] {grid[i * 2], grid[i * 2 + 1]}));
    debug.append('\n');
    Bitmap mapped = remap(padded, grid, 64, 64, w, h);
    return ImageMath.sharpen(mapped);
  }

  private static Bitmap remap(Bitmap source, float[] grid, int gw, int gh, int w, int h)
      throws IOException {
    int sw = source.getWidth(), sh = source.getHeight();
    int[] pixels = ImageMath.pixels(source), out = new int[w * h];
    for (int y = 0; y < h; y++) {
      if (y % 32 == 0) checkCancelled();
      float gy = (float) y / (h - 1) * (gh - 1);
      for (int x = 0; x < w; x++) {
        float gx = (float) x / (w - 1) * (gw - 1);
        float u = ImageMath.field(grid, gw, gh, 2, gx, gy, 0),
            v = ImageMath.field(grid, gw, gh, 2, gx, gy, 1);
        out[y * w + x] = ImageMath.sample(pixels, sw, sh, u * sw - .5f, v * sh - .5f);
      }
    }
    return ImageMath.bitmap(out, w, h);
  }

  private Bitmap straighten(Bitmap source) throws IOException {
    float[] grid = runner.run("pptp_v1_1_0", ImageMath.input(source, 320, 320, false));
    return remap(source, grid, 320, 320, source.getWidth(), source.getHeight());
  }

  private Bitmap enhance(Bitmap source) throws IOException {
    float[] in = ImageMath.input(source, 320, 320, false), dist = runner.run("enhance_v10", in);
    boolean useBlack = runner.run("blackpoint_classification_v1_0_1", in)[0] < 0;
    float[] black = useBlack ? runner.run("blackpoint_v1_0_1", in) : null;
    int w = source.getWidth(), h = source.getHeight();
    int[] p = ImageMath.pixels(source), out = new int[p.length];
    float[] rgb = new float[3], d = new float[4];
    for (int y = 0; y < h; y++) {
      if (y % 32 == 0) checkCancelled();
      float fy = (float) y / (h - 1);
      for (int x = 0; x < w; x++) {
        float fx = (float) x / (w - 1);
        int color = p[y * w + x];
        boolean almost = true;
        for (int c = 0; c < 4; c++) {
          d[c] = ImageMath.field(dist, 320, 320, 4, fx * 319, fy * 319, c);
          float original = c == 3 ? 1 : ((color >> (16 - c * 8)) & 255) / 255f;
          if (Math.abs(d[c] - original) >= .05f) almost = false;
        }
        for (int c = 0; c < 3; c++) {
          float v = ((color >> (16 - c * 8)) & 255) / 255f;
          float reference = almost ? v : d[c];
          float a = ContrastTables.PRE_CONTRAST_MAP[(int) (ImageMath.clamp(v) * 255)] / 255f,
              b = ContrastTables.PRE_CONTRAST_MAP[(int) (ImageMath.clamp(reference) * 255)] / 255f;
          float value = ImageMath.clamp(a / Math.max(1e-7f, b));
          if (useBlack) {
            float bp = ImageMath.field(black, 20, 20, 4, fx * 19, fy * 19, c);
            value = ImageMath.clamp((value - bp) / Math.max(1e-7f, 1 - bp));
          }
          rgb[c] = ContrastTables.POST_CONTRAST_MAP[(int) (value * 255)] / 255f;
        }
        if (.299f * rgb[0] + .587f * rgb[1] + .114f * rgb[2] > 205f / 255)
          for (int c = 0; c < 3; c++) rgb[c] = ImageMath.clamp(rgb[c] * 255f / 230);
        out[y * w + x] = ImageMath.rgb(rgb[0], rgb[1], rgb[2]);
      }
    }
    return ImageMath.bitmap(out, w, h);
  }

  private Bitmap removeFingers(Bitmap source, Bitmap maskSource, StringBuilder debug)
      throws IOException {
    float[] input = ImageMath.input(maskSource, 320, 320, false),
        gray = ImageMath.input(maskSource, 320, 320, true);
    float[] content = runner.run("content_mask_v2", gray),
        mask = runner.run("finger_mask_v1_1", input);
    ByteBuffer finger = ModelRunner.buffer(mask.length * 4),
        text = ModelRunner.buffer(content.length * 4);
    finger.asFloatBuffer().put(mask);
    text.asFloatBuffer().put(content);
    NativeScan.adjustFingerMask(320, 320, finger, text);
    finger.asFloatBuffer().get(mask);
    float[] pixels = ImageMath.input(source, 320, 320, false);
    ByteBuffer rgba = ModelRunner.buffer(pixels.length * 4);
    rgba.asFloatBuffer().put(pixels);
    ByteBuffer color = ModelRunner.buffer(16);
    NativeScan.getInpaintColor(320, 320, 4, rgba, text, finger, color);
    float r = ImageMath.clamp(color.getFloat(0)),
        g = ImageMath.clamp(color.getFloat(4)),
        b = ImageMath.clamp(color.getFloat(8));
    boolean white = r * 255 >= 250 && g * 255 >= 250 && b * 255 >= 250;
    float[] repaired = null;
    if (!white) {
      NativeScan.prepareInpaintInput(320, 320, rgba, finger);
      rgba.asFloatBuffer().get(pixels);
      repaired = runner.run("inpainter_v1_0_0", pixels);
    }
    int w = source.getWidth(), h = source.getHeight(), count = 0;
    int[] out = ImageMath.pixels(source);
    for (int y = 0; y < h; y++) {
      if (y % 32 == 0) checkCancelled();
      float fy = (float) y / (h - 1) * 319;
      for (int x = 0; x < w; x++) {
        float fx = (float) x / (w - 1) * 319;
        if (ImageMath.field(mask, 320, 320, 1, fx, fy, 0) > .9f) {
          count++;
          out[y * w + x] =
              white
                  ? ImageMath.rgb(r, g, b)
                  : ImageMath.rgb(
                      ImageMath.field(repaired, 320, 320, 4, fx, fy, 0),
                      ImageMath.field(repaired, 320, 320, 4, fx, fy, 1),
                      ImageMath.field(repaired, 320, 320, 4, fx, fy, 2));
        }
      }
    }
    debug
        .append("finger pixels=")
        .append(count)
        .append(" white=")
        .append(white)
        .append(" paper=")
        .append(r)
        .append(',')
        .append(g)
        .append(',')
        .append(b)
        .append('\n');
    return ImageMath.bitmap(out, w, h);
  }

  private Bitmap handwriting(Bitmap source, Progress progress) throws IOException {
    int w = source.getWidth(),
        h = source.getHeight(),
        pw = ((w + 479) / 480) * 480,
        ph = ((h + 479) / 480) * 480;
    Bitmap padded = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(padded);
    canvas.drawColor(Color.WHITE);
    canvas.drawBitmap(source, 0, 0, null);
    Bitmap out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
    Canvas dest = new Canvas(out);
    int completed = 0, total = (pw / 480) * (ph / 480);
    for (int y = 0; y < ph; y += 480)
      for (int x = 0; x < pw; x += 480) {
        checkCancelled();
        Bitmap tile = Bitmap.createBitmap(padded, x, y, 480, 480);
        float[] prediction =
            runner.run("handwriting_removal_v2_0_2", ImageMath.input(tile, 480, 480, false));
        int[] pixels = new int[480 * 480];
        for (int i = 0; i < pixels.length; i++)
          pixels[i] =
              ImageMath.rgb(prediction[i * 4], prediction[i * 4 + 1], prediction[i * 4 + 2]);
        Bitmap processed = ImageMath.bitmap(pixels, 480, 480);
        dest.drawBitmap(processed, x, y, null);
        tile.recycle();
        processed.recycle();
        progress.update("手写消除 " + (++completed) + "/" + total, 85 + completed * 14 / total);
      }
    padded.recycle();
    return Bitmap.createBitmap(out, 0, 0, w, h);
  }
}
