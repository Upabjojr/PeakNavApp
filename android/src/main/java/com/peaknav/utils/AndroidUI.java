package com.peaknav.utils;

import static com.peaknav.utils.PeakNavUtils.s;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class AndroidUI {

    private Context context;
    private static AndroidUI instance = null;

    private AndroidUI(Context context) {
        this.context = context;
    }

    public static void setInstance(Context context) {
        instance = new AndroidUI(context);
    }

    public static AndroidUI getInstance() {
        return instance;
    }

    public boolean isNetworkConnected() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }
        return false;
    }

    public static void alertMessage(String message, Context context, boolean terminate) {
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(context);
        alertBuilder.setMessage(message)
                .setPositiveButton(s("OK"), (dialogInterface, i) -> {
                    if (context instanceof Activity && terminate) {
                        ((Activity)context).finish();
                    }
                })
                .setCancelable(false);
        // .show();
        AlertDialog alert = alertBuilder.create();
        alert.show();
    }

}
