package dev.myflat.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
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
  private Mode mode = Mode.VIEW;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Matrix matrix = new Matrix(), inverse = new Matrix();
  private final float[] corners = new float[8], screenCorners = new float[8];
  private final Path border = new Path(), dim = new Path();
  private float scale = 1, base = 1, panX, panY, lastX, lastY, brush = 16;
  private int handle = -1;
  private final ScaleGestureDetector pinch;
  private final GestureDetector gestures;
  private final ArrayDeque<Bitmap> undo = new ArrayDeque<>();

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
                scale = Math.max(1, Math.min(6, scale * detector.getScaleFactor()));
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
    if (value == Mode.ERASE && bitmap != null) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
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
      bitmap = undo.removeLast();
      invalidate();
    }
  }

  private void clearUndo() {
    undo.clear();
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
    new Canvas(bitmap).drawCircle(point[0], point[1], brush / (base * scale), paint);
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
          if (undo.size() >= 2) undo.removeFirst();
          undo.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, true));
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
