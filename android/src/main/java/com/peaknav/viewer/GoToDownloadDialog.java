package com.peaknav.viewer;

import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.peaknav.R;
import com.peaknav.compatibility.NativeScreenCallerAndroid;

public class GoToDownloadDialog extends Fragment {

    private final float lat;
    private final float lon;
    private boolean shown = false;

    public GoToDownloadDialog(float lat, float lon) {
        this.lat = lat;
        this.lon = lon;
    }

    private void finish() {
        ((NativeScreenCallerAndroid) getNativeScreenCaller()).popStack();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        // view = inflater.inflate(R.layout., container, false);

        if (shown) { // only show this dialog once
            finish();
        }
        shown = true;

        // setFinishOnTouchOutside(false);

        // boolean finishOnDownloadStart = intent.getBooleanExtra("finishOnDownloadStart", true);

        DialogInterface.OnClickListener goToDownloadScreenDialog = (dialog, which) -> {
            switch (which) {
                case DialogInterface.BUTTON_POSITIVE:
                    getNativeScreenCaller().openMapDataDownloadChooser(lat, lon, true);
                    shown = false;
                    break;

                case DialogInterface.BUTTON_NEGATIVE:
                    shown = false;
                    finish();
                    break;
            }
        };
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(getContext());
        alertBuilder.setMessage(s("Missing_data_prompt"))
                .setPositiveButton(s("Yes"), goToDownloadScreenDialog)
                .setNegativeButton(s("No"), goToDownloadScreenDialog)
                .setCancelable(false)
                .show();

        return super.onCreateView(inflater, container, savedInstanceState);
    }

}
