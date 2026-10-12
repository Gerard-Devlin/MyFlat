package dev.myflat.app;

import android.content.Context;
import dev.myflat.scanner.ScanEngine;
import org.json.JSONObject;

/** Serializable processing choices; each page keeps its own copy. */
public final class ScanSettings {
  public boolean dewarp = true,
      enhance = true,
      fingers = true,
      straighten = false,
      handwriting = false;
  public boolean doublePage = false, rightFirst = false;
  public float split = .5f;
  public int quality = 95, maxEdge = 2400;

  public ScanEngine.Options engine() {
    ScanEngine.Options options = new ScanEngine.Options();
    options.dewarp = dewarp;
    options.enhance = enhance;
    options.fingers = fingers;
    options.straighten = straighten;
    options.handwriting = handwriting;
    options.maxEdge = maxEdge;
    return options;
  }

  public String json() {
    try {
      JSONObject j = new JSONObject();
      j.put("dewarp", dewarp)
          .put("enhance", enhance)
          .put("fingers", fingers)
          .put("straighten", straighten)
          .put("handwriting", handwriting)
          .put("doublePage", doublePage)
          .put("rightFirst", rightFirst)
          .put("split", split)
          .put("quality", quality)
          .put("maxEdge", maxEdge);
      return j.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static ScanSettings parse(String value) {
    ScanSettings s = new ScanSettings();
    try {
      JSONObject j = new JSONObject(value);
      s.dewarp = j.optBoolean("dewarp", true);
      s.enhance = j.optBoolean("enhance", true);
      s.fingers = j.optBoolean("fingers", true);
      s.straighten = j.optBoolean("straighten");
      s.handwriting = j.optBoolean("handwriting");
      s.doublePage = j.optBoolean("doublePage");
      s.rightFirst = j.optBoolean("rightFirst");
      s.split = (float) Math.max(.3, Math.min(.7, j.optDouble("split", .5)));
      s.quality = Math.max(60, Math.min(100, j.optInt("quality", 95)));
      s.maxEdge = Math.max(1200, Math.min(3600, j.optInt("maxEdge", 2400)));
    } catch (Exception ignored) {
      /* Defaults also support the first launch. */
    }
    return s;
  }

  public static ScanSettings defaults(Context c) {
    return parse(c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("scan", "{}"));
  }

  public void save(Context c) {
    c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putString("scan", json())
        .apply();
  }
}
