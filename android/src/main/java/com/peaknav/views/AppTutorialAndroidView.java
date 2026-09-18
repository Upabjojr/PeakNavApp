package com.peaknav.views;

import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.badlogic.gdx.Gdx;
import com.peaknav.R;
import com.peaknav.compatibility.NativeScreenCallerAndroid;


public class AppTutorialAndroidView extends Fragment {

    private View view;

    public AppTutorialAndroidView() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        view = inflater.inflate(R.layout.fragment_app_tutorial_android_view, container, false);

        WebView webView = view.findViewById(R.id.app_tutorial_android_web_view);
        webView.getSettings().setJavaScriptEnabled(true);

        String htmlString = Gdx.files.internal("info/app_tutorial.html").readString();
        // The captions, in the device's language, from the app's own catalogue.
        htmlString = htmlString.replace("// OVERLOAD::get_string",
                com.peaknav.viewer.TutorialStrings.asJavaScript());

        // The pictures are read from the assets the app already ships, through the base URL, so
        // the page's own <img src="tutorial_base.jpg"> finds them: they used to be built into the
        // page as base64 data URLs, which meant holding every picture in memory twice over and
        // cost the app a kill for memory once the tutorial had more than a handful of slides.
        webView.loadDataWithBaseURL("file:///android_asset/info/", htmlString, "text/html", "UTF-8", null);

        Button buttonAppInfoBack = view.findViewById(R.id.button_app_tutorial_back);
        buttonAppInfoBack.setText(s("Back"));
        buttonAppInfoBack.setOnClickListener(view -> ((NativeScreenCallerAndroid) getNativeScreenCaller()).popStack());

        return view;
    }
}