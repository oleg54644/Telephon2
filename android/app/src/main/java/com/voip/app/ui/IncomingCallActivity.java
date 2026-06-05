package com.voip.app.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.voip.app.R;
import com.voip.app.service.VoipService;

public class IncomingCallActivity extends AppCompatActivity {

    private VoipService voipService;
    private boolean serviceBound = false;
    private String callId;
    private String fromNumber;
    private Ringtone ringtone;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            voipService = ((VoipService.LocalBinder) binder).getService();
            serviceBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (VoipService.BROADCAST_ACCEPTED.equals(action)) {
                stopRingtone();
                startActivity(new Intent(IncomingCallActivity.this, ActiveCallActivity.class)
                    .putExtra(VoipService.EXTRA_CALL_ID, callId)
                    .putExtra(VoipService.EXTRA_FROM, fromNumber));
                finish();
            } else if (VoipService.BROADCAST_ENDED.equals(action)
                    || VoipService.BROADCAST_REJECTED.equals(action)) {
                stopRingtone();
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Показать поверх экрана блокировки
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_incoming_call);

        callId = getIntent().getStringExtra(VoipService.EXTRA_CALL_ID);
        fromNumber = getIntent().getStringExtra(VoipService.EXTRA_FROM);

        TextView tvCaller = findViewById(R.id.tvCaller);
        Button btnAnswer = findViewById(R.id.btnAnswer);
        Button btnDecline = findViewById(R.id.btnDecline);

        tvCaller.setText("Звонит: " + fromNumber);

        btnAnswer.setOnClickListener(v -> {
            stopRingtone();
            if (serviceBound) voipService.answerCall(callId);
        });

        btnDecline.setOnClickListener(v -> {
            stopRingtone();
            if (serviceBound) voipService.rejectCall(callId);
            finish();
        });

        bindService(new Intent(this, VoipService.class), serviceConn, 0);
        registerReceiver(receiver, makeFilter(), Context.RECEIVER_NOT_EXPORTED);
        playRingtone();
    }

    private void playRingtone() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(this, uri);
            if (ringtone != null) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_RING,
                    am.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
                ringtone.play();
            }
        } catch (Exception ignored) {}
    }

    private void stopRingtone() {
        if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
    }

    private IntentFilter makeFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(VoipService.BROADCAST_ACCEPTED);
        f.addAction(VoipService.BROADCAST_ENDED);
        f.addAction(VoipService.BROADCAST_REJECTED);
        return f;
    }

    @Override
    protected void onDestroy() {
        stopRingtone();
        unregisterReceiver(receiver);
        if (serviceBound) unbindService(serviceConn);
        super.onDestroy();
    }
}
