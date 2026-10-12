package dev.myflat.scanner;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PageGeometryTest {
  private float[] page(float left, float top, float right, float bottom) {
    float[] mesh = new float[1250];
    for (int y = 0; y < 25; y++)
      for (int x = 0; x < 25; x++) {
        int i = (y * 25 + x) * 2;
        mesh[i] = left + (right - left) * x / 24;
        mesh[i + 1] = top + (bottom - top) * y / 24;
      }
    return mesh;
  }

  @Test
  public void identifiesAlignedPage() {
    assertEquals(
        PageGeometry.State.NORMAL, PageGeometry.assess(page(.2f, .15f, .8f, .85f), 0, .8f, .75f));
  }

  @Test
  public void tiltTakesPriorityOverDistance() {
    assertEquals(
        PageGeometry.State.TILTED,
        PageGeometry.assess(page(.02f, .02f, .98f, .98f), 25, .8f, .75f));
  }

  @Test
  public void detectsDistanceAndMissingCorners() {
    assertEquals(
        PageGeometry.State.TOO_CLOSE,
        PageGeometry.assess(page(.02f, .02f, .98f, .98f), 0, .8f, .75f));
    assertEquals(
        PageGeometry.State.OUT_OF_BOUNDS,
        PageGeometry.assess(page(-.05f, .2f, .65f, .8f), 0, .8f, .75f));
  }

  @Test
  public void rejectsBlankFramesAndFoldedMeshes() {
    assertEquals(
        PageGeometry.State.PAGE_INVALID,
        PageGeometry.assess(page(.2f, .15f, .8f, .85f), 0, .1f, .75f));
    float[] folded = page(.2f, .15f, .8f, .85f);
    folded[52] = -.5f;
    assertEquals(PageGeometry.State.PAGE_INVALID, PageGeometry.assess(folded, 0, .8f, .75f));
    folded[0] = Float.NaN;
    assertEquals(PageGeometry.State.PAGE_INVALID, PageGeometry.assess(folded, 0, .8f, .75f));
  }

  @Test
  public void outlineRetainsCurvesAndCornerOrder() {
    float[] mesh = page(.2f, .15f, .8f, .85f);
    mesh[24 + 1] = .12f;
    float[] edge = PageGeometry.outline(mesh);
    assertEquals(192, edge.length);
    assertEquals(.12f, edge[25], .0001f);
    assertArrayEquals(
        new float[] {.2f, .15f, .8f, .15f, .8f, .85f, .2f, .85f},
        new float[] {
          edge[0], edge[1], edge[48], edge[49], edge[96], edge[97], edge[144], edge[145]
        },
        .0001f);
  }
}
