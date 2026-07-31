package com.zzf.bluetoothsmp.customAdapter;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.zzf.bluetoothsmp.BluetoothConnectionState;
import com.zzf.bluetoothsmp.Fruit;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.utils.ImageUtils;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class FruitAdapter extends RecyclerView.Adapter<FruitAdapter.ViewHolder> {
    private static final int VIEW_TYPE_DEVICE = 0;
    private static final int VIEW_TYPE_SECTION = 1;
    private List<Fruit> mFruitList;

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == VIEW_TYPE_SECTION
                ? R.layout.fruit_section_item : R.layout.fruit_item;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new ViewHolder(view, viewType == VIEW_TYPE_SECTION);
    }

    public FruitAdapter(List<Fruit> fruitList) {
        mFruitList = fruitList;
    }




    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, @SuppressLint("RecyclerView") int position) {
        if (getItemViewType(position) == VIEW_TYPE_SECTION) {
            holder.sectionTitle.setText(holder.sectionTitle.getContext().getString(
                    sectionTitleFor(position)));
            return;
        }
        Fruit fruit = getFruitAtAdapterPosition(position);
        holder.tv_name.setText(fruit.getDisplayName());
        holder.tv_address.setText(fruit.getAddress());
        holder.tv_rssi.setText(fruit.getRssi() == null ? "" : fruit.getRssi());
        holder.tv_stateName.setText(fruit.getStateName());
        holder.tv_bluetoothTypeName.setText(fruit.getBluetoothTypeName());
        holder.favorite.setText(fruit.isFavorite() ? "★" : "☆");
        holder.favorite.setOnClickListener(v -> {
            if (mOnFavoriteClickListener != null) {
                mOnFavoriteClickListener.OnFavoriteClick(fruit);
            }
        });
        holder.itemView.setOnLongClickListener(v ->
                mOnLongClickListener != null && mOnLongClickListener.OnLongClick(fruit));
        String name = fruit.getDisplayName();
        if(name ==null || name.length()==0){
            name=fruit.getAddress();
        }
        Bitmap bitmap = ImageUtils.defaultAvatar(name);
        if(bitmap!=null){
            holder.fruitImage.setImageBitmap(bitmap);
        }
        holder.button.setOnClickListener(null);
        BluetoothConnectionState connectionState = fruit.getConnectionState();
        if (connectionState == BluetoothConnectionState.CONNECTED) {
            holder.button.setEnabled(true);
            holder.button.setText(R.string.open_session);
            holder.button.setOnClickListener(v -> {
                if (mOnItemDeleteListener != null) {
                    mOnItemDeleteListener.OnItemClick(fruit);
                }
            });
        } else if (connectionState == BluetoothConnectionState.CONNECTING
                || connectionState == BluetoothConnectionState.PAIRING
                || connectionState == BluetoothConnectionState.RECONNECTING) {
            holder.button.setEnabled(true);
            holder.button.setText(R.string.cancel_connection);
            holder.button.setOnClickListener(v -> {
                if (mOnItemDeleteListener != null) {
                    mOnItemDeleteListener.OnItemClick(fruit);
                }
            });
        } else {
            holder.button.setEnabled(true);
            holder.button.setText(R.string.connection);
            holder.button.setOnClickListener(v -> {
                if (mOnItemDeleteListener != null) {
                    mOnItemDeleteListener.OnItemClick(fruit);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return mFruitList.size() + sectionCount();
    }

    @Override
    public int getItemViewType(int position) {
        return isSectionPosition(position) ? VIEW_TYPE_SECTION : VIEW_TYPE_DEVICE;
    }

    private int pairedCount() {
        int count = 0;
        for (Fruit fruit : mFruitList) {
            if (isPaired(fruit)) {
                count++;
            }
        }
        return count;
    }

    private int sectionCount() {
        int paired = pairedCount();
        return (paired > 0 ? 1 : 0) + (mFruitList.size() - paired > 0 ? 1 : 0);
    }

    private boolean isSectionPosition(int position) {
        int paired = pairedCount();
        if (paired > 0 && position == 0) {
            return true;
        }
        int unpairedHeaderPosition = (paired > 0 ? paired + 1 : 0);
        return mFruitList.size() > paired && position == unpairedHeaderPosition;
    }

    private Fruit getFruitAtAdapterPosition(int position) {
        int paired = pairedCount();
        if (paired > 0) {
            if (position <= paired) {
                return mFruitList.get(position - 1);
            }
            return mFruitList.get(position - 2);
        }
        return mFruitList.get(position - 1);
    }

    private int sectionTitleFor(int position) {
        return position == 0 && pairedCount() > 0
                ? R.string.paired_devices : R.string.nearby_devices;
    }

    private boolean isPaired(Fruit fruit) {
        return fruit.getState() != null
                && fruit.getState() == android.bluetooth.BluetoothDevice.BOND_BONDED;
    }

    /**
     * 删除按钮的监听接口
     */
    public interface onItemDeleteListener {
        void OnItemClick(Fruit fruit);
    }

    private onItemDeleteListener mOnItemDeleteListener;

    public void setOnItemClickListener(onItemDeleteListener mOnItemDeleteListener) {
        this.mOnItemDeleteListener = mOnItemDeleteListener;
    }

    public interface OnFavoriteClickListener {
        void OnFavoriteClick(Fruit fruit);
    }

    private OnFavoriteClickListener mOnFavoriteClickListener;

    public void setOnFavoriteClickListener(OnFavoriteClickListener listener) {
        this.mOnFavoriteClickListener = listener;
    }

    public interface OnLongClickListener {
        boolean OnLongClick(Fruit fruit);
    }

    private OnLongClickListener mOnLongClickListener;

    public void setOnLongClickListener(OnLongClickListener listener) {
        this.mOnLongClickListener = listener;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView fruitImage;
        TextView tv_name;
        TextView tv_address;
        TextView tv_stateName;
        TextView tv_bluetoothTypeName;
        TextView tv_rssi;
        TextView favorite;
        TextView sectionTitle;
        Button button;

        public ViewHolder(@NonNull View itemView, boolean section) {
            super(itemView);
            if (section) {
                sectionTitle = itemView.findViewById(R.id.section_title);
                return;
            }
            fruitImage = itemView.findViewById(R.id.imageView);
            tv_name = itemView.findViewById(R.id.tv_name);
            tv_bluetoothTypeName = itemView.findViewById(R.id.tv_bluetoothTypeName);
            tv_stateName = itemView.findViewById(R.id.tv_stateName);
            tv_address = itemView.findViewById(R.id.tv_address);
            tv_rssi = itemView.findViewById(R.id.tv_rssi);
            favorite = itemView.findViewById(R.id.button_favorite);
            button = itemView.findViewById(R.id.button_connect);
        }
    }
}

