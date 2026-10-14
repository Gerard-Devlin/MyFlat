package dev.myflat.app;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Size;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Legacy camera backend for devices or camera drivers requiring Camera1. */
@SuppressWarnings("deprecation")
final class LegacyCapture implements AutoCloseable {
  private Camera camera;
  final Size photoSize, previewSize;
  final PhotoResolution.Configuration resolutions;
  final int orientation;
  final boolean flashAvailable;

  LegacyCapture(android.content.Context context, SurfaceTexture texture) throws IOException {
    int chosen = 0;
    Camera.CameraInfo info = new Camera.CameraInfo();
    for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
      Camera.getCameraInfo(i, info);
      if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
        chosen = i;
        break;
      }
    }
    Camera.getCameraInfo(chosen, info);
    orientation = info.orientation;
    Camera opened = Camera.open(chosen);
    camera = opened;
    try {
      Camera.Parameters p = opened.getParameters();
      resolutions = PhotoResolution.legacy(chosen, p);
      photoSize = resolutions.selected(context).size;
      p.setPictureSize(photoSize.getWidth(), photoSize.getHeight());
      p.setJpegQuality(100);
      float aspect = (float) photoSize.getWidth() / photoSize.getHeight();
      Camera.Size preview =
          Collections.min(
              p.getSupportedPreviewSizes(),
              Comparator.comparingDouble(
                  s ->
                      Math.abs((float) s.width / s.height - aspect) * 10000
                          + Math.abs(Math.max(s.width, s.height) - 1440)));
      previewSize = new Size(preview.width, preview.height);
      p.setPreviewSize(preview.width, preview.height);
      List<String> focus = p.getSupportedFocusModes();
      if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
        p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
      flashAvailable = p.getSupportedFlashModes() != null && p.getSupportedFlashModes().size() > 1;
      opened.setParameters(p);
      opened.setDisplayOrientation(orientation);
      opened.setPreviewTexture(texture);
      opened.startPreview();
    } catch (IOException | RuntimeException error) {
      opened.release();
      camera = null;
      throw error;
    }
  }

  void flash(int mode) {
    if (camera == null || !flashAvailable) return;
    Camera.Parameters p = camera.getParameters();
    String value =
        mode == 1
            ? Camera.Parameters.FLASH_MODE_ON
            : mode == 2
                ? Camera.Parameters.FLASH_MODE_AUTO
                : mode == 3 ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF;
    if (p.getSupportedFlashModes().contains(value)) {
      p.setFlashMode(value);
      camera.setParameters(p);
    }
  }

  void focus(float x, float y) {
    if (camera == null) return;
    try {
      Camera.Parameters p = camera.getParameters();
      List<String> modes = p.getSupportedFocusModes();
      if (modes == null || !modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) return;
      camera.cancelAutoFocus();
      p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
      int cx = Math.round(Math.max(0, Math.min(1, x)) * 2000 - 1000),
          cy = Math.round(Math.max(0, Math.min(1, y)) * 2000 - 1000);
      android.graphics.Rect region =
          new android.graphics.Rect(
              Math.max(-1000, cx - 100),
              Math.max(-1000, cy - 100),
              Math.min(1000, cx + 100),
              Math.min(1000, cy + 100));
      if (p.getMaxNumFocusAreas() > 0)
        p.setFocusAreas(Collections.singletonList(new Camera.Area(region, 1000)));
      if (p.getMaxNumMeteringAreas() > 0)
        p.setMeteringAreas(Collections.singletonList(new Camera.Area(region, 1000)));
      camera.setParameters(p);
      camera.autoFocus((ok, c) -> {});
    } catch (RuntimeException ignored) {
      /* A driver may decline explicit autofocus while continuing its preview. */
    }
  }

  void take(int rotation, boolean sound, Consumer<byte[]> jpeg) {
    if (camera == null) throw new IllegalStateException("相机已关闭");
    Camera.Parameters p = camera.getParameters();
    p.setRotation((orientation + rotation + 360) % 360);
    p.setJpegQuality(100);
    camera.setParameters(p);
    camera.enableShutterSound(sound);
    camera.takePicture(
        null,
        null,
        (bytes, c) -> {
          try {
            c.startPreview();
          } catch (RuntimeException ignored) {
          }
          jpeg.accept(bytes);
        });
  }

  @Override
  public void close() {
    Camera c = camera;
    camera = null;
    if (c != null) {
      try {
        c.stopPreview();
      } catch (RuntimeException ignored) {
      }
      c.release();
    }
  }
}
