package com.zzf.bluetoothsmp.liaoTian;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * A [FragmentPagerAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
public class SectionsPagerAdapter extends FragmentStateAdapter {


    public SectionsPagerAdapter( FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }


    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            return new ChatModeFragment();
        }
        if (position == 1) {
            return new KeyboardFragment();
        }
        return com.zzf.bluetoothsmp.fragment.DebugFragment.newInstance(true);
    }

    @Override
    public int getItemCount() {
        return Liantian_new.TAB_TITLES.length;
    }









}
