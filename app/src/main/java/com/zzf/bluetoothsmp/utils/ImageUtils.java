package com.zzf.bluetoothsmp.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;

import com.avatarfirst.avatargenlib.AvatarConstants;
import com.avatarfirst.avatargenlib.AvatarGenerator;
import com.zzf.bluetoothsmp.MyApplication;

public class ImageUtils {


    public static Bitmap defaultAvatar(String name) {
        Log.d("ImageUtils", "defaultAvatar:2222222222222222222222222 "+name);
        BitmapDrawable build = new AvatarGenerator.AvatarBuilder(MyApplication.getContext())
                .setLabel(name)
                .setAvatarSize(120)
                .setTextSize(30)
                //.toSquare()
               // .toCircle()
               // .setBackgroundColor(Color.RED)
                .build();

        return build.getBitmap();
    }
}
