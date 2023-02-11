package com.zzf.bluetoothsmp.liaoTian;

import android.os.Bundle;

import com.example.bluetoothsmp.R;
import com.example.bluetoothsmp.databinding.ActivityLiantianNewBinding;
import com.google.android.material.tabs.TabLayout;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class liantian_new extends AppCompatActivity {

private ActivityLiantianNewBinding binding;

    public  static Map<String, Fragment> mapView =new HashMap<>();
    public static List<Fragment> listFragment = new ArrayList<>();

    public static final int[] TAB_TITLES = new int[] {R.string.chat, R.string.keyboard};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

     binding = ActivityLiantianNewBinding.inflate(getLayoutInflater());
     setContentView(binding.getRoot());


        ViewPager2 viewPager = findViewById(R.id.view_pager);
        SectionsPagerAdapter adapter = new SectionsPagerAdapter(this);
        viewPager.setAdapter(adapter);
        TabLayout tabs = binding.tabs;

        new TabLayoutMediator(tabs, viewPager, new TabLayoutMediator.TabConfigurationStrategy() {
            @Override
            public void onConfigureTab(@NonNull TabLayout.Tab tab, int position) {
                // tab:当前处于选中状态的Tab对象
                // position:当前Tab所处的位置
                tab.setText(TAB_TITLES[position]);
            }
        }).attach();// 不要忘记attach()，否则没效果

        Toolbar toolbar = findViewById(R.id.lao_tian_toolbar);
        toolbar.setTitle("demo");
        setSupportActionBar(toolbar);

        initFragment();
    }

    public void initFragment(){
        // mapView.put("",new ChatModeFragment());
        //mapView.put("keyboard",new KeyboardFragment() );
        listFragment.clear();
        listFragment.add(new ChatModeFragment());
        listFragment.add(new KeyboardFragment());
    }
}