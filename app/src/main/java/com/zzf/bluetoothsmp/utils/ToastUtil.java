package com.zzf.bluetoothsmp.utils;

import android.content.Context;
import android.os.Looper;
import android.widget.Toast;

public class ToastUtil {
    private static Toast mToast = null;
    public static void toastWord(Context context, String text) {
        if (mToast == null) {
            mToast = Toast.makeText(context, text, Toast.LENGTH_LONG);
        } else {
            mToast.setText(text);
            mToast.setDuration(Toast.LENGTH_LONG);
        }
        mToast.show();
    }
}
