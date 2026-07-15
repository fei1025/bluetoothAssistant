package com.zzf.bluetoothsmp.fragment;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;

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

        // 1. 设置状态栏透明
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            requireActivity().getWindow().setStatusBarColor(Color.TRANSPARENT);
        }

        // 2. 控制状态栏图标颜色（深色/浅色模式适配）
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDarkMode = nightMode == Configuration.UI_MODE_NIGHT_YES;
        
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
        // 如果不是暗黑模式，则设置状态栏图标为深色
        controller.setAppearanceLightStatusBars(!isDarkMode);

        // 3. 动态处理系统栏高度，防止内容被遮挡
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int leftInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).left;
            int rightInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).right;

            v.setPadding(leftInset, topInset, rightInset, bottomInset);
            return insets;
        });
    }
}
