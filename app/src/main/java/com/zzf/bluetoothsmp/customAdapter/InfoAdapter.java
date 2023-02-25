package com.zzf.bluetoothsmp.customAdapter;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.BluetoothObject;
import com.zzf.bluetoothsmp.Liao_tian;
import com.zzf.bluetoothsmp.MyApplication;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.utils.DateUtils;
import com.zzf.bluetoothsmp.utils.ImageUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

//https://github.com/mcxtzhang/SwipeDelMenuLayout 右滑删除
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
        Bitmap systemImg = bluetoothDrive.getSystemImg();
        if(systemImg == null){
            systemImg= ImageUtils.defaultAvatar(bluetoothDrive.getDriveName());
        }
        holder.img1.setImageBitmap(systemImg);
        holder.content.setText(bluetoothDrive.getLastReceiveMsg());
        holder.title.setText(bluetoothDrive.getDriveName());
        holder.time.setText(DateUtils.dateToStr(bluetoothDrive.getSenDate()));
        holder.mLinearLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //聊天记录
                int adapterPosition = holder.getAdapterPosition();
                BluetoothDrive bluetoothDrive1 = list.get(adapterPosition);
                //Intent intent = new Intent(v.getContext(), Liantian_new.class);
                Intent intent = new Intent(v.getContext(), Liao_tian.class);
                intent.putExtra("bluetoothName",bluetoothDrive1.getDriveName());
                intent.putExtra("bluetoothAdd",bluetoothDrive1.getDriveAdd());
                intent.putExtra("bluetoothUUid",bluetoothDrive1.getUuid());
                intent.putExtra("infoType","0");
                BluetoothDrive drive=new BluetoothDrive();
                drive.setDriveName(bluetoothDrive1.getDriveName());
                drive.setDriveAdd(bluetoothDrive1.getDriveAdd());
                drive.setUuid(bluetoothDrive1.getUuid());
                intent.putExtra("BluetoothDrive",drive);
                v.getContext().startActivity(intent);
            }
        });

/*        holder.mLinearLayout.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                int adapterPosition = holder.getAdapterPosition();
                BluetoothDrive bluetoothDrive1 = list.get(adapterPosition);
                ToastUtil.toastWord(v.getContext(), "长按了");
                return true;
            }
        });*/
        holder.itemDelete.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                int adapterPosition = holder.getAdapterPosition();
                BluetoothDrive remove = list.remove(adapterPosition);
                notifyDataSetChanged();
                onClickDelete.deleteItem(adapterPosition,remove);
            }
        });
    }


    @Override
    public int getItemCount() {
        return list.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ConstraintLayout mLinearLayout;
        View fruitView;
        ImageView img1;
        TextView title;
        TextView time;
        TextView content;
        Button itemDelete;


        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            fruitView=itemView;
            mLinearLayout = itemView.findViewById(R.id.LayoutId);
            img1 = itemView.findViewById(R.id.img1);
            title = itemView.findViewById(R.id.title);
            content = itemView.findViewById(R.id.content);
            time = itemView.findViewById(R.id.time);
            itemDelete = itemView.findViewById(R.id.item_delete);

        }
    }

    private OnClickDelete onClickDelete;

    public void setOnClickDelete(OnClickDelete onClickDelete) {
        this.onClickDelete = onClickDelete;
    }

    public interface OnClickDelete{
        void deleteItem(int adapterPosition,  BluetoothDrive remove);
    }



}
