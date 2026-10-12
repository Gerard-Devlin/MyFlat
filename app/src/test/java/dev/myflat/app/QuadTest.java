package dev.myflat.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class QuadTest {
  @Test
  public void acceptsPerspectivePage() {
    assertTrue(Quad.valid(new float[] {20, 10, 800, 60, 750, 1100, 5, 1000}, 24));
  }

  @Test
  public void rejectsCrossingCorners() {
    assertFalse(Quad.valid(new float[] {0, 0, 100, 100, 100, 0, 0, 100}, 2));
  }

  @Test
  public void rejectsCollapsedEdge() {
    assertFalse(Quad.valid(new float[] {0, 0, 1, 0, 100, 100, 0, 100}, 24));
  }

  @Test
  public void rejectsConcaveRegion() {
    assertFalse(Quad.valid(new float[] {0, 0, 100, 0, 25, 25, 0, 100}, 2));
  }

  @Test
  public void rejectsInvalidCoordinates() {
    assertFalse(Quad.valid(new float[] {0, 0, Float.NaN, 0, 100, 100, 0, 100}, 2));
  }

  @Test
  public void rejectsReversedWinding() {
    assertFalse(Quad.valid(new float[] {0, 0, 0, 100, 100, 100, 100, 0}, 2));
  }
}
