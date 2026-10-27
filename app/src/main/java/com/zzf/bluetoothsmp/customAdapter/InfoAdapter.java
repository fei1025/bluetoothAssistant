package com.zzf.bluetoothsmp.customAdapter;

import android.content.Intent;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.zzf.bluetoothsmp.Liao_tian;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.utils.DateUtils;
import com.zzf.bluetoothsmp.utils.ImageUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class InfoAdapter extends ListAdapter<BluetoothDrive, InfoAdapter.ViewHolder> {
    private final LruCache<String, Bitmap> avatars = new LruCache<>(24);
    private final Set<String> deleting = new HashSet<>();
    private OnClickDelete onClickDelete;

    private static final DiffUtil.ItemCallback<BluetoothDrive> DIFFER = new DiffUtil.ItemCallback<BluetoothDrive>() {
        @Override public boolean areItemsTheSame(@NonNull BluetoothDrive a, @NonNull BluetoothDrive b) {
            return Objects.equals(a.getDriveAdd(), b.getDriveAdd()) && Objects.equals(a.getUuid(), b.getUuid());
        }
        @Override public boolean areContentsTheSame(@NonNull BluetoothDrive a, @NonNull BluetoothDrive b) {
            return Objects.equals(a.getDriveName(), b.getDriveName())
                    && Objects.equals(a.getLastReceiveMsg(), b.getLastReceiveMsg())
                    && Objects.equals(a.getSenDate(), b.getSenDate());
        }
    };

    public InfoAdapter(List<BluetoothDrive> rows) {
        super(DIFFER);
        submitList(new ArrayList<>(rows));
    }

    public void setDeleting(BluetoothDrive drive, boolean active) {
        String key = key(drive);
        if (active) deleting.add(key); else deleting.remove(key);
        for (int i = 0; i < getCurrentList().size(); i++) {
            if (key.equals(key(getCurrentList().get(i)))) notifyItemChanged(i);
        }
    }

    public void clearPendingDeletes() {
        if (!deleting.isEmpty()) {
            deleting.clear();
            notifyDataSetChanged();
        }
    }

    private String key(BluetoothDrive drive) { return drive.getDriveAdd() + "\n" + String.valueOf(drive.getUuid()); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.info_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BluetoothDrive drive = getItem(position);
        String name = drive.getDriveName();
        if (name == null || name.trim().isEmpty()) name = drive.getDriveAdd();
        String avatarKey = key(drive) + "|" + name;
        Bitmap avatar = drive.getSystemImg();
        if (avatar == null) {
            avatar = avatars.get(avatarKey);
            if (avatar == null) {
                avatar = ImageUtils.defaultAvatar(name);
                avatars.put(avatarKey, avatar);
            }
        }
        holder.image.setImageBitmap(avatar);
        holder.title.setText(name);
        String content = drive.getLastReceiveMsg();
        holder.content.setText(content == null ? "" : content.substring(0, Math.min(content.length(), 256)));
        holder.time.setText(drive.getSenDate() == null ? "" : DateUtils.dateToStr(drive.getSenDate()));
        holder.delete.setEnabled(!deleting.contains(key(drive)));
        holder.contentView.setOnClickListener(view -> {
            int currentPosition = holder.getBindingAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION) return;
            BluetoothDrive current = getItem(currentPosition);
            Intent intent = new Intent(view.getContext(), Liao_tian.class);
            intent.putExtra("bluetoothName", current.getDriveName());
            intent.putExtra("bluetoothAdd", current.getDriveAdd());
            intent.putExtra("bluetoothUUid", current.getUuid());
            view.getContext().startActivity(intent);
        });
        holder.delete.setOnClickListener(view -> {
            int currentPosition = holder.getBindingAdapterPosition();
            if (currentPosition != RecyclerView.NO_POSITION && onClickDelete != null) {
                BluetoothDrive current = getItem(currentPosition);
                if (!deleting.contains(key(current))) onClickDelete.deleteItem(currentPosition, current);
            }
        });
    }

    public void setOnClickDelete(OnClickDelete listener) { onClickDelete = listener; }

    public interface OnClickDelete {
        void deleteItem(int position, BluetoothDrive drive);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final View contentView;
        final ImageView image;
        final TextView title;
        final TextView content;
        final TextView time;
        final Button delete;
        public ViewHolder(View view) {
            super(view);
            contentView = view.findViewById(R.id.LayoutId);
            image = view.findViewById(R.id.img1);
            title = view.findViewById(R.id.title);
            content = view.findViewById(R.id.content);
            time = view.findViewById(R.id.time);
            delete = view.findViewById(R.id.item_delete);
        }
    }
}
