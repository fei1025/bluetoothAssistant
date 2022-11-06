package com.zzf.bluetoothsmp.utils;

import android.annotation.SuppressLint;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateUtils {
    /**
     * 简单日期时间格式 yyyy-MM-dd HH:mm:ss
     */
    public static final String FORMAT_COMMON_DATETIME = "yyyy-MM-dd HH:mm";

    /**
     * 取得当前日期 yyyy-MM-dd HH:mm:ss
     *
     * @return time
     */
    @SuppressLint("SimpleDateFormat")
    public static String getCurrentTime() {
      DateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return f.format(Calendar.getInstance().getTime());
    }

    @SuppressLint("SimpleDateFormat")
    public static String dateToStr(Date date) {
        return (date == null) ? ""
                : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(date);

    }

    @SuppressLint("SimpleDateFormat")
    public static String dateToStr(Date date, String fo) {
        return (date == null) ? ""
                : new SimpleDateFormat(fo).format(date);

    }


}
