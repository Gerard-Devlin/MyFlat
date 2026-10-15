package dev.myflat.scanner;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.LruCache;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import org.tensorflow.lite.Interpreter;

/** One owned CPU interpreter at a time, so large mask activations do not accumulate. */
final class ModelRunner {
  private static final LruCache<String, ByteBuffer> WEIGHTS =
      new LruCache<String, ByteBuffer>(32 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, ByteBuffer value) {
          return value.capacity();
        }
      };
  private final Context context;
  private ByteBuffer inputStorage, outputStorage;

  ModelRunner(Context c) {
    context = c.getApplicationContext();
  }

  static ByteBuffer buffer(int bytes) {
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
  }

  float[] run(String model, float[] input) throws IOException {
    ByteBuffer weights = readWeights(context, model);
    try (Interpreter interpreter =
        new Interpreter(
            weights,
            new Interpreter.Options()
                .setNumThreads(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())))
                .setUseXNNPACK(true))) {
      if (interpreter.getInputTensor(0).numBytes() != input.length * 4)
        throw new IOException("Wrong input size for " + model);
      int inputBytes = input.length * 4, outputBytes = interpreter.getOutputTensor(0).numBytes();
      if (inputStorage == null || inputStorage.capacity() < inputBytes)
        inputStorage = buffer(inputBytes);
      if (outputStorage == null || outputStorage.capacity() < outputBytes)
        outputStorage = buffer(outputBytes);
      ByteBuffer in = view(inputStorage, inputBytes), out = view(outputStorage, outputBytes);
      in.asFloatBuffer().put(input);
      interpreter.run(in, out);
      float[] result = new float[out.capacity() / 4];
      out.rewind();
      out.asFloatBuffer().get(result);
      for (float value : result)
        if (!Float.isFinite(value)) throw new IOException("Non-finite output: " + model);
      return result;
    }
  }

  private static ByteBuffer view(ByteBuffer storage, int bytes) {
    ByteBuffer view = storage.duplicate();
    view.clear().limit(bytes);
    return view.slice().order(ByteOrder.nativeOrder());
  }

  static synchronized ByteBuffer readWeights(Context context, String model) throws IOException {
    String asset = "myflat/" + model + ".tflite";
    String key = context.getPackageCodePath() + ":" + asset;
    ByteBuffer cached = WEIGHTS.get(key);
    if (cached != null) return cached.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    // Uncompressed assets can be shared with the native interpreter without heap copies.
    try (AssetFileDescriptor descriptor = context.getAssets().openFd(asset);
        FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
      ByteBuffer weights =
          stream
              .getChannel()
              .map(
                  FileChannel.MapMode.READ_ONLY,
                  descriptor.getStartOffset(),
                  descriptor.getDeclaredLength())
              .order(ByteOrder.nativeOrder());
      WEIGHTS.put(key, weights);
      return weights.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    } catch (IOException compressedAsset) {
      // Retain support for alternate APK packagers that compress model assets.
      try (InputStream stream = context.getAssets().open(asset)) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int count;
        while ((count = stream.read(chunk)) != -1) bytes.write(chunk, 0, count);
        byte[] data = bytes.toByteArray();
        ByteBuffer weights = buffer(data.length);
        weights.put(data).rewind();
        WEIGHTS.put(key, weights);
        return weights.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
      }
    }
  }
}
