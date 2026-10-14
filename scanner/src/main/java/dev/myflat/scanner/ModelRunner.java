package dev.myflat.scanner;

import android.content.Context;
import java.io.*;
import java.nio.*;
import org.tensorflow.lite.Interpreter;

/** One owned CPU interpreter at a time, so large mask activations do not accumulate. */
final class ModelRunner {
  private final Context context;

  ModelRunner(Context c) {
    context = c.getApplicationContext();
  }

  static ByteBuffer buffer(int bytes) {
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
  }

  float[] run(String model, float[] input) throws IOException {
    byte[] data;
    try (InputStream stream = context.getAssets().open("myflat/" + model + ".tflite")) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] chunk = new byte[16384];
      int count;
      while ((count = stream.read(chunk)) != -1) bytes.write(chunk, 0, count);
      data = bytes.toByteArray();
    }
    ByteBuffer weights = buffer(data.length);
    weights.put(data).rewind();
    try (Interpreter interpreter =
        new Interpreter(weights, new Interpreter.Options().setNumThreads(2).setUseXNNPACK(true))) {
      if (interpreter.getInputTensor(0).numBytes() != input.length * 4)
        throw new IOException("Wrong input size for " + model);
      ByteBuffer in = buffer(input.length * 4);
      in.asFloatBuffer().put(input);
      ByteBuffer out = buffer(interpreter.getOutputTensor(0).numBytes());
      interpreter.run(in, out);
      float[] result = new float[out.capacity() / 4];
      out.rewind();
      out.asFloatBuffer().get(result);
      for (float value : result)
        if (!Float.isFinite(value)) throw new IOException("Non-finite output: " + model);
      return result;
    }
  }
}
