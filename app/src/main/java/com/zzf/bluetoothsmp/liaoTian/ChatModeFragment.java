package com.zzf.bluetoothsmp.liaoTian;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.example.bluetoothsmp.databinding.FragmentLiantianNewBinding;

/**
 * A placeholder fragment containing a simple view.
 */
public class ChatModeFragment extends Fragment {

    private static final String ARG_SECTION_NUMBER = "section_number";

private FragmentLiantianNewBinding binding;

    public static Fragment newInstance(int index) {
        if(index == 1){
            return new KeyboardFragment();
        }
        ChatModeFragment fragment = new ChatModeFragment();
        Bundle bundle = new Bundle();
        bundle.putInt(ARG_SECTION_NUMBER, index);
        fragment.setArguments(bundle);

        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {

      binding = FragmentLiantianNewBinding.inflate(inflater, container, false);
      View root = binding.getRoot();


        return root;
    }

@Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}