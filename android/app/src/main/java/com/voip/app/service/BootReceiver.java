package com.voip.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
            || "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {

            SharedPreferences prefs = context.getSharedPreferences("voip_prefs", Context.MODE_PRIVATE);
            String serverUrl = prefs.getString("server_url", null);

            if (serverUrl != null) {
                Log.i("BootReceiver", "Auto-starting VoIP service");
                Intent service = new Intent(context, VoipService.class);
                service.putExtra("server_url", serverUrl);
                context.startForegroundService(service);
            }
        }
    }
}
