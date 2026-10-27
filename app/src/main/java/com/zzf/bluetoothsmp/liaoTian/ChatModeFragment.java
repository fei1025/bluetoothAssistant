package com.zzf.bluetoothsmp.liaoTian;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.zzf.bluetoothsmp.BluetoothTelemetry;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.databinding.FragmentLiantianNewBinding;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;

public class ChatModeFragment extends Fragment {
    private FragmentLiantianNewBinding binding;
    private ChatTranscriptController transcript;

    @Override
    public void onResume() {
        super.onResume();
        BluetoothTelemetry.logScreenView("chat", getClass().getSimpleName());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentLiantianNewBinding.inflate(inflater, container, false);
        Liantian_new activity = (Liantian_new) requireActivity();
        BluetoothDrive drive = activity.getDrive();
        if (drive != null) {
            transcript = new ChatTranscriptController(binding.getRoot(), drive.getDriveAdd(),
                    drive.getUuid(), activity.getSessionId(), () -> {
                Toast.makeText(requireContext(), R.string.chat_connection_ended, Toast.LENGTH_SHORT).show();
                activity.finishSession();
            });
            transcript.restoreState(savedInstanceState);
        }
        binding.send.setOnClickListener(view -> {
            String text = binding.inputText.getText().toString();
            if (!text.isEmpty() && activity.sendText(text)) binding.inputText.setText("");
        });
        applyImeInsets(binding.getRoot());
        return binding.getRoot();
    }

    private void applyImeInsets(View root) {
        View bar = binding.inputBar;
        int start = bar.getPaddingStart();
        int top = bar.getPaddingTop();
        int end = bar.getPaddingEnd();
        int bottom = bar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // The Activity already applies navigation-bar padding to the pager.
            Insets navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            bar.setPaddingRelative(start, top, end, bottom + Math.max(0, ime.bottom - navigation.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        if (transcript != null) transcript.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        if (transcript != null) {
            transcript.close();
            transcript = null;
        }
        binding = null;
        super.onDestroyView();
    }
}
