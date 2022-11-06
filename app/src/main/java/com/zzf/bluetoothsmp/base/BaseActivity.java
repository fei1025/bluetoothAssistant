package com.zzf.bluetoothsmp.base;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;

public class BaseActivity extends AppCompatActivity {
     public String[] mPermissionListnew =null;

     public void cratePermission(){
      List<String> list =  new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        list.add(Manifest.permission.BLUETOOTH_ADMIN);
        list.add(Manifest.permission.BLUETOOTH);
        mPermissionListnew= list.toArray(new String[0]);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    }

    /*********************子类实现*****************************/
    //获取布局文件
    public  int getLayoutId(){return 0;};
    //初始化view
    public  void initView(){};

}
