package com.peaknav.views;

import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;

import com.badlogic.gdx.Gdx;
import com.peaknav.R;
import com.peaknav.compatibility.NativeScreenCallerAndroid;


public class AppInfoAndroidView extends Fragment {

    private View view;

    public AppInfoAndroidView() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        view = inflater.inflate(R.layout.fragment_app_info_android_view, container, false);

        // view.setContentView(R.layout.fragment_app_info_android_view);

        WebView appInfo = view.findViewById(R.id.app_info_android_web_view);

        String text = Gdx.files.internal("info/app_info.html").readString();
        appInfo.loadData(text, "text/html", "utf-8");

        Button buttonAppInfoBack = view.findViewById(R.id.button_app_info_back);
        buttonAppInfoBack.setText(s("Back"));
        buttonAppInfoBack.setOnClickListener(view -> ((NativeScreenCallerAndroid) getNativeScreenCaller()).popStack());

        return view;
    }
}