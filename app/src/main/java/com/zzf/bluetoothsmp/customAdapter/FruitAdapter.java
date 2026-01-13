package com.zzf.bluetoothsmp.customAdapter;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.zzf.bluetoothsmp.Fruit;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.utils.ImageUtils;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class FruitAdapter extends RecyclerView.Adapter<FruitAdapter.ViewHolder> {
    private List<Fruit> mFruitList;
    private ViewGroup parent;
    private int viewType;

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        //用来创建ViewHolder实例，再将加载好的布局传入构造函数，最后返回ViewHolder实例
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.fruit_item, null);
        return new ViewHolder(view);
    }

    public FruitAdapter(List<Fruit> fruitList) {
        mFruitList = fruitList;
    }




    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, @SuppressLint("RecyclerView") int position) {
        //用于对RecyclerView的子项进行赋值，会在每个子项滚动到屏幕内的时候执行
        Fruit fruit = mFruitList.get(position);
        holder.tv_name.setText(fruit.getName());
        holder.tv_address.setText(fruit.getAddress());
        holder.tv_rssi.setText(fruit.getRssi());
        holder.tv_stateName.setText(fruit.getStateName());
        holder.tv_bluetoothTypeName.setText(fruit.getBluetoothTypeName());
        String name =fruit.getName();
        if(name ==null || name.length()==0){
            name=fruit.getAddress();
        }
        Bitmap bitmap = ImageUtils.defaultAvatar(name);
        if(bitmap!=null){
            holder.fruitImage.setImageBitmap(bitmap);
        }
        if (1 == fruit.getIsConnect()) {
            //隐藏按钮
            //holder.button.setVisibility(View.INVISIBLE);.
            holder.button.setEnabled(false);
            holder.button.setText(R.string.do_not_connect);
        } else {
            holder.button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemDeleteListener.OnItemClick(position);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return mFruitList.size();
    }

    /**
     * 删除按钮的监听接口
     */
    public interface onItemDeleteListener {
        void OnItemClick(int i);
    }

    private onItemDeleteListener mOnItemDeleteListener;

    public void setOnItemClickListener(onItemDeleteListener mOnItemDeleteListener) {
        this.mOnItemDeleteListener = mOnItemDeleteListener;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView fruitImage;
        TextView tv_name;
        TextView tv_address;
        TextView tv_stateName;
        TextView tv_bluetoothTypeName;
        TextView tv_rssi;
        Button button;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            fruitImage = itemView.findViewById(R.id.imageView);
            tv_name = itemView.findViewById(R.id.tv_name);
            tv_bluetoothTypeName = itemView.findViewById(R.id.tv_bluetoothTypeName);
            tv_stateName = itemView.findViewById(R.id.tv_stateName);
            tv_address = itemView.findViewById(R.id.tv_address);
            tv_rssi = itemView.findViewById(R.id.tv_rssi);
            button = itemView.findViewById(R.id.button_connect);
        }
    }
}

