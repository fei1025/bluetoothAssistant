package com.zzf.bluetoothsmp.plugin;

import android.content.Context;
import android.view.View;
import android.widget.*;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.view.*;
import androidx.core.graphics.Insets;

final class PluginViews {
    private PluginViews() { }
    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static TextView text(Context context, CharSequence value, int size) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size);
        int padding = dp(context, 8); text.setPadding(padding, padding, padding, padding); text.setTextIsSelectable(true); return text;
    }
    static Button button(LinearLayout parent, int label, Runnable action) {
        Button button = new AppCompatButton(parent.getContext()); button.setAllCaps(false); button.setText(label);
        button.setMinHeight(dp(parent.getContext(), 48)); button.setOnClickListener(ignored -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    static void insets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, window) -> {
            Insets safe = window.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return window;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
