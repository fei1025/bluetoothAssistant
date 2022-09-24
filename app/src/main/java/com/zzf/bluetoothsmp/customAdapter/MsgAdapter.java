package com.zzf.bluetoothsmp.customAdapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.entity.Msg;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.ViewHolder> {
    private List<Msg> MsgList;

    public MsgAdapter(List<Msg> msgList){
        MsgList=msgList;

    }


    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View inflate = LayoutInflater.from(parent.getContext()).inflate(R.layout.msg_item, parent, false);
        return new ViewHolder(inflate);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Msg msg  = MsgList.get(position);
        if(msg.getType() == Msg.TYPE_RECEIVED){
            holder.left_layout.setVisibility(View.VISIBLE);
            holder.right_layout.setVisibility(View.GONE);
            holder.left_msg.setText(msg.getContent());
        }else  if (msg.getType() == Msg.TYPE_SENT){
            holder.right_layout.setVisibility(View.VISIBLE);
            holder.left_layout.setVisibility(View.GONE);
            holder.right_msg.setText(msg.getContent());
        }
    }

    @Override
    public int getItemCount() {
        return MsgList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder{
        LinearLayout left_layout;
        LinearLayout right_layout;
        TextView left_msg;
        TextView right_msg;
        public ViewHolder(@NonNull View view) {
            super(view);
             left_layout = (LinearLayout)view.findViewById(R.id.left_layout);
             right_layout = (LinearLayout)view.findViewById(R.id.right_layout);
             left_msg = (TextView)view.findViewById(R.id.left_msg);
             right_msg = (TextView)view.findViewById(R.id.right_msg);

        }
    }
}
