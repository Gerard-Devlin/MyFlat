package dev.myflat.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;
import java.util.*;

/** JPEG sizes reported by the selected camera backend, with per-camera preferences. */
@SuppressWarnings("deprecation")
final class PhotoResolution {
  static final class Option {
    final Size size;
    final boolean maximum;

    Option(Size size, boolean maximum) {
      this.size = size;
      this.maximum = maximum;
    }

    String value() {
      return size.getWidth() + "x" + size.getHeight();
    }

    String label() {
      return String.format(
          Locale.getDefault(),
          "%d × %d · %.1f MP",
          size.getWidth(),
          size.getHeight(),
          (long) size.getWidth() * size.getHeight() / 1000000d);
    }
  }

  static final class Configuration {
    final int backend;
    final String camera;
    final List<Option> options;

    Configuration(int backend, String camera, List<Option> options) {
      this.backend = backend;
      this.camera = camera;
      this.options = options;
      if (options.isEmpty()) throw new IllegalArgumentException("相机没有 JPEG 输出尺寸");
    }

    String key() {
      return "photo_resolution_" + backend + "_" + camera;
    }

    Option selected(Context context) {
      String value = preferences(context).getString(key(), "");
      for (Option option : options) if (option.value().equals(value)) return option;
      return options.get(0);
    }

    void select(Context context, Option option) {
      if (!options.contains(option)) throw new IllegalArgumentException("Unknown JPEG size");
      preferences(context).edit().putString(key(), option.value()).apply();
    }
  }

  static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences("preferences", 0);
  }

  static List<Option> sizes(Size[] normal, Size[] high, Size[] maximum, Size[] maximumHigh) {
    Map<String, Option> unique = new LinkedHashMap<>();
    Size[][] groups = {normal, high, maximum, maximumHigh};
    for (int i = 0; i < groups.length; i++)
      if (groups[i] != null)
        for (Size size : groups[i]) {
          if (size.getWidth() <= 0 || size.getHeight() <= 0) continue;
          Option option = new Option(size, i >= 2);
          unique.putIfAbsent(option.value(), option);
        }
    List<Option> result = new ArrayList<>(unique.values());
    result.sort(
        Comparator.<Option>comparingLong(o -> (long) o.size.getWidth() * o.size.getHeight())
            .reversed()
            .thenComparing(o -> o.value()));
    return Collections.unmodifiableList(result);
  }

  static Configuration camera2(String id, CameraCharacteristics c) {
    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    if (map == null) throw new IllegalArgumentException("相机不支持图像输出");
    StreamConfigurationMap full =
        Build.VERSION.SDK_INT >= 31
            ? c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            : null;
    return new Configuration(
        2,
        id,
        sizes(
            map.getOutputSizes(ImageFormat.JPEG),
            map.getHighResolutionOutputSizes(ImageFormat.JPEG),
            full == null ? null : full.getOutputSizes(ImageFormat.JPEG),
            full == null ? null : full.getHighResolutionOutputSizes(ImageFormat.JPEG)));
  }

  static Configuration legacy(int id, Camera.Parameters p) {
    List<Size> sizes = new ArrayList<>();
    for (Camera.Size size : p.getSupportedPictureSizes())
      sizes.add(new Size(size.width, size.height));
    return new Configuration(
        1, String.valueOf(id), sizes(sizes.toArray(new Size[0]), null, null, null));
  }

  static Configuration discover(Context context) throws Exception {
    if (preferences(context).getInt("camera_api", 2) == 1) {
      if (context.checkSelfPermission(Manifest.permission.CAMERA)
          != PackageManager.PERMISSION_GRANTED) throw new IllegalStateException("请先允许访问相机");
      int chosen = 0;
      Camera.CameraInfo info = new Camera.CameraInfo();
      for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
        Camera.getCameraInfo(i, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
          chosen = i;
          break;
        }
      }
      Camera camera = Camera.open(chosen);
      try {
        return legacy(chosen, camera.getParameters());
      } finally {
        camera.release();
      }
    }
    CameraManager manager = context.getSystemService(CameraManager.class);
    String chosen = null;
    for (String id : manager.getCameraIdList()) {
      CameraCharacteristics c = manager.getCameraCharacteristics(id);
      if (chosen == null) chosen = id;
      if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK)
          .equals(c.get(CameraCharacteristics.LENS_FACING))) {
        chosen = id;
        break;
      }
    }
    if (chosen == null) throw new IllegalStateException("没有可用相机");
    return camera2(chosen, manager.getCameraCharacteristics(chosen));
  }

  static Dialog picker(Activity activity, Configuration config, Runnable changed) {
    Option selected = config.selected(activity);
    String[] labels = new String[config.options.size()];
    int checked = 0;
    for (int i = 0; i < labels.length; i++) {
      Option option = config.options.get(i);
      labels[i] = option.label();
      if (option == selected) checked = i;
    }
    return Ui.alert(activity)
        .setTitle("扫描分辨率")
        .setSingleChoiceItems(
            labels,
            checked,
            (dialog, which) -> {
              config.select(activity, config.options.get(which));
              dialog.dismiss();
              changed.run();
            })
        .setNegativeButton("取消", null)
        .create();
  }
}
