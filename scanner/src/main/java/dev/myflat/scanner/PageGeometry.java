package dev.myflat.scanner;

/** Normalized 25 by 25 page mesh and camera guidance, independent of Android views. */
public final class PageGeometry {
  public enum State {
    NORMAL,
    TILTED,
    TOO_CLOSE,
    OUT_OF_BOUNDS,
    PAGE_INVALID,
    MISALIGNED_GUTTER
  }

  private PageGeometry() {}

  public static State assess(float[] mesh, float pitch, float contrast, float frameAspect) {
    if (mesh.length != 1250 || !Float.isFinite(pitch) || contrast < .3f) return State.PAGE_INVALID;
    float left = Float.POSITIVE_INFINITY, top = left;
    float right = Float.NEGATIVE_INFINITY, bottom = right;
    for (int i = 0; i < mesh.length; i += 2) {
      if (!Float.isFinite(mesh[i]) || !Float.isFinite(mesh[i + 1])) return State.PAGE_INVALID;
      left = Math.min(left, mesh[i]);
      right = Math.max(right, mesh[i]);
      top = Math.min(top, mesh[i + 1]);
      bottom = Math.max(bottom, mesh[i + 1]);
    }
    float width = right - left, height = bottom - top;
    float ratio = height / (width * frameAspect);
    if (width < .08f || height < .08f || ratio < .3f || ratio > 3f) return State.PAGE_INVALID;
    // Reject folded or reversed predictions rather than drawing a plausible-looking fake box.
    for (int y = 0; y < 24; y++)
      for (int x = 0; x < 24; x++) {
        int q = (y * 25 + x) * 2;
        float ax = mesh[q + 2] - mesh[q], ay = mesh[q + 3] - mesh[q + 1];
        float bx = mesh[q + 50] - mesh[q], by = mesh[q + 51] - mesh[q + 1];
        if (ax * by - ay * bx <= 0) return State.PAGE_INVALID;
      }
    if (Math.abs(pitch) > 20) return State.TILTED;
    if (width > .9f || height > .9f) return State.TOO_CLOSE;
    if (left < 0 || top < 0 || right > 1 || bottom > 1) return State.OUT_OF_BOUNDS;
    return State.NORMAL;
  }

  /** Clockwise perimeter, retaining the page's curved edges. */
  public static float[] outline(float[] mesh) {
    float[] edge = new float[192];
    int n = 0;
    for (int x = 0; x < 24; x++) n = copy(mesh, x, edge, n);
    for (int y = 0; y < 24; y++) n = copy(mesh, y * 25 + 24, edge, n);
    for (int x = 24; x > 0; x--) n = copy(mesh, 600 + x, edge, n);
    for (int y = 24; y > 0; y--) n = copy(mesh, y * 25, edge, n);
    return edge;
  }

  private static int copy(float[] mesh, int point, float[] edge, int n) {
    edge[n++] = mesh[point * 2];
    edge[n++] = mesh[point * 2 + 1];
    return n;
  }
}
