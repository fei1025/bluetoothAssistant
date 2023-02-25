package com.zzf.bluetoothsmp.liaoTian;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.fragment.app.Fragment;

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.KeyboardEntity;
import com.zzf.bluetoothsmp.utils.StringUtils;

import org.litepal.LitePal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyboardFragment extends Fragment {

    private String TAG = "KeyboardFragment";
    private String defaultAdd = "defaultAdd";

    private String demo = "demo";
    BluetoothDrive drive;
    ToggleButton toggle;
    private Liantian_new liantian_new;
    Map<Integer,KeyboardEntity>  keymap =null;

    public KeyboardFragment(BluetoothDrive drive) {
        this.drive = drive;
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        liantian_new=(Liantian_new)getActivity();
        View root = inflater.inflate(R.layout.frament_keyboard_moder, container, false);
        initData(root);
        return root;
    }

    public void longClick(Button button) {
        showDialog(button);
    }


    private void showDialog(Button v) {
        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        View dialogView = layoutInflater.inflate(R.layout.dialog_layout, null);
        KeyboardEntity m = LitePal.where(" driveAdd =? and buttonId =?", drive.getDriveAdd(), v.getId() + "").findFirst(KeyboardEntity.class);

        EditText editText_button = dialogView.findViewById(R.id.editText_button);
        editText_button.setHint(getString(R.string.PleaseEnterButtonName));

        EditText editText2 = dialogView.findViewById(R.id.editText_down);
        editText2.setHint(getString(R.string.PleaseEnterSenMsg));

        EditText editText3 = dialogView.findViewById(R.id.editText_up);
        editText3.setHint(getString(R.string.UpEnterSenMsg));

        if (m != null) {
            String buttonName = m.getButtonName();
            if (StringUtils.isNotEmpty(buttonName)) {
                editText_button.setText(buttonName);
            }
            String actionDown = m.getActionDown();

            if (StringUtils.isNotEmpty(actionDown)) {
                editText2.setText(actionDown);
            }
            String actionUp = m.getActionUp();
            if (StringUtils.isNotEmpty(actionUp)) {
                editText3.setText(actionUp);
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setView(dialogView)
                .setPositiveButton(getString(R.string.confirm), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int id = v.getId();
                        KeyboardEntity m = LitePal.where(" driveAdd =? and buttonId =?", drive.getDriveAdd(), id + "").findFirst(KeyboardEntity.class);
                        if (m == null) {
                            m = new KeyboardEntity();
                            m.setDriveAdd(drive.getDriveAdd());
                            m.setButtonId(id);
                        }
                        // do something
                        String Text_button = editText_button.getText().toString();
                        if (Text_button != null && Text_button.length() != 0) {
                            m.setButtonName(Text_button);
                            v.setText(Text_button);


                        }
                        Editable text = editText2.getText();
                        if (StringUtils.isNotEmpty(text)) {
                            m.setActionDown(text.toString());
                        }
                        Editable text1 = editText3.getText();
                        if (StringUtils.isNotEmpty(text1)) {
                            m.setActionUp(text1.toString());
                        }
                        keymap.put(id,m);
                        m.save();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initData(View root) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        int widthPixels = displayMetrics.widthPixels; // 屏幕宽度（像素）
        int heightPixels = displayMetrics.heightPixels; // 屏幕高度（像素）
        float density = displayMetrics.density; // 屏幕密度（0.75 / 1.0 / 1.5）
        int densityDpi = displayMetrics.densityDpi; // 屏幕密度DPI（120 / 160 / 240）
        Log.i(TAG, "widthPixels: "+widthPixels);
        Log.i(TAG, "heightPixels: "+heightPixels);
        Log.i(TAG, "density: "+density);
        Log.i(TAG, "densityDpi: "+densityDpi);



        keymap=new HashMap<>();
        List<KeyboardEntity> messageList = LitePal.where(" driveAdd =?", drive.getDriveAdd()).find(KeyboardEntity.class);
        for (int i = 1; i <= 12; i++) {
            int resID = getResources().getIdentifier("button" + i, "id", getActivity().getPackageName());
            if (messageList == null || messageList.size() == 0) {
                KeyboardEntity m = new KeyboardEntity();
                m.setDriveAdd(defaultAdd);
                m.setButtonId(resID);
                m.save();
            }

            Button button = root.findViewById(resID);
            //动态计算按钮高度
            int h =heightPixels/4-170;
            ConstraintSet constraintSet = new ConstraintSet() ;
            constraintSet.clone((ConstraintLayout) button.getParent());
            constraintSet.constrainHeight(button.getId(), (int) h);
            constraintSet.applyTo((ConstraintLayout) button.getParent());
            int id = button.getId();
            KeyboardEntity first = LitePal.where(" driveAdd =? and buttonId =?", drive.getDriveAdd(), id + "").findFirst(KeyboardEntity.class);
            if (first != null) {
                String buttonName = first.getButtonName();
                if (buttonName != null && buttonName.length() > 0) {
                    button.setText(buttonName);
                }
            }
            button.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "按钮id: "+resID+";按钮高度"+button.getHeight() );


                }
            });

            keymap.put(id,first);
            button.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    boolean clickable = toggle.isChecked();
                    if(clickable){
                        longClick(button);
                        return true;
                    }

                    //Button按下事件
                    KeyboardEntity keyboardEntity = keymap.get(view.getId());
                    if(keyboardEntity !=null){
                        liantian_new.senMsg(keyboardEntity.getActionDown());

                    }

                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    boolean clickable = toggle.isChecked();
                    if(clickable){
                        return true;
                    }

                    //Button抬起事件
                    KeyboardEntity keyboardEntity = keymap.get(view.getId());
                    if(keyboardEntity!=null ){
                        liantian_new.senMsg(keyboardEntity.getActionUp());
                    }
                 }
                return false;
            });


      /*      //监听按钮事件
            button.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    longClick(button);
                    return true;
                }
            });*/
             toggle = (ToggleButton) root.findViewById(R.id.toggleButton);
             toggle.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(R.color.purple_700)));
             toggle.setTextColor(getResources().getColor(R.color.white));
             toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (isChecked) {
                        toggle.setTextColor(getResources().getColor(R.color.orange));
                    } else {
                        toggle.setTextColor(getResources().getColor(R.color.white));
                    }
                }
            });

        }

    }


}
