package dev.myflat.app;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;

/** Bounded decoding, including EXIF orientation on API 26/27. */
public final class Images {
  private Images() {}

  public static Bitmap decode(Context context, Uri uri, int maxEdge) throws IOException {
    if (Build.VERSION.SDK_INT >= 28) {
      ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
      return ImageDecoder.decodeBitmap(
          source,
          (decoder, info, src) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float scale = maxEdge <= 0 ? 1 : Math.min(1f, (float) maxEdge / Math.max(w, h));
            decoder.setTargetSize(
                Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
          });
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      BitmapFactory.decodeStream(in, null, options);
    }
    if (options.outWidth < 1) throw new IOException("无法读取图片");
    options.inSampleSize = 1;
    while (maxEdge > 0
        && Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxEdge * 1.5f)
      options.inSampleSize *= 2;
    options.inJustDecodeBounds = false;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    options.inMutable = true;
    Bitmap result;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      result = BitmapFactory.decodeStream(in, null, options);
    }
    if (result == null) throw new IOException("无法解码图片");
    int orientation = ExifInterface.ORIENTATION_NORMAL;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      orientation =
          new ExifInterface(in)
              .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
    }
    Matrix matrix = new Matrix();
    switch (orientation) {
      case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
        matrix.setScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_180:
        matrix.setRotate(180);
        break;
      case ExifInterface.ORIENTATION_FLIP_VERTICAL:
        matrix.setScale(1, -1);
        break;
      case ExifInterface.ORIENTATION_TRANSPOSE:
        matrix.setRotate(90);
        matrix.postScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_90:
        matrix.setRotate(90);
        break;
      case ExifInterface.ORIENTATION_TRANSVERSE:
        matrix.setRotate(-90);
        matrix.postScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_270:
        matrix.setRotate(-90);
        break;
      default:
        break;
    }
    if (!matrix.isIdentity()) {
      Bitmap oriented =
          Bitmap.createBitmap(result, 0, 0, result.getWidth(), result.getHeight(), matrix, true);
      if (oriented != result) result.recycle();
      result = oriented;
    }
    return result;
  }

  public static Bitmap decode(File file, int maxEdge) throws IOException {
    if (Build.VERSION.SDK_INT >= 28) {
      return ImageDecoder.decodeBitmap(
          ImageDecoder.createSource(file),
          (decoder, info, src) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            if (maxEdge > 0) {
              float scale = Math.min(1f, (float) maxEdge / Math.max(w, h));
              decoder.setTargetSize(
                  Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
            }
          });
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getPath(), options);
    if (options.outWidth < 1) throw new IOException("图片文件不存在或已损坏");
    options.inSampleSize = 1;
    while (maxEdge > 0
        && Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxEdge * 1.5f)
      options.inSampleSize *= 2;
    options.inJustDecodeBounds = false;
    options.inMutable = true;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap result = BitmapFactory.decodeFile(file.getPath(), options);
    if (result == null) throw new IOException("图像解码失败");
    int orientation = new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
    Matrix matrix = new Matrix();
    switch (orientation) {
      case 2:
        matrix.setScale(-1, 1);
        break;
      case 3:
        matrix.setRotate(180);
        break;
      case 4:
        matrix.setScale(1, -1);
        break;
      case 5:
        matrix.setRotate(90);
        matrix.postScale(-1, 1);
        break;
      case 6:
        matrix.setRotate(90);
        break;
      case 7:
        matrix.setRotate(-90);
        matrix.postScale(-1, 1);
        break;
      case 8:
        matrix.setRotate(-90);
        break;
      default:
        break;
    }
    if (!matrix.isIdentity()) {
      Bitmap rotated =
          Bitmap.createBitmap(result, 0, 0, result.getWidth(), result.getHeight(), matrix, true);
      if (rotated != result) result.recycle();
      result = rotated;
    }
    return result;
  }
}
