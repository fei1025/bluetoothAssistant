package com.zzf.bluetoothsmp.utils;

public class StringUtils {



    public static boolean isNotEmpty(Object o){
        return o!=null && o.toString()!=null&& o.toString().length()!=0;
    }
}
