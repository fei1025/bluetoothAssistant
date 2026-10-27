package com.zzf.bluetoothsmp.customAdapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.DebugLogStore;
import com.zzf.bluetoothsmp.databinding.ItemDebugLogBinding;
import com.zzf.bluetoothsmp.entity.LogItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DebugLogAdapter extends RecyclerView.Adapter<DebugLogAdapter.LogViewHolder> {

    private final DebugLogStore logItems = new DebugLogStore(500, 4L * 1024 * 1024);
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean showHex = true;

    public void setShowHex(boolean showHex) {
        this.showHex = showHex;
        notifyDataSetChanged();
    }

    public boolean isShowHex() {
        return showHex;
    }

    public void addLog(LogItem item) {
        addLogs(java.util.Collections.singletonList(item));
    }

    public void addLogs(List<LogItem> items) {
        // One immutable-size batch notification covers insertions AND all byte-budget evictions.
        for (LogItem item : items) logItems.add(item);
        notifyDataSetChanged();
    }

    public void replaceLogs(List<LogItem> items) {
        logItems.clear();
        // Store inserts at the head; retain the snapshot's newest-first ordering.
        for (int index = items.size() - 1; index >= 0; index--) logItems.add(items.get(index));
        notifyDataSetChanged();
    }

    public void updateStatus(String messageId, String status) {
        for (int index = 0; index < logItems.size(); index++) {
            LogItem item = logItems.get(index);
            if (messageId.equals(item.getMessageId())) {
                item.setSendStatus(status); notifyItemChanged(index); return;
            }
        }
    }

    public void clearLogs() {
        logItems.clear();
        notifyDataSetChanged();
    }

    public List<LogItem> getLogs() {
        return logItems.snapshot();
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDebugLogBinding binding = ItemDebugLogBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new LogViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        holder.bind(logItems.get(position));
    }

    @Override
    public int getItemCount() {
        return logItems.size();
    }

    class LogViewHolder extends RecyclerView.ViewHolder {
        private final ItemDebugLogBinding binding;

        LogViewHolder(ItemDebugLogBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(LogItem item) {
            // Timestamp
            binding.timestamp.setText(timeFormat.format(item.getTimestamp()));

            // Direction
            if (item.isSent()) {
                binding.direction.setText(item.getSendStatus() == null
                        ? itemView.getContext().getString(R.string.debug_log_send)
                        : itemView.getContext().getString(R.string.debug_log_send) + " " + item.getSendStatus());
                binding.direction.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.debug_hex));
                binding.getRoot().setBackgroundColor(ContextCompat.getColor(itemView.getContext(), R.color.debug_log_sent));
            } else {
                binding.direction.setText(R.string.debug_log_receive);
                binding.direction.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.debug_ascii));
                binding.getRoot().setBackgroundColor(ContextCompat.getColor(itemView.getContext(), R.color.debug_log_received));
            }

            // Content based on view mode
            if (showHex) {
                binding.hexContent.setVisibility(View.VISIBLE);
                binding.asciiContent.setVisibility(View.VISIBLE);
                binding.hexContent.setText(item.getHexContent());
                binding.asciiContent.setText(item.getAsciiContent());
            } else {
                binding.hexContent.setVisibility(View.GONE);
                binding.asciiContent.setVisibility(View.VISIBLE);
                binding.asciiContent.setText(item.getContent());
            }
        }
    }
}
