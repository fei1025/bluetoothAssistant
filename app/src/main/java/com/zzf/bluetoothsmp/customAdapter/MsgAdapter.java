package com.zzf.bluetoothsmp.customAdapter;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.utils.ImageUtils;

import org.litepal.LitePal;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.ViewHolder> {
    private List<Msg> MsgList;
    public Bitmap myBitmap;
    public Bitmap outBitmap;

    public MsgAdapter(List<Msg> msgList) {
        MsgList = msgList;
        myBitmap=null;
        outBitmap=null;
    }


    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View inflate = LayoutInflater.from(parent.getContext()).inflate(R.layout.msg_item, parent, false);
        return new ViewHolder(inflate);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Msg msg = MsgList.get(position);
        String bluetoothName = msg.getBluetoothName();
        if (msg.getType() == Msg.TYPE_RECEIVED) {
            holder.left_layout.setVisibility(View.VISIBLE);
            holder.right_layout.setVisibility(View.GONE);
            holder.left_msg.setText(msg.getContent());
            if (outBitmap == null) {
                if(bluetoothName == null || bluetoothName.length() ==0){
                    bluetoothName=msg.getBluetoothAdd();
                }
                BluetoothDrive messageList = LitePal.where("driveAdd = ? and uuid=? ",msg.getBluetoothAdd(),msg.getSendUuid()).findFirst(BluetoothDrive.class);
                if(messageList == null ){
                    BluetoothDrive bluetoothDrive=new BluetoothDrive();
                    bluetoothDrive.setDriveAdd(msg.getBluetoothAdd());
                    bluetoothDrive.setUuid(msg.getSendUuid());
                    bluetoothDrive.setDriveName(msg.getBluetoothName());
                    bluetoothDrive.setSystemImg(outBitmap);
                    bluetoothDrive.save();
                }else {
                    if(!messageList.getDriveName().equals(bluetoothName)){
                        messageList.setDriveName(bluetoothName);
                        messageList.save();
                    }
                }
                outBitmap = ImageUtils.defaultAvatar(bluetoothName);

            }
            holder.left_img.setImageBitmap(outBitmap);
        } else if (msg.getType() == Msg.TYPE_SENT) {
            holder.right_layout.setVisibility(View.VISIBLE);
            holder.left_layout.setVisibility(View.GONE);
            holder.right_msg.setText(msg.getContent());
            if (myBitmap == null) {
                myBitmap = ImageUtils.defaultAvatar(StaticObject.myBluetoothName);
            }
            holder.right_img.setImageBitmap(myBitmap);
        }

    }

    @Override
    public int getItemCount() {
        return MsgList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        LinearLayout left_layout;
        LinearLayout right_layout;
        ImageView left_img;
        ImageView right_img;
        TextView left_msg;
        TextView right_msg;

        public ViewHolder(@NonNull View view) {
            super(view);
            left_layout = (LinearLayout) view.findViewById(R.id.left_layout);
            right_layout = (LinearLayout) view.findViewById(R.id.right_layout);
            left_msg = (TextView) view.findViewById(R.id.left_msg);
            right_msg = (TextView) view.findViewById(R.id.right_msg);
            left_img = view.findViewById(R.id.left_img);
            right_img = view.findViewById(R.id.right_img);
        }
    }
}
