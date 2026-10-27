package com.zzf.bluetoothsmp.customAdapter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.zzf.bluetoothsmp.BluetoothSendStatus;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.utils.HexUtils;
import com.zzf.bluetoothsmp.utils.ImageUtils;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/** Rendering has no persistence side effects; anonymous devices use their MAC as their label. */
public class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.ViewHolder> {
    private static final int TEXT_PREVIEW_CHARS = 2048;
    private static final int HEX_PREVIEW_BYTES = 128;
    private final List<Msg> messages;
    private final DateFormat timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
    private Bitmap localAvatar;
    private Bitmap remoteAvatar;

    public MsgAdapter(List<Msg> messages) {
        this.messages = messages;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.msg_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Msg message = messages.get(position);
        boolean received = message.getType() == Msg.TYPE_RECEIVED;
        holder.left.setVisibility(received ? View.VISIBLE : View.GONE);
        holder.right.setVisibility(received ? View.GONE : View.VISIBLE);
        String content = message.getContent() == null ? "" : message.getContent();
        String preview = content.length() > TEXT_PREVIEW_CHARS
                ? content.substring(0, TEXT_PREVIEW_CHARS) + "…" : content;
        (received ? holder.leftMessage : holder.rightMessage).setText(preview);
        if (received) {
            if (remoteAvatar == null) {
                String name = message.getBluetoothName();
                if (name == null || name.trim().isEmpty()) name = message.getBluetoothAdd();
                remoteAvatar = ImageUtils.defaultAvatar(name);
            }
            holder.leftImage.setImageBitmap(remoteAvatar);
        } else {
            if (localAvatar == null) localAvatar = ImageUtils.defaultAvatar(StaticObject.myBluetoothName);
            holder.rightImage.setImageBitmap(localAvatar);
        }
        Date time = message.getSenTime();
        String timestamp = time == null ? "" : timeFormat.format(time);
        int label = received ? R.string.chat_received : statusLabel(message.getSendStatus());
        String metadata = timestamp + " · " + holder.itemView.getContext().getString(label);
        if (message.getTextEncoding() != null) metadata += " · " + message.getTextEncoding().name();
        holder.metadata.setText(metadata);
        byte[] payload = message.getPayload();
        holder.raw.setVisibility(payload == null ? View.GONE : View.VISIBLE);
        if (payload != null) {
            byte[] previewBytes = payload.length <= HEX_PREVIEW_BYTES ? payload : Arrays.copyOf(payload, HEX_PREVIEW_BYTES);
            holder.raw.setText("HEX: " + HexUtils.bytesToHex(previewBytes)
                    + (payload.length > HEX_PREVIEW_BYTES ? " … (" + payload.length + " B)" : ""));
        }
        holder.raw.setOnLongClickListener(view -> {
            copy(view.getContext(), view.getContext().getString(R.string.chat_copy_hex),
                    HexUtils.bytesToHex(message.getPayload()));
            return true;
        });
        holder.raw.setContentDescription(holder.raw.getText() + " · "
                + holder.itemView.getContext().getString(R.string.chat_copy_hex));
        (received ? holder.leftMessage : holder.rightMessage).setOnLongClickListener(view -> {
            copy(view.getContext(), "", message.getContent());
            return true;
        });
    }

    private int statusLabel(BluetoothSendStatus status) {
        if (status == null) return R.string.chat_status_unknown;
        switch (status) {
            case QUEUED: return R.string.chat_status_queued;
            case SENDING: return R.string.chat_status_sending;
            case FAILED: return R.string.chat_status_failed;
            case CANCELED: return R.string.chat_status_canceled;
            default: return R.string.chat_status_sent;
        }
    }

    private void copy(Context context, String label, String content) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, content == null ? "" : content));
            Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() { return messages.size(); }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final View left;
        final View right;
        final TextView leftMessage;
        final TextView rightMessage;
        final ImageView leftImage;
        final ImageView rightImage;
        final TextView metadata;
        final TextView raw;

        ViewHolder(View view) {
            super(view);
            left = view.findViewById(R.id.left_layout);
            right = view.findViewById(R.id.right_layout);
            leftMessage = view.findViewById(R.id.left_msg);
            rightMessage = view.findViewById(R.id.right_msg);
            leftImage = view.findViewById(R.id.left_img);
            rightImage = view.findViewById(R.id.right_img);
            metadata = view.findViewById(R.id.message_metadata);
            raw = view.findViewById(R.id.message_raw);
        }
    }
}
