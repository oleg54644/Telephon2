package com.voip.app.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.voip.app.R;
import com.voip.app.model.CallRecord;
import com.voip.app.service.VoipService;

public class MainActivity extends AppCompatActivity {

    // ★ Фиксированный сервер — всегда подключается сюда
    private static final String FIXED_SERVER = "ws://45.128.204.171:8765";

    private static final int PERM_REQUEST = 100;

    private TextView tvStatus, tvMyNumber, tvServerStatus;
    private EditText etMyName, etCallTo;
    private Button btnCall, btnHangup, btnHistory;

    private VoipService voipService;
    private boolean serviceBound = false;
    private SharedPreferences prefs;

    // Для подсчёта длительности звонка
    private long callStartTime = 0;
    private String currentCallee = null;
    private String currentCallType = null;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            voipService = ((VoipService.LocalBinder) binder).getService();
            serviceBound = true;
            updateUI();
            // Если сервис уже подключён до биндинга — сразу показываем номер
            if (voipService.getMyNumber() != null) {
                runOnUiThread(() -> {
                    tvMyNumber.setText("Ваш номер: " + voipService.getMyNumber());
                    tvServerStatus.setText("● Подключено");
                });
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            voipService = null;
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case VoipService.BROADCAST_REGISTERED:
                    String num = intent.getStringExtra("number");
                    runOnUiThread(() -> {
                        if (num != null && !num.isEmpty()) {
                            tvMyNumber.setText("Ваш номер: " + num);
                        } else if (serviceBound && voipService != null && voipService.getMyNumber() != null) {
                            tvMyNumber.setText("Ваш номер: " + voipService.getMyNumber());
                        }
                        tvServerStatus.setText("● Подключено");
                    });
                    break;
                case VoipService.BROADCAST_CONNECTED:
                    runOnUiThread(() -> tvServerStatus.setText("● Подключено"));
                    break;
                case VoipService.BROADCAST_DISCONNECTED:
                    runOnUiThread(() -> tvServerStatus.setText("○ Переподключение..."));
                    break;
                case VoipService.BROADCAST_RINGING:
                    runOnUiThread(() -> {
                        currentCallee = intent.getStringExtra("to");
                        currentCallType = CallRecord.TYPE_OUTGOING;
                        tvStatus.setText("Вызов: " + currentCallee + "...");
                        btnCall.setVisibility(View.GONE);
                        btnHangup.setVisibility(View.VISIBLE);
                    });
                    break;
                case VoipService.BROADCAST_ACCEPTED:
                    runOnUiThread(() -> {
                        callStartTime = System.currentTimeMillis();
                        tvStatus.setText("В разговоре");
                        startActivity(new Intent(MainActivity.this, ActiveCallActivity.class)
                            .putExtra(VoipService.EXTRA_CALL_ID,
                                intent.getStringExtra(VoipService.EXTRA_CALL_ID)));
                    });
                    break;
                case VoipService.BROADCAST_REJECTED:
                    runOnUiThread(() -> {
                        saveCallRecord(CallRecord.TYPE_OUTGOING, currentCallee, 0);
                        tvStatus.setText("Отклонено");
                        resetCallUI();
                    });
                    break;
                case VoipService.BROADCAST_ENDED:
                    runOnUiThread(() -> {
                        int dur = callStartTime > 0
                            ? (int)((System.currentTimeMillis() - callStartTime) / 1000) : 0;
                        saveCallRecord(currentCallType != null ? currentCallType : CallRecord.TYPE_OUTGOING,
                            currentCallee, dur);
                        callStartTime = 0;
                        tvStatus.setText("Звонок завершён");
                        resetCallUI();
                    });
                    break;
                case VoipService.BROADCAST_INCOMING:
                    runOnUiThread(() -> {
                        currentCallee = intent.getStringExtra(VoipService.EXTRA_FROM);
                        currentCallType = CallRecord.TYPE_INCOMING;
                    });
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("voip_prefs", MODE_PRIVATE);

        tvStatus = findViewById(R.id.tvStatus);
        tvMyNumber = findViewById(R.id.tvMyNumber);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        etMyName = findViewById(R.id.etMyName);
        etCallTo = findViewById(R.id.etCallTo);
        btnCall = findViewById(R.id.btnCall);
        btnHangup = findViewById(R.id.btnHangup);
        btnHistory = findViewById(R.id.btnHistory);

        // Загрузить сохранённое имя
        String savedName = prefs.getString("my_name", "");
        etMyName.setText(savedName);
        etMyName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveName();
        });

        btnCall.setOnClickListener(v -> { saveName(); makeCall(); });
        btnHangup.setOnClickListener(v -> hangup());
        btnHistory.setOnClickListener(v ->
            startActivity(new Intent(this, CallHistoryActivity.class)));

        requestPermissions();
        registerReceivers();

        // Автоматически подключаемся к фиксированному серверу
        autoConnect();
    }

    private void autoConnect() {
        tvServerStatus.setText("○ Подключение...");
        Intent service = new Intent(this, VoipService.class);
        service.putExtra("server_url", FIXED_SERVER);
        startForegroundService(service);
        bindService(new Intent(this, VoipService.class), serviceConn, Context.BIND_AUTO_CREATE);
    }

    private void saveName() {
        String name = etMyName.getText().toString().trim();
        if (!name.isEmpty()) {
            prefs.edit().putString("my_name", name).apply();
        }
    }

    private void makeCall() {
        String to = etCallTo.getText().toString().trim();
        if (TextUtils.isEmpty(to)) {
            Toast.makeText(this, "Введите номер", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound || voipService == null) {
            Toast.makeText(this, "Нет подключения к серверу", Toast.LENGTH_SHORT).show();
            return;
        }
        currentCallee = to;
        currentCallType = CallRecord.TYPE_OUTGOING;
        voipService.makeCall(to);
        tvStatus.setText("Вызов " + to + "...");
    }

    private void hangup() {
        if (serviceBound && voipService != null) {
            voipService.hangup();
        }
        saveCallRecord(currentCallType != null ? currentCallType : CallRecord.TYPE_OUTGOING,
            currentCallee, 0);
        resetCallUI();
    }

    private void saveCallRecord(String type, String number, int durationSec) {
        if (number == null || number.isEmpty()) return;
        String myName = prefs.getString("my_name", "");
        // displayName — имя собеседника (номер, если имя не известно)
        CallRecord record = new CallRecord(type, number, number, System.currentTimeMillis(), durationSec);
        CallHistoryActivity.addRecord(this, record);
    }

    private void resetCallUI() {
        btnCall.setVisibility(View.VISIBLE);
        btnHangup.setVisibility(View.GONE);
        tvStatus.setText("Готов");
        currentCallee = null;
        currentCallType = null;
        callStartTime = 0;
    }

    private void updateUI() {
        if (voipService != null && voipService.getMyNumber() != null) {
            tvMyNumber.setText("Ваш номер: " + voipService.getMyNumber());
        }
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(VoipService.BROADCAST_REGISTERED);
        filter.addAction(VoipService.BROADCAST_CONNECTED);
        filter.addAction(VoipService.BROADCAST_DISCONNECTED);
        filter.addAction(VoipService.BROADCAST_RINGING);
        filter.addAction(VoipService.BROADCAST_ACCEPTED);
        filter.addAction(VoipService.BROADCAST_REJECTED);
        filter.addAction(VoipService.BROADCAST_ENDED);
        filter.addAction(VoipService.BROADCAST_INCOMING);
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void requestPermissions() {
        String[] perms = { Manifest.permission.RECORD_AUDIO };
        if (Build.VERSION.SDK_INT >= 33) {
            perms = new String[]{ Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS };
        }
        boolean needRequest = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true; break;
            }
        }
        if (needRequest) ActivityCompat.requestPermissions(this, perms, PERM_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        if (serviceBound) { unbindService(serviceConn); serviceBound = false; }
        super.onDestroy();
    }
}
