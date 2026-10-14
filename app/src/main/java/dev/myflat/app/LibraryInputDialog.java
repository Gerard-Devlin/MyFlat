package dev.myflat.app;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.function.*;

/** The common name-entry form used by folder actions. */
final class LibraryInputDialog {
  private LibraryInputDialog() {}

  static void show(
      Activity a,
      String title,
      String label,
      String value,
      String positive,
      Function<String, String> validate,
      Consumer<String> save) {
    Dialog dialog = new Dialog(a);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout body = Ui.column(a);
    Ui.padding(body, 20, 20);
    body.setBackground(Ui.shape(Color.WHITE, Ui.dp(a, 12)));
    body.addView(Ui.bold(a, title, 16));
    body.addView(Ui.spacer(a, 16));
    body.addView(Ui.bold(a, label, 14));
    body.addView(Ui.spacer(a, 4));
    EditText input = Ui.input(a, value, label);
    input.setTextSize(16);
    Ui.padding(input, 8, 8);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(100)});
    input.setBackground(Ui.shape(0xfff5f6f7, Ui.dp(a, 8)));
    body.addView(input, new LinearLayout.LayoutParams(-1, -2));
    TextView error = Ui.text(a, "", 12, 0xffff3b30);
    error.setVisibility(View.GONE);
    body.addView(error);
    body.addView(Ui.spacer(a, 28));
    LinearLayout buttons = Ui.row(a);
    buttons.setGravity(Gravity.END);
    TextView cancel = Ui.text(a, "取消", 14, Ui.INK);
    cancel.setGravity(Gravity.CENTER);
    cancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    Ui.padding(cancel, 16, 12);
    cancel.setOnClickListener(v -> dialog.dismiss());
    buttons.addView(cancel, new LinearLayout.LayoutParams(-2, Ui.dp(a, 44)));
    buttons.addView(Ui.spacer(a, 12), new LinearLayout.LayoutParams(Ui.dp(a, 12), 1));
    TextView ok = Ui.text(a, positive, 14, Color.WHITE);
    ok.setGravity(Gravity.CENTER);
    ok.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    Ui.padding(ok, 16, 12);
    ok.setBackground(Ui.shape(Ui.GREEN, Ui.dp(a, 8)));
    ok.setOnClickListener(
        v -> {
          String name = input.getText().toString().trim();
          String message =
              validate == null ? (name.isEmpty() ? "请输入有效的名称。" : null) : validate.apply(name);
          if (message != null) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
            return;
          }
          save.accept(name);
          dialog.dismiss();
        });
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            error.setVisibility(View.GONE);
          }

          public void afterTextChanged(Editable e) {}
        });
    buttons.addView(ok, new LinearLayout.LayoutParams(-2, Ui.dp(a, 44)));
    body.addView(buttons);
    dialog.setContentView(body);
    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    dialog.show();
    dialog
        .getWindow()
        .setLayout(a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 48), -2);
    input.requestFocus();
    input.selectAll();
    input.postDelayed(
        () ->
            ((InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE))
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT),
        100);
  }

  static void createFolder(Activity a, LongConsumer save) {
    folder(a, 0, "创建文件夹", "", "创建", save);
  }

  static void renameFolder(Activity a, long id, String current, Runnable done) {
    folder(a, id, "变更资料夹名称", current, "确定", unused -> done.run());
  }

  private static void folder(
      Activity a, long id, String title, String current, String positive, LongConsumer result) {
    DocumentStore store = MyFlatApp.of(a).store;
    show(
        a,
        title,
        "文件夹名称",
        current,
        positive,
        name -> {
          if (name.isEmpty()) return "请输入有效的文件夹名称。";
          for (DocumentStore.Folder f : store.folders())
            if (f.id != id && f.name.equals(name)) return "文件夹名称已经存在。请输入其他文件夹名称";
          return null;
        },
        name -> {
          long folder = id;
          if (folder == 0) folder = store.createFolder(name);
          else store.renameFolder(id, name);
          result.accept(folder);
        });
  }
}
