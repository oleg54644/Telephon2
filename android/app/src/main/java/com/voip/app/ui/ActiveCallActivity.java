package com.voip.app.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.voip.app.R;
import com.voip.app.service.VoipService;

public class ActiveCallActivity extends AppCompatActivity {

    private VoipService voipService;
    private boolean serviceBound = false;
    private String callId;
    private String peerNumber;

    private TextView tvPeer, tvTimer;
    private Button btnHangup, btnSpeaker, btnMute;
    private boolean isSpeaker = false;
    private boolean isMuted = false;

    private final Handler timerHandler = new Handler();
    private long callStartTime;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsed = (System.currentTimeMillis() - callStartTime) / 1000;
            long min = elapsed / 60, sec = elapsed % 60;
            tvTimer.setText(String.format("%02d:%02d", min, sec));
            timerHandler.postDelayed(this, 1000);
        }
    };

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
            if (VoipService.BROADCAST_ENDED.equals(intent.getAction())) {
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active_call);

        callId = getIntent().getStringExtra(VoipService.EXTRA_CALL_ID);
        peerNumber = getIntent().getStringExtra(VoipService.EXTRA_FROM);

        tvPeer = findViewById(R.id.tvPeer);
        tvTimer = findViewById(R.id.tvTimer);
        btnHangup = findViewById(R.id.btnHangup);
        btnSpeaker = findViewById(R.id.btnSpeaker);
        btnMute = findViewById(R.id.btnMute);

        // Показываем номер + имя если есть
        String peerName = getIntent().getStringExtra("peer_name");
        if (peerName != null && !peerName.isEmpty()) {
            tvPeer.setText(peerName + " (" + peerNumber + ")");
        } else {
            tvPeer.setText(peerNumber != null ? peerNumber : "Звонок");
        }

        btnHangup.setOnClickListener(v -> {
            if (serviceBound) voipService.hangup();
            finish();
        });

        btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        btnMute.setOnClickListener(v -> toggleMute());

        callStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        bindService(new Intent(this, VoipService.class), serviceConn, 0);
        IntentFilter f = new IntentFilter(VoipService.BROADCAST_ENDED);
        registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
    }

    private void toggleSpeaker() {
        isSpeaker = !isSpeaker;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.setSpeakerphoneOn(isSpeaker);
        btnSpeaker.setText(isSpeaker ? "🔊 Громко" : "📱 Трубка");
    }

    private void toggleMute() {
        isMuted = !isMuted;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.setMicrophoneMute(isMuted);
        btnMute.setText(isMuted ? "🔇 Выкл. микр." : "🎙 Микрофон");
    }

    @Override
    protected void onDestroy() {
        timerHandler.removeCallbacks(timerRunnable);
        unregisterReceiver(receiver);
        if (serviceBound) unbindService(serviceConn);
        super.onDestroy();
    }
}








