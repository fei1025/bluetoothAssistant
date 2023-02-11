package com.zzf.bluetoothsmp.liaoTian;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.utils.ToastUtil;

public class KeyboardFragment extends Fragment {

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        View root =inflater.inflate(R.layout.frament_keyboard_moder,container,false);


        for (int i = 1; i <= 12; i++) {
            int resID = getResources().getIdentifier("button" + i, "id", getActivity().getPackageName());
            Button button = root.findViewById(resID);
            final int index = i;
            button.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    longClick(view);
                    return true;
                }
            });
        }

      /*  Button button1 = (Button) root.findViewById(R.id.button1);
        button1.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                // Do your actions here
                longClick(view);
                return true;
            }
        });*/
        return root;
    }

    public void longClick(View v){
        ToastUtil.toastWord("长按了");

    }
}
