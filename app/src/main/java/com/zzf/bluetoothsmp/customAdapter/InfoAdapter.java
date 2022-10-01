package com.zzf.bluetoothsmp.customAdapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.utils.DateUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class InfoAdapter extends RecyclerView.Adapter<InfoAdapter.ViewHolder> {

    private List<BluetoothDrive> list;

    public InfoAdapter(List<BluetoothDrive> msgList) {
        list = msgList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View inflate = LayoutInflater.from(parent.getContext()).inflate(R.layout.info_item, parent, false);
        return new ViewHolder(inflate);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BluetoothDrive bluetoothDrive = list.get(position);
        holder.img1.setImageBitmap(bluetoothDrive.getSystemImg());
        holder.content.setText(bluetoothDrive.getLastReceiveMsg());
        holder.title.setText(bluetoothDrive.getDriveName());
        holder.time.setText(DateUtils.dateToStr(bluetoothDrive.getSenDate()));
        holder.title.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int adapterPosition = holder.getAdapterPosition();
                BluetoothDrive bluetoothDrive1 = list.get(adapterPosition);
                ToastUtil.toastWord(v.getContext(), bluetoothDrive1.getDriveName());
            }
        });

        holder.fruitView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int adapterPosition = holder.getAdapterPosition();
                BluetoothDrive bluetoothDrive1 = list.get(adapterPosition);
                ToastUtil.toastWord(v.getContext(), "点击全部了");

            }
        });
    }



    @Override
    public int getItemCount() {
        return list.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        View fruitView;
        ImageView img1;
        TextView title;
        TextView time;
        TextView content;


        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            fruitView=itemView;
            img1 = itemView.findViewById(R.id.img1);
            title = itemView.findViewById(R.id.title);
            content = itemView.findViewById(R.id.content);
            time = itemView.findViewById(R.id.time);

        }
    }
}
