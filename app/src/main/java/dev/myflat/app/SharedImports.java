package dev.myflat.app;

import android.content.*;
import android.net.Uri;
import java.util.*;

/** Accepts image/PDF content shared by another application. */
final class SharedImports {
  static boolean accepts(Intent intent) {
    String action = intent.getAction();
    return Intent.ACTION_SEND.equals(action)
        || Intent.ACTION_SEND_MULTIPLE.equals(action)
        || Intent.ACTION_EDIT.equals(action);
  }

  static ArrayList<Uri> collect(Context context, Intent intent) {
    LinkedHashSet<Uri> candidates = new LinkedHashSet<>();
    if (!accepts(intent)) return new ArrayList<>();
    try {
      if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
        ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (streams != null) candidates.addAll(streams);
      } else {
        Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null) candidates.add(stream);
      }
      if (intent.getData() != null) candidates.add(intent.getData());
      ClipData clip = intent.getClipData();
      if (clip != null)
        for (int i = 0; i < clip.getItemCount(); i++) candidates.add(clip.getItemAt(i).getUri());
      if (candidates.size() > 300) return new ArrayList<>();
      ArrayList<Uri> result = new ArrayList<>();
      for (Uri uri : candidates) {
        if (uri == null || !"content".equals(uri.getScheme())) continue;
        String type = context.getContentResolver().getType(uri);
        if (type == null) type = intent.getType();
        if (type != null && (type.startsWith("image/") || type.equals("application/pdf")))
          result.add(uri);
      }
      return result;
    } catch (RuntimeException invalid) {
      return new ArrayList<>();
    }
  }

  static void forward(Intent target, List<Uri> uris) {
    ArrayList<String> values = new ArrayList<>();
    ClipData clip = null;
    for (Uri uri : uris) {
      values.add(uri.toString());
      if (clip == null) clip = ClipData.newRawUri("Scans", uri);
      else clip.addItem(new ClipData.Item(uri));
    }
    target.putStringArrayListExtra("shared_imports", values);
    target.setClipData(clip);
    target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }
}
