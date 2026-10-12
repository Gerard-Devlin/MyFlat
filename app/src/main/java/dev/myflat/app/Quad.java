package dev.myflat.app;

/** Pure geometry so invalid crossing/degenerate perspective crops can be tested on the JVM. */
final class Quad {
  private Quad() {}

  static boolean valid(float[] p, float minimumEdge) {
    if (p == null || p.length != 8) return false;
    for (float value : p) if (!Float.isFinite(value)) return false;
    for (int i = 0; i < 4; i++) {
      int a = i * 2, b = ((i + 1) % 4) * 2, c = ((i + 2) % 4) * 2;
      if (Math.hypot(p[a] - p[b], p[a + 1] - p[b + 1]) < minimumEdge) return false;
      float cross = (p[b] - p[a]) * (p[c + 1] - p[b + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[b]);
      if (cross <= 1e-3f) return false;
    }
    return true;
  }
}
