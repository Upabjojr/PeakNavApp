package com.peaknav.views;

import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import android.os.Bundle;
import android.util.Base64;
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

        view = inflater.inflate(R.layout.fragment_app_info_android_view, container, false);

        WebView webView = view.findViewById(R.id.app_info_android_web_view);
        webView.getSettings().setJavaScriptEnabled(true);

        String htmlString = Gdx.files.internal("info/app_tutorial.html").readString();

        String get_image = "function get_image(k) {\n";
        String[] imgFiles = {"imageBase.jpg", "imageOptions.jpg", "imageOptionsSat.jpg", "imageBaseSat.jpg"};
        for (String imgFile : imgFiles) {
            byte[] imgBytes = Gdx.files.internal("info/" + imgFile).readBytes();
            String base64Img = Base64.encodeToString(imgBytes, Base64.DEFAULT);
            base64Img = base64Img.replace("\n", "");
            get_image += "if (k == '" + imgFile + "') data = 'data:image/jpeg;base64,"+base64Img+"';\n";
        }
        get_image += "\nlet img = new Image();\nimg.src = data;\nreturn img;\nconsole.log(k);\n}\n";

        htmlString = htmlString.replace("// OVERLOAD::get_image", get_image);

        webView.loadDataWithBaseURL(null, htmlString, "text/html", "UTF-8", null);

        Button buttonAppInfoBack = view.findViewById(R.id.button_app_info_back);
        buttonAppInfoBack.setText(s("Back"));
        buttonAppInfoBack.setOnClickListener(view -> ((NativeScreenCallerAndroid) getNativeScreenCaller()).popStack());

        return view;
    }
}