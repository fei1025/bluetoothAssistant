package com.zzf.bluetoothsmp.fragment;

import android.content.res.Configuration;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;

/**
 * 基类 Fragment，统一处理 Android 15 边缘到边缘显示
 */




public abstract class BaseFragment extends Fragment {

    /**
     * 适配沉浸式状态栏
     * 在子类中调用，只需传入根视图即可
     */
    protected void setupEdgeToEdge(View root) {
        if (getActivity() == null) return;

        // EdgeToEdge.enable() 负责系统栏外观；这里仅处理内容避让。
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDarkMode = nightMode == Configuration.UI_MODE_NIGHT_YES;

        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!isDarkMode);

        final int initialLeft = root.getPaddingLeft();
        final int initialTop = root.getPaddingTop();
        final int initialRight = root.getPaddingRight();
        final int initialBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    initialLeft + safeInsets.left,
                    initialTop + safeInsets.top,
                    initialRight + safeInsets.right,
                    initialBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
