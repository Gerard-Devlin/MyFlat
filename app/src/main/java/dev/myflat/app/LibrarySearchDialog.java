package dev.myflat.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;
import java.util.function.LongConsumer;
import org.json.*;

/** Folder-name search with the same history and result navigation as the library. */
final class LibrarySearchDialog {
  private LibrarySearchDialog() {}

  static Dialog show(Activity a, long scope, LongConsumer open) {
    Dialog dialog = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout root = Ui.column(a);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout head = Ui.row(a);
    head.addView(Ui.iconButton(a, "back", "返回", dialog::dismiss));
    long[] filter = {scope};
    TextView chip = Ui.text(a, "", 13, Ui.INK);
    Ui.padding(chip, 8, 4);
    chip.setBackground(Ui.shape(0xffeeeeee, Ui.dp(a, 16)));
    for (DocumentStore.Folder f : MyFlatApp.of(a).store.folders())
      if (f.id == scope) chip.setText(f.name + " ×");
    chip.setVisibility(scope > 0 ? View.VISIBLE : View.GONE);
    head.addView(chip, new LinearLayout.LayoutParams(-2, -2));
    EditText input = Ui.input(a, "", "搜索文件夹");
    input.setTextSize(15);
    input.setSelectAllOnFocus(false);
    input.setBackgroundColor(Color.TRANSPARENT);
    Ui.padding(input, 16, 0);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    head.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1));
    View clear = Ui.iconButton(a, "close", "清除搜索", () -> input.setText(""));
    clear.setVisibility(View.INVISIBLE);
    head.addView(clear);
    root.addView(head);
    root.addView(Ui.line(a));
    ScrollView scroll = new ScrollView(a);
    LinearLayout list = Ui.column(a);
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    android.content.SharedPreferences prefs = a.getSharedPreferences("search", 0);
    List<String> history = new ArrayList<>();
    try {
      JSONArray values = new JSONArray(prefs.getString("history", "[]"));
      for (int i = 0; i < values.length(); i++) history.add(values.getString(i));
    } catch (JSONException ignored) {
    }
    Runnable persist =
        () -> prefs.edit().putString("history", new JSONArray(history).toString()).apply();
    Runnable[] render = new Runnable[1];
    java.util.function.Consumer<String> remember =
        value -> {
          String word = value.trim();
          if (word.isEmpty()) return;
          history.remove(word);
          history.add(0, word);
          while (history.size() > 20) history.remove(history.size() - 1);
          persist.run();
        };
    render[0] =
        () -> {
          list.removeAllViews();
          String query = input.getText().toString().trim();
          clear.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
          if (query.isEmpty()) {
            if (history.isEmpty()) return;
            TextView recent = Ui.text(a, "最近搜寻", 18, 0xff616161);
            Ui.padding(recent, 20, 16);
            list.addView(recent);
            for (String word : new ArrayList<>(history)) {
              LinearLayout row = Ui.row(a);
              Ui.padding(row, 24, 8);
              Ui.Icon icon = new Ui.Icon(a, "search");
              icon.color = 0xff757575;
              row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20)));
              TextView text = Ui.text(a, word, 17, 0xff757575);
              LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1);
              tp.leftMargin = Ui.dp(a, 12);
              text.setGravity(Gravity.CENTER_VERTICAL);
              row.addView(text, tp);
              row.addView(
                  Ui.iconButton(
                      a,
                      "close",
                      "删除搜索记录",
                      () -> {
                        history.remove(word);
                        persist.run();
                        render[0].run();
                      }));
              text.setOnClickListener(v -> input.setText(word));
              list.addView(row);
            }
            TextView remove = Ui.text(a, "清除搜索历史", 14, Ui.INK);
            remove.setGravity(Gravity.END);
            Ui.padding(remove, 16, 16);
            remove.setOnClickListener(
                v -> {
                  history.clear();
                  persist.run();
                  render[0].run();
                });
            list.addView(remove);
            return;
          }
          TextView category = Ui.text(a, "文件夹", 18, 0xff616161);
          Ui.padding(category, 20, 16);
          list.addView(category);
          int count = 0;
          for (DocumentStore.Folder folder : MyFlatApp.of(a).store.folders()) {
            if (filter[0] > 0 && folder.id != filter[0]) continue;
            if (!folder
                .name
                .toLowerCase(Locale.getDefault())
                .contains(query.toLowerCase(Locale.getDefault()))) continue;
            count++;
            LinearLayout row = Ui.row(a);
            Ui.padding(row, 24, 14);
            Ui.Icon icon = new Ui.Icon(a, "folder");
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
            TextView title = Ui.text(a, folder.name, 17, Ui.INK);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.leftMargin = Ui.dp(a, 12);
            row.addView(title, tp);
            row.addView(Ui.text(a, "›", 24, 0xff616161));
            row.setContentDescription(folder.name);
            row.setOnClickListener(
                v -> {
                  remember.accept(query);
                  FolderSecurity.require(
                      a,
                      folder.id,
                      () -> {
                        dialog.dismiss();
                        open.accept(folder.id);
                      },
                      () -> {});
                });
            list.addView(row);
          }
          if (count == 0) {
            LinearLayout empty = Ui.column(a);
            empty.setGravity(Gravity.CENTER);
            Ui.padding(empty, 24, 80);
            Ui.Icon icon = new Ui.Icon(a, "search");
            icon.color = 0xffbdbdbd;
            empty.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 64)));
            empty.addView(Ui.spacer(a, 8));
            TextView text = Ui.text(a, "没有搜索结果。\n请尝试其他关键词。", 14, 0xff757575);
            text.setGravity(Gravity.CENTER);
            empty.addView(text);
            list.addView(empty);
          }
        };
    chip.setOnClickListener(
        v -> {
          filter[0] = 0;
          chip.setVisibility(View.GONE);
          render[0].run();
        });
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            render[0].run();
          }

          public void afterTextChanged(Editable e) {}
        });
    input.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_SEARCH) {
            remember.accept(input.getText().toString());
            ((InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(), 0);
            return true;
          }
          return false;
        });
    render[0].run();
    Ui.dialogInsets(dialog, root);
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    input.requestFocus();
    input.postDelayed(
        () ->
            ((InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE))
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT),
        100);
    return dialog;
  }
}
