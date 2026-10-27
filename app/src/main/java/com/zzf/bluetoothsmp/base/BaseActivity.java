package com.zzf.bluetoothsmp.base;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import com.zzf.bluetoothsmp.utils.LanguageUtils;

public class BaseActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(LanguageUtils.localizedContext(context));
    }
     public String[] mPermissionListnew =null;

     public void cratePermission(){
      List<String> list =  new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        mPermissionListnew= list.toArray(new String[0]);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep edge-to-edge behavior consistent on Android versions where it is
        // not enforced by the platform yet. Individual screens handle insets.
        EdgeToEdge.enable(this);
    }

    /*********************子类实现*****************************/
    //获取布局文件
    public  int getLayoutId(){return 0;};
    //初始化view
    public  void initView(){};

}
