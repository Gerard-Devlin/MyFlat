package dev.myflat.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import androidx.exifinterface.media.ExifInterface;
import dev.myflat.scanner.ScanSource;
import java.io.File;
import java.util.ArrayDeque;

/** Zoomable page, perspective handles and an undoable white-paper brush. */
final class PageCanvas extends View {
  enum Mode {
    VIEW,
    CROP,
    ERASE
  }

  interface Swipe {
    void accept(int direction);
  }

  Swipe onSwipe;
  private float downX, downY;
  private boolean multiTouch;
  private Bitmap bitmap;
  private boolean ownsBitmap;
  private File fullFile;
  private Bitmap detail;
  private RectF detailBounds, requestedBounds;
  private float requestedScale;
  private float maximumScale = 24;
  private boolean tileBusy;
  private int imageGeneration;
  private static final java.util.concurrent.ExecutorService DETAIL_IO =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  void fullResolution(File file) {
    fullFile = file;
    imageGeneration++;
    requestedBounds = null;
    clearDetail();
    invalidate();
  }

  private void clearDetail() {
    if (detail != null) {
      detail.recycle();
      detail = null;
    }
    detailBounds = null;
  }

  private void drawDetail(Canvas target) {
    if (mode != Mode.VIEW || fullFile == null || scale <= 1.05f) return;
    if (detail != null && detailBounds != null) {
      RectF destination = new RectF(detailBounds);
      matrix.mapRect(destination);
      target.drawBitmap(detail, null, destination, paint);
    }
    RectF visible = new RectF(0, 0, getWidth(), getHeight());
    inverse.mapRect(visible);
    if (!visible.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) return;
    if (tileBusy
        || requestedBounds != null
            && requestedBounds.equals(visible)
            && Math.abs(requestedScale - base * scale) < .001f) return;
    requestedBounds = new RectF(visible);
    requestedScale = base * scale;
    int generation = imageGeneration;
    float previewWidth = bitmap.getWidth(), previewBase = base;
    File file = fullFile;
    RectF normalized =
        new RectF(
            visible.left / bitmap.getWidth(),
            visible.top / bitmap.getHeight(),
            visible.right / bitmap.getWidth(),
            visible.bottom / bitmap.getHeight());
    int screenWidth = Math.max(1, Math.round(visible.width() * base * scale));
    int screenHeight = Math.max(1, Math.round(visible.height() * base * scale));
    tileBusy = true;
    DETAIL_IO.submit(
        () -> {
          Bitmap tile = null;
          float sourceScale = maximumScale;
          try {
            int exif = new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            try (ScanSource source = new ScanSource(file, exif, 0, 0, 0, 1, 1, 0)) {
              sourceScale = Math.max(6, source.width / (previewWidth * previewBase));
              int w =
                  Math.max(1, Math.min(screenWidth, Math.round(normalized.width() * source.width)));
              int h =
                  Math.max(
                      1, Math.min(screenHeight, Math.round(normalized.height() * source.height)));
              tile =
                  source.render(
                      w,
                      h,
                      points -> {
                        for (int i = 0; i < points.length; i += 2) {
                          points[i] =
                              (normalized.left + (points[i] + .5f) / w * normalized.width())
                                      * source.width
                                  - .5f;
                          points[i + 1] =
                              (normalized.top + (points[i + 1] + .5f) / h * normalized.height())
                                      * source.height
                                  - .5f;
                        }
                      });
            }
          } catch (Exception ex) {
            android.util.Log.w("MyFlat", "Page detail unavailable", ex);
          }
          Bitmap ready = tile;
          float readyScale = sourceScale;
          post(
              () -> {
                tileBusy = false;
                if (generation != imageGeneration || !isAttachedToWindow()) {
                  if (ready != null) ready.recycle();
                  invalidate();
                  return;
                }
                maximumScale = readyScale;
                clearDetail();
                detail = ready;
                detailBounds = new RectF(visible);
                invalidate();
              });
        });
  }

  @Override
  protected void onDetachedFromWindow() {
    imageGeneration++;
    fullFile = null;
    clearDetail();
    clearUndo();
    if (ownsBitmap && bitmap != null) {
      bitmap.recycle();
      bitmap = null;
      ownsBitmap = false;
    }
    super.onDetachedFromWindow();
  }

  private Mode mode = Mode.VIEW;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Matrix matrix = new Matrix(), inverse = new Matrix();
  private final float[] corners = new float[8], screenCorners = new float[8];
  private final Path border = new Path(), dim = new Path();
  private float scale = 1, base = 1, panX, panY, lastX, lastY, brush = 16;
  private int handle = -1;
  private boolean rectangle;
  private final ScaleGestureDetector pinch;
  private final GestureDetector gestures;
  private final ArrayDeque<Bitmap> undo = new ArrayDeque<>();
  private final java.util.ArrayList<Stroke> strokes = new java.util.ArrayList<>();

  private static final class Stroke {
    float radius;
    java.util.ArrayList<PointF> points = new java.util.ArrayList<>();
  }

  void applyBrushes(Bitmap full) {
    Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
    brush.setColor(Color.WHITE);
    Canvas target = new Canvas(full);
    for (Stroke stroke : strokes)
      for (PointF p : stroke.points)
        target.drawCircle(
            p.x * full.getWidth(), p.y * full.getHeight(), stroke.radius * full.getWidth(), brush);
  }

  PageCanvas(Context context) {
    super(context);
    setBackgroundColor(Ui.PAPER);
    setContentDescription("扫描页面，可双指缩放或拖动裁剪角点");
    pinch =
        new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                scale = Math.max(1, Math.min(maximumScale, scale * detector.getScaleFactor()));
                invalidate();
                return true;
              }
            });
    gestures =
        new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDoubleTap(android.view.MotionEvent e) {
                scale = scale > 1 ? 1 : 2.5f;
                panX = panY = 0;
                invalidate();
                return true;
              }
            });
  }

  void image(Bitmap value) {
    if (ownsBitmap && bitmap != null && bitmap != value) bitmap.recycle();
    ownsBitmap = false;
    fullFile = null;
    imageGeneration++;
    requestedBounds = null;
    clearDetail();
    bitmap = value;
    scale = 1;
    panX = panY = 0;
    resetCorners();
    clearUndo();
    invalidate();
  }

  Bitmap image() {
    return bitmap;
  }

  void mode(Mode value) {
    mode = value;
    setBackgroundColor(value == Mode.CROP ? 0xffe0e0e0 : 0xffeeeeee);
    if (value == Mode.CROP) resetCorners();
    if (value == Mode.ERASE && bitmap != null && !ownsBitmap) {
      bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
      ownsBitmap = true;
    }
    invalidate();
  }

  Mode mode() {
    return mode;
  }

  void brush(float value) {
    brush = value;
  }

  void resetCorners() {
    if (bitmap != null) {
      float w = bitmap.getWidth() - 1, h = bitmap.getHeight() - 1;
      float[] p = {0, 0, w, 0, w, h, 0, h};
      System.arraycopy(p, 0, corners, 0, 8);
    }
  }

  void ratio(float ratio) {
    rectangle = ratio == -1;
    resetCorners();
    if (bitmap == null || ratio <= 0) {
      invalidate();
      return;
    }
    float w = bitmap.getWidth() - 1, h = bitmap.getHeight() - 1;
    float cw = Math.min(w, h * ratio), ch = cw / ratio, x = (w - cw) / 2, y = (h - ch) / 2;
    float[] p = {x, y, x + cw, y, x + cw, y + ch, x, y + ch};
    System.arraycopy(p, 0, corners, 0, 8);
    invalidate();
  }

  float[] corners() {
    return corners.clone();
  }

  void undo() {
    if (!undo.isEmpty()) {
      if (ownsBitmap) bitmap.recycle();
      bitmap = undo.removeLast();
      if (!strokes.isEmpty()) strokes.remove(strokes.size() - 1);
      invalidate();
    }
  }

  private void clearUndo() {
    for (Bitmap old : undo) old.recycle();
    undo.clear();
    strokes.clear();
  }

  private void transform() {
    if (bitmap == null) return;
    float margin = Ui.dp(getContext(), mode == Mode.CROP ? 48 : mode == Mode.ERASE ? 16 : 0);
    base =
        Math.min(
            (getWidth() - 2 * margin) / bitmap.getWidth(),
            (getHeight() - 2 * margin) / bitmap.getHeight());
    float factor = base * scale;
    matrix.reset();
    matrix.setScale(factor, factor);
    matrix.postTranslate(
        (getWidth() - bitmap.getWidth() * factor) / 2 + panX,
        (getHeight() - bitmap.getHeight() * factor) / 2 + panY);
    matrix.invert(inverse);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (bitmap == null) return;
    transform();
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.WHITE);
    canvas.drawBitmap(bitmap, matrix, paint);
    drawDetail(canvas);
    if (mode == Mode.CROP) {
      System.arraycopy(corners, 0, screenCorners, 0, 8);
      float[] p = screenCorners;
      matrix.mapPoints(p);
      border.reset();
      border.moveTo(p[0], p[1]);
      for (int i = 2; i < 8; i += 2) border.lineTo(p[i], p[i + 1]);
      border.close();
      dim.reset();
      dim.setFillType(Path.FillType.EVEN_ODD);
      RectF imageBounds = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
      matrix.mapRect(imageBounds);
      dim.addRect(imageBounds, Path.Direction.CW);
      dim.addPath(border);
      paint.setColor(0x22000000);
      canvas.drawPath(dim, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Ui.dp(getContext(), 1));
      paint.setColor(0xffffaa00);
      canvas.drawPath(border, paint);
      for (int i = 0; i < 8; i += 2) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffffaa00);
        canvas.drawCircle(p[i], p[i + 1], Ui.dp(getContext(), 4), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xffffaa00);
        canvas.drawCircle(p[i], p[i + 1], Ui.dp(getContext(), 4), paint);
      }
    }
  }

  private void erase(float x, float y) {
    float[] point = {x, y};
    inverse.mapPoints(point);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.WHITE);
    float radius = brush / (base * scale);
    if (!strokes.isEmpty()) {
      Stroke stroke = strokes.get(strokes.size() - 1);
      stroke.radius = radius / bitmap.getWidth();
      stroke.points.add(new PointF(point[0] / bitmap.getWidth(), point[1] / bitmap.getHeight()));
    }
    new Canvas(bitmap).drawCircle(point[0], point[1], radius, paint);
    invalidate();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (bitmap == null) return false;
    if (event.getPointerCount() > 1) multiTouch = true;
    pinch.onTouchEvent(event);
    gestures.onTouchEvent(event);
    float x = event.getX(), y = event.getY();
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        multiTouch = false;
        downX = x;
        downY = y;
        lastX = x;
        lastY = y;
        handle = -1;
        transform();
        if (mode == Mode.CROP) {
          float[] p = corners.clone();
          matrix.mapPoints(p);
          float best = Ui.dp(getContext(), 42);
          for (int i = 0; i < 8; i += 2) {
            float d = (float) Math.hypot(p[i] - x, p[i + 1] - y);
            if (d < best) {
              best = d;
              handle = i;
            }
          }
        }
        if (mode == Mode.ERASE) {
          if (undo.size() >= 2) undo.removeFirst().recycle();
          undo.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, true));
          strokes.add(new Stroke());
          erase(x, y);
        }
        return true;
      case MotionEvent.ACTION_MOVE:
        if (event.getPointerCount() > 1) return true;
        if (handle >= 0) {
          float[] point = {x, y};
          inverse.mapPoints(point);
          float[] proposed = corners.clone();
          proposed[handle] = Math.max(0, Math.min(bitmap.getWidth() - 1, point[0]));
          proposed[handle + 1] = Math.max(0, Math.min(bitmap.getHeight() - 1, point[1]));
          if (rectangle) {
            int opposite = (handle + 4) % 8;
            float ox = corners[opposite], oy = corners[opposite + 1];
            float px = proposed[handle], py = proposed[handle + 1];
            float l = Math.min(ox, px),
                r = Math.max(ox, px),
                t = Math.min(oy, py),
                b = Math.max(oy, py);
            proposed = new float[] {l, t, r, t, r, b, l, b};
          }
          if (Quad.valid(proposed, 24)) System.arraycopy(proposed, 0, corners, 0, 8);
        } else if (mode == Mode.ERASE) {
          float distance = (float) Math.hypot(x - lastX, y - lastY);
          int steps = Math.max(1, (int) (distance / Math.max(1, brush / 3)));
          for (int i = 1; i <= steps; i++)
            erase(lastX + (x - lastX) * i / steps, lastY + (y - lastY) * i / steps);
        } else if (scale > 1) {
          panX += x - lastX;
          panY += y - lastY;
        }
        lastX = x;
        lastY = y;
        invalidate();
        return true;
      case MotionEvent.ACTION_UP:
        if (mode == Mode.VIEW
            && !multiTouch
            && scale == 1
            && Math.abs(x - downX) > Ui.dp(getContext(), 64)
            && Math.abs(y - downY) < Ui.dp(getContext(), 96)
            && onSwipe != null) onSwipe.accept(x < downX ? 1 : -1);
        handle = -1;
        performClick();
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
