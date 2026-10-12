package dev.myflat.scanner;

import java.nio.Buffer;

/**
 * ABI bridge for the supplied native image-processing artifact. No application or network logic.
 */
public final class NativeScan {
  static {
    System.loadLibrary("myflat-scan");
  }

  private NativeScan() {}

  public static native void dof(Buffer output);

  public static native void calcFreeformOutputSize(int width, Buffer model, Buffer size);

  public static native void otr(Buffer model, Buffer remap);

  public static native void ibf(Buffer indices, Buffer positions);

  public static native void adjustFingerMask(int width, int height, Buffer finger, Buffer content);

  public static native void getInpaintColor(
      int width,
      int height,
      int channels,
      Buffer pixels,
      Buffer content,
      Buffer finger,
      Buffer color);

  public static native void prepareInpaintInput(
      int width, int height, Buffer pixels, Buffer finger);
}
