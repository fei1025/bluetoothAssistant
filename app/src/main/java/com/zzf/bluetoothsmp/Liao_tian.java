package com.zzf.bluetoothsmp;

import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.zzf.bluetoothsmp.base.BaseActivity;
import com.zzf.bluetoothsmp.liaoTian.ChatTranscriptController;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Browsing saved messages never owns, sends through, or closes an active Bluetooth transport. */
public class Liao_tian extends BaseActivity {
    private static final int SEARCH_MENU = Menu.FIRST;
    private ChatTranscriptController transcript;
    private String keyword = "";
    private String fromDate = "";
    private String toDate = "";
    private int direction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_liao_tian);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        String address = getIntent().getStringExtra("bluetoothAdd");
        String uuid = getIntent().getStringExtra("bluetoothUUid");
        String name = getIntent().getStringExtra("bluetoothName");
        Toolbar toolbar = findViewById(R.id.lao_tian_toolbar);
        toolbar.setTitle(name == null || name.trim().isEmpty() ? address : name);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        findViewById(R.id.history_input_bar).setVisibility(View.GONE);
        applySystemBarInsets();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });
        transcript = new ChatTranscriptController(findViewById(R.id.legacy_chat_root),
                address, uuid, null, null);
        if (savedInstanceState != null) {
            keyword = savedInstanceState.getString("history_keyword", "");
            fromDate = savedInstanceState.getString("history_from", "");
            toDate = savedInstanceState.getString("history_to", "");
            direction = savedInstanceState.getInt("history_direction", 0);
            applyFilter();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        menu.add(Menu.NONE, SEARCH_MENU, Menu.NONE, R.string.history_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == SEARCH_MENU) {
            showSearch();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSearch() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding, padding, 0);
        EditText text = field(fields, R.string.history_keyword, keyword);
        EditText from = field(fields, R.string.history_from_date, fromDate);
        EditText to = field(fields, R.string.history_to_date, toDate);
        Spinner type = new Spinner(this);
        type.setContentDescription(getString(R.string.history_direction));
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.history_all), getString(R.string.history_received),
                        getString(R.string.history_sent)}));
        type.setSelection(direction);
        fields.addView(type);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.history_search)
                .setView(fields).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String previousKeyword = keyword;
            String previousFrom = fromDate;
            String previousTo = toDate;
            int previousDirection = direction;
            keyword = text.getText().toString();
            fromDate = from.getText().toString().trim();
            toDate = to.getText().toString().trim();
            direction = type.getSelectedItemPosition();
            if (applyFilter()) {
                dialog.dismiss();
            } else {
                keyword = previousKeyword;
                fromDate = previousFrom;
                toDate = previousTo;
                direction = previousDirection;
            }
        });
    }

    private EditText field(LinearLayout parent, int hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setText(value);
        parent.addView(field);
        return field;
    }

    private boolean applyFilter() {
        try {
            Long from = parseDate(fromDate, false);
            Long to = parseDate(toDate, true);
            if (from != null && to != null && from >= to) throw new IllegalArgumentException();
            transcript.setHistoryFilter(keyword, from, to,
                    direction == 0 ? null : direction == 1 ? 0 : 1);
            return true;
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, R.string.history_date_invalid, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private Long parseDate(String text, boolean exclusiveEnd) {
        if (text.isEmpty()) return null;
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(text, position);
        if (date == null || position.getIndex() != text.length()) throw new IllegalArgumentException();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        if (exclusiveEnd) calendar.add(Calendar.DAY_OF_YEAR, 1);
        return calendar.getTimeInMillis();
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.legacy_chat_root);
        View appBar = findViewById(R.id.legacy_chat_appbar);
        View content = findViewById(R.id.legacy_chat_content);
        int top = appBar.getPaddingTop();
        int left = content.getPaddingLeft();
        int right = content.getPaddingRight();
        int bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            appBar.setPadding(appBar.getPaddingLeft(), top + safe.top,
                    appBar.getPaddingRight(), appBar.getPaddingBottom());
            content.setPadding(left + safe.left, content.getPaddingTop(), right + safe.right, bottom + safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("history_keyword", keyword);
        state.putString("history_from", fromDate);
        state.putString("history_to", toDate);
        state.putInt("history_direction", direction);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (transcript != null) transcript.close();
        super.onDestroy();
    }
}
