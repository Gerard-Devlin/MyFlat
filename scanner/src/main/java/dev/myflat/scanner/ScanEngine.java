package dev.myflat.scanner;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Local scan pipeline with native mesh decoding, tiled rasterization and compositing. */
public final class ScanEngine {
  public static final class Options {
    public boolean dewarp = true,
        enhance = true,
        fingers = true,
        straighten = false,
        handwriting = false;
    public int maxEdge = 0;
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

  public Result process(ScanSource source, Options options, Progress progress, Observer observer)
      throws IOException {
    long started = SystemClock.elapsedRealtime();
    Map<String, Long> times = new LinkedHashMap<>();
    StringBuilder debug = new StringBuilder();
    Bitmap preview = source.preview(1600), workingPreview = preview;
    int outputWidth = source.width, outputHeight = source.height;
    Warp warp = null;
    progress.update("准备图像", 5);
    checkCancelled();
    if (options.dewarp) {
      warp = plan(preview, source.width, source.height, debug);
      outputWidth = warp.width;
      outputHeight = warp.height;
      int pw =
          Math.max(
              1,
              Math.round(outputWidth * Math.min(1f, 1600f / Math.max(outputWidth, outputHeight))));
      int ph =
          Math.max(
              1,
              Math.round(outputHeight * Math.min(1f, 1600f / Math.max(outputWidth, outputHeight))));
      Warp planned = warp;
      int ow = outputWidth, oh = outputHeight;
      workingPreview =
          ImageMath.raster(
              preview,
              pw,
              ph,
              points -> {
                for (int i = 0; i < points.length; i += 2) {
                  points[i] = points[i] / Math.max(1, pw - 1) * (ow - 1);
                  points[i + 1] = points[i + 1] / Math.max(1, ph - 1) * (oh - 1);
                }
                planned.map(points);
                for (int i = 0; i < points.length; i += 2) {
                  points[i] *= (float) preview.getWidth() / source.width;
                  points[i + 1] *= (float) preview.getHeight() / source.height;
                }
              });
    }
    progress.update("页面展平", 25);
    checkCancelled();
    float[] straight =
        options.straighten
            ? runner.run("pptp_v1_1_0", ImageMath.input(workingPreview, 320, 320, false))
            : null;
    if (straight != null) {
      Bitmap previous = workingPreview;
      workingPreview =
          remap(previous, straight, 320, 320, previous.getWidth(), previous.getHeight());
      if (previous != preview) previous.recycle();
    }
    float[] fingerInput = options.fingers ? ImageMath.input(workingPreview, 320, 320, false) : null;
    if (workingPreview != preview) workingPreview.recycle();
    preview.recycle();
    Warp planned = warp;
    int ow = outputWidth, oh = outputHeight;
    long rasterStart = SystemClock.elapsedRealtime();
    Bitmap current =
        source.render(
            outputWidth,
            outputHeight,
            points -> {
              if (straight != null)
                for (int i = 0; i < points.length; i += 2) {
                  float gx = points[i] / Math.max(1, ow - 1) * 319,
                      gy = points[i + 1] / Math.max(1, oh - 1) * 319;
                  points[i] = ImageMath.field(straight, 320, 320, 2, gx, gy, 0) * ow - .5f;
                  points[i + 1] = ImageMath.field(straight, 320, 320, 2, gx, gy, 1) * oh - .5f;
                }
              if (planned != null) planned.map(points);
            });
    if (options.dewarp) current = ImageMath.sharpen(current);
    times.put("raster", SystemClock.elapsedRealtime() - rasterStart);
    emit(observer, "01-dewarp", current);
    progress.update("色彩增强", 45);
    checkCancelled();
    if (options.enhance) {
      current = enhance(current);
      emit(observer, "03-enhance", current);
    }
    progress.update("手指消除", 65);
    checkCancelled();
    if (options.fingers) {
      current = removeFingers(current, fingerInput, debug);
      emit(observer, "04-fingers", current);
    }
    progress.update("手写消除", 85);
    checkCancelled();
    if (options.handwriting) {
      current = handwriting(current, progress);
      emit(observer, "05-handwriting", current);
    }
    times.put("total", SystemClock.elapsedRealtime() - started);
    progress.update("处理完成", 100);
    return new Result(current, times, debug.toString());
  }

  public Result process(Bitmap original, Options options, Progress progress, Observer observer)
      throws IOException {
    Map<String, Long> times = new LinkedHashMap<>();
    StringBuilder debug = new StringBuilder();
    long start = SystemClock.elapsedRealtime();
    Bitmap current = limit(original, options.maxEdge);
    if (current == original
        && !options.dewarp
        && !options.straighten
        && (options.enhance || options.fingers || options.handwriting))
      current = original.copy(Bitmap.Config.ARGB_8888, true);
    checkCancelled();
    progress.update("准备图像", 5);
    if (options.dewarp) {
      long t = SystemClock.elapsedRealtime();
      Bitmap previous = current;
      current = dewarp(current, debug);
      release(previous, current, original);
      times.put("dewarp", SystemClock.elapsedRealtime() - t);
      emit(observer, "01-dewarp", current);
    }
    progress.update("页面展平", 25);
    checkCancelled();
    if (options.straighten) {
      long t = SystemClock.elapsedRealtime();
      Bitmap previous = current;
      current = straighten(current);
      release(previous, current, original);
      times.put("straighten", SystemClock.elapsedRealtime() - t);
      emit(observer, "02-straighten", current);
    }
    progress.update("色彩增强", 45);
    checkCancelled();
    // Masks in the reference pipeline use the pre-enhancement image to protect printed content.
    float[] fingerInput = options.fingers ? ImageMath.input(current, 320, 320, false) : null;
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
      current = removeFingers(current, fingerInput, debug);
      times.put("fingers", SystemClock.elapsedRealtime() - t);
      emit(observer, "04-fingers", current);
    }
    progress.update("手写消除", 85);
    checkCancelled();
    if (options.handwriting) {
      long t = SystemClock.elapsedRealtime();
      Bitmap previous = current;
      current = handwriting(current, progress);
      release(previous, current, original);
      times.put("handwriting", SystemClock.elapsedRealtime() - t);
      emit(observer, "05-handwriting", current);
    }
    times.put("total", SystemClock.elapsedRealtime() - start);
    progress.update("处理完成", 100);
    return new Result(current, times, debug.toString());
  }

  private static void release(Bitmap before, Bitmap after, Bitmap original) {
    if (before != after && before != original && !before.isRecycled()) before.recycle();
  }

  private static void emit(Observer o, String stage, Bitmap b) throws IOException {
    if (o != null) o.image(stage, b);
  }

  private static void checkCancelled() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消处理");
  }

  private static Bitmap limit(Bitmap b, int max) {
    int edge = Math.max(b.getWidth(), b.getHeight());
    if (max <= 0 || edge <= max) return b;
    float scale = (float) max / edge;
    return Bitmap.createScaledBitmap(
        b,
        Math.max(1, Math.round(b.getWidth() * scale)),
        Math.max(1, Math.round(b.getHeight() * scale)),
        true);
  }

  private static final class Warp {
    int width, height, paddedWidth, paddedHeight, dx, dy;
    float[] grid;

    void map(float[] points) {
      for (int i = 0; i < points.length; i += 2) {
        float gx = points[i] / Math.max(1, width - 1) * 63,
            gy = points[i + 1] / Math.max(1, height - 1) * 63;
        points[i] = ImageMath.field(grid, 64, 64, 2, gx, gy, 0) * paddedWidth - .5f - dx;
        points[i + 1] = ImageMath.field(grid, 64, 64, 2, gx, gy, 1) * paddedHeight - .5f - dy;
      }
    }
  }

  private Warp plan(Bitmap preview, int sourceWidth, int sourceHeight, StringBuilder debug)
      throws IOException {
    Bitmap padded = ImageMath.pad(preview);
    float[] prediction;
    try {
      prediction = runner.run("freeform_v3_2", ImageMath.input(padded, 144, 192, false));
    } finally {
      if (padded != preview) padded.recycle();
    }
    ByteBuffer model = ModelRunner.buffer(5004);
    model.asFloatBuffer().put(prediction);
    NativeScan.dof(model);
    Warp warp = new Warp();
    warp.paddedWidth = Math.max(sourceWidth, Math.round(sourceHeight * .75f));
    warp.paddedHeight = Math.max(sourceHeight, Math.round(sourceWidth / .75f));
    warp.dx = (warp.paddedWidth - sourceWidth) / 2;
    warp.dy = (warp.paddedHeight - sourceHeight) / 2;
    ByteBuffer size = ModelRunner.buffer(8);
    NativeScan.calcFreeformOutputSize(warp.paddedWidth, model, size);
    int w = size.getInt(0), h = size.getInt(4);
    if (w < 16 || h < 16 || w > 65535 || h > 65535 || (long) w * h > Integer.MAX_VALUE / 4)
      throw new IOException("展平模型返回无效尺寸：" + w + "×" + h);
    warp.width = w;
    warp.height = h;
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
        .append(warp.paddedWidth)
        .append('x')
        .append(warp.paddedHeight)
        .append(" output=")
        .append(w)
        .append('x')
        .append(h)
        .append(" uv corners=");
    for (int i : new int[] {0, 63, 4032, 4095})
      debug.append(Arrays.toString(new float[] {grid[i * 2], grid[i * 2 + 1]}));
    debug.append('\n');
    warp.grid = grid;
    return warp;
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
    if (w < 16 || h < 16 || w > 65535 || h > 65535 || (long) w * h > Integer.MAX_VALUE / 4)
      throw new IOException("展平模型返回无效尺寸：" + w + "×" + h);
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
    try {
      return ImageMath.sharpen(remap(padded, grid, 64, 64, w, h));
    } finally {
      if (padded != source) padded.recycle();
    }
  }

  private static Bitmap remap(Bitmap source, float[] grid, int gw, int gh, int w, int h)
      throws IOException {
    int sw = source.getWidth(), sh = source.getHeight();
    return ImageMath.raster(
        source,
        w,
        h,
        points -> {
          for (int i = 0; i < points.length; i += 2) {
            float gx = points[i] / Math.max(1, w - 1) * (gw - 1),
                gy = points[i + 1] / Math.max(1, h - 1) * (gh - 1);
            points[i] = ImageMath.field(grid, gw, gh, 2, gx, gy, 0) * sw - .5f;
            points[i + 1] = ImageMath.field(grid, gw, gh, 2, gx, gy, 1) * sh - .5f;
          }
        });
  }

  private Bitmap straighten(Bitmap source) throws IOException {
    float[] grid = runner.run("pptp_v1_1_0", ImageMath.input(source, 320, 320, false));
    return remap(source, grid, 320, 320, source.getWidth(), source.getHeight());
  }

  /** Paper-color field for local cleanup; the source is never changed. */
  public Bitmap paperBackground(Bitmap source) throws IOException {
    checkCancelled();
    float[] background = runner.run("enhance_v10", ImageMath.input(source, 320, 320, false));
    int[] pixels = new int[320 * 320];
    for (int i = 0; i < pixels.length; i++)
      pixels[i] = ImageMath.rgb(background[i * 4], background[i * 4 + 1], background[i * 4 + 2]);
    return ImageMath.bitmap(pixels, 320, 320);
  }

  /** Removes handwriting from the current full-resolution page, without rerunning its crop. */
  public Bitmap removeHandwriting(Bitmap source, Progress progress) throws IOException {
    return handwriting(source, progress);
  }

  private Bitmap enhance(Bitmap source) throws IOException {
    float[] in = ImageMath.input(source, 320, 320, false), dist = runner.run("enhance_v10", in);
    boolean useBlack = runner.run("blackpoint_classification_v1_0_1", in)[0] < 0;
    float[] black = useBlack ? runner.run("blackpoint_v1_0_1", in) : null;
    int w = source.getWidth(), h = source.getHeight();
    if (!source.isMutable()) source = source.copy(Bitmap.Config.ARGB_8888, true);
    int[] p = new int[w], out = new int[w];
    float[] rgb = new float[3], d = new float[4];
    for (int y = 0; y < h; y++) {
      if (y % 32 == 0) checkCancelled();
      source.getPixels(p, 0, w, 0, y, w, 1);
      float fy = (float) y / Math.max(1, h - 1);
      for (int x = 0; x < w; x++) {
        float fx = (float) x / (w - 1);
        int color = p[x];
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
        out[x] = ImageMath.rgb(rgb[0], rgb[1], rgb[2]);
      }
      source.setPixels(out, 0, w, 0, y, w, 1);
    }
    return source;
  }

  private Bitmap removeFingers(Bitmap source, float[] input, StringBuilder debug)
      throws IOException {
    float[] gray = new float[320 * 320];
    for (int i = 0; i < gray.length; i++)
      gray[i] = input[i * 4] * .299f + input[i * 4 + 1] * .587f + input[i * 4 + 2] * .114f;
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
    if (!source.isMutable()) source = source.copy(Bitmap.Config.ARGB_8888, true);
    int[] out = new int[w];
    for (int y = 0; y < h; y++) {
      if (y % 32 == 0) checkCancelled();
      source.getPixels(out, 0, w, 0, y, w, 1);
      float fy = (float) y / Math.max(1, h - 1) * 319;
      for (int x = 0; x < w; x++) {
        float fx = (float) x / (w - 1) * 319;
        if (ImageMath.field(mask, 320, 320, 1, fx, fy, 0) > .9f) {
          count++;
          out[x] =
              white
                  ? ImageMath.rgb(r, g, b)
                  : ImageMath.rgb(
                      ImageMath.field(repaired, 320, 320, 4, fx, fy, 0),
                      ImageMath.field(repaired, 320, 320, 4, fx, fy, 1),
                      ImageMath.field(repaired, 320, 320, 4, fx, fy, 2));
        }
      }
      source.setPixels(out, 0, w, 0, y, w, 1);
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
    return source;
  }

  private Bitmap handwriting(Bitmap source, Progress progress) throws IOException {
    int w = source.getWidth(), h = source.getHeight();
    if (!source.isMutable()) source = source.copy(Bitmap.Config.ARGB_8888, true);
    Canvas destination = new Canvas(source);
    int completed = 0, total = ((w + 479) / 480) * ((h + 479) / 480);
    for (int y = 0; y < h; y += 480)
      for (int x = 0; x < w; x += 480) {
        checkCancelled();
        Bitmap tile = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tile);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, -x, -y, null);
        float[] prediction =
            runner.run("handwriting_removal_v2_0_2", ImageMath.input(tile, 480, 480, false));
        int[] pixels = new int[480 * 480];
        for (int i = 0; i < pixels.length; i++)
          pixels[i] =
              ImageMath.rgb(prediction[i * 4], prediction[i * 4 + 1], prediction[i * 4 + 2]);
        tile.setPixels(pixels, 0, 480, 0, 0, 480, 480);
        destination.drawBitmap(tile, x, y, null);
        tile.recycle();
        progress.update("手写消除 " + (++completed) + "/" + total, 85 + completed * 14 / total);
      }
    return source;
  }
}
