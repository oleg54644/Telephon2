package com.voip.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.voip.app.R;
import com.voip.app.network.AudioEngine;
import com.voip.app.network.SignalingClient;
import com.voip.app.ui.IncomingCallActivity;
import com.voip.app.ui.MainActivity;

public class VoipService extends Service implements SignalingClient.Listener {

    private static final String TAG = "VoipService";
    public static final String CHANNEL_ID_PERSISTENT = "voip_persistent";
    public static final String CHANNEL_ID_CALLS = "voip_calls";
    public static final int NOTIF_ID_PERSISTENT = 1;
    public static final int NOTIF_ID_CALL = 2;

    // ★ Фиксированный сервер — жёстко прописан здесь
    private static final String SERVER_URL = "ws://45.128.204.171:8765";

    public static final String ACTION_ANSWER  = "com.voip.ACTION_ANSWER";
    public static final String ACTION_REJECT  = "com.voip.ACTION_REJECT";
    public static final String ACTION_HANGUP  = "com.voip.ACTION_HANGUP";
    public static final String EXTRA_CALL_ID  = "call_id";
    public static final String EXTRA_FROM     = "from";

    public static final String BROADCAST_REGISTERED   = "com.voip.REGISTERED";
    public static final String BROADCAST_INCOMING     = "com.voip.INCOMING";
    public static final String BROADCAST_RINGING      = "com.voip.RINGING";
    public static final String BROADCAST_ACCEPTED     = "com.voip.ACCEPTED";
    public static final String BROADCAST_REJECTED     = "com.voip.REJECTED";
    public static final String BROADCAST_ENDED        = "com.voip.ENDED";
    public static final String BROADCAST_CONNECTED    = "com.voip.CONNECTED";
    public static final String BROADCAST_DISCONNECTED = "com.voip.DISCONNECTED";

    private SignalingClient signalingClient;
    private AudioEngine audioEngine;
    private PowerManager.WakeLock wakeLock;

    private String myNumber;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private static final long WATCHDOG_INTERVAL = 15_000L; // 15 секунд
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (signalingClient == null || !signalingClient.isOpen()) {
                Log.i(TAG, "Watchdog: reconnecting...");
                connectToServer();
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL);
        }
    };
    private String currentCallId;
    private boolean connected = false;
    private static final int UDP_PORT = 5004;

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public VoipService getService() { return VoipService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        acquireWakeLock();
        audioEngine = new AudioEngine(UDP_PORT);
        watchdogHandler.postDelayed(watchdog, WATCHDOG_INTERVAL);
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Обработка кнопок уведомления
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_ANSWER: answerCall(intent.getStringExtra(EXTRA_CALL_ID)); return START_STICKY;
                case ACTION_REJECT: rejectCall(intent.getStringExtra(EXTRA_CALL_ID)); return START_STICKY;
                case ACTION_HANGUP: hangup(); return START_STICKY;
            }
        }

        startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification("Подключение..."));

        // Подключаемся если ещё не подключены
        if (signalingClient == null || !signalingClient.isOpen()) {
            connectToServer();
        }

        return START_STICKY;
    }

    private void connectToServer() {
        try {
            if (signalingClient != null) {
                signalingClient.disconnectGracefully();
                signalingClient = null;
            }
            signalingClient = new SignalingClient(SERVER_URL, UDP_PORT, this);
            // Передаём сохранённый номер чтобы он не менялся
            if (myNumber != null) signalingClient.setMyNumber(myNumber);
            signalingClient.connect();
            Log.i(TAG, "Connecting to " + SERVER_URL + " (number=" + myNumber + ")");
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
        }
    }

    @Override
    public void onConnected() {
        connected = true;
        broadcast(BROADCAST_CONNECTED, null, null);
        updatePersistentNotification("Подключено");
    }

    @Override
    public void onDisconnected() {
        connected = false;
        broadcast(BROADCAST_DISCONNECTED, null, null);
        updatePersistentNotification("Переподключение...");
    }

    @Override
    public void onRegistered(String number) {
        myNumber = number;
        broadcast(BROADCAST_REGISTERED, "number", number);
        updatePersistentNotification("Номер: " + number);
    }

    @Override
    public void onIncomingCall(String callId, String from, String callerIp, int callerUdpPort) {
        currentCallId = callId;
        showIncomingCallNotification(callId, from);

        Intent ui = new Intent(this, IncomingCallActivity.class);
        ui.putExtra(EXTRA_CALL_ID, callId);
        ui.putExtra(EXTRA_FROM, from);
        ui.putExtra("caller_ip", callerIp);
        ui.putExtra("caller_udp_port", callerUdpPort);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        startActivity(ui);

        Intent b = new Intent(BROADCAST_INCOMING);
        b.putExtra(EXTRA_CALL_ID, callId);
        b.putExtra(EXTRA_FROM, from);
        b.putExtra("caller_ip", callerIp);
        b.putExtra("caller_udp_port", callerUdpPort);
        sendBroadcast(b);
    }

    @Override
    public void onCallRinging(String callId, String to) {
        currentCallId = callId;
        Intent b = new Intent(BROADCAST_RINGING);
        b.putExtra(EXTRA_CALL_ID, callId);
        b.putExtra("to", to);
        sendBroadcast(b);
    }

    @Override
    public void onCallAccepted(String callId, String calleeIp, int calleeUdpPort) {
        audioEngine.startCall(calleeIp, calleeUdpPort);
        cancelCallNotification();
        Intent b = new Intent(BROADCAST_ACCEPTED);
        b.putExtra(EXTRA_CALL_ID, callId);
        sendBroadcast(b);
    }

    @Override
    public void onCallRejected(String callId) {
        cancelCallNotification();
        Intent b = new Intent(BROADCAST_REJECTED);
        b.putExtra(EXTRA_CALL_ID, callId);
        sendBroadcast(b);
        currentCallId = null;
    }

    @Override
    public void onCallEnded(String callId, String reason) {
        audioEngine.stopCall();
        cancelCallNotification();
        Intent b = new Intent(BROADCAST_ENDED);
        b.putExtra(EXTRA_CALL_ID, callId);
        b.putExtra("reason", reason);
        sendBroadcast(b);
        currentCallId = null;
    }

    @Override public void onIceCandidate(String callId, String candidate) {}

    @Override public void onError(String message) {
        Log.e(TAG, "Signaling error: " + message);
    }

    public void makeCall(String toNumber) {
        if (signalingClient != null && signalingClient.isOpen()) {
            signalingClient.call(toNumber);
        }
    }

    public void answerCall(String callId) {
        if (signalingClient != null) signalingClient.answerCall(callId);
    }

    public void rejectCall(String callId) {
        if (signalingClient != null) signalingClient.rejectCall(callId);
        cancelCallNotification();
    }

    public void hangup() {
        if (currentCallId != null && signalingClient != null) {
            signalingClient.endCall(currentCallId);
        }
        audioEngine.stopCall();
        cancelCallNotification();
        currentCallId = null;
    }

    public String getMyNumber() { return myNumber; }
    public boolean isInCall() { return audioEngine.isRunning(); }

    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel persistent = new NotificationChannel(
            CHANNEL_ID_PERSISTENT, "VoIP Статус", NotificationManager.IMPORTANCE_LOW);
        persistent.setDescription("Фоновое соединение");

        NotificationChannel calls = new NotificationChannel(
            CHANNEL_ID_CALLS, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Уведомления о звонках");
        calls.enableVibration(true);
        calls.setBypassDnd(true);

        nm.createNotificationChannel(persistent);
        nm.createNotificationChannel(calls);
    }

    private Notification buildPersistentNotification(String text) {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID_PERSISTENT)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("GrinMain Телефон")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    private void updatePersistentNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID_PERSISTENT, buildPersistentNotification(text));
    }

    private void showIncomingCallNotification(String callId, String from) {
        Intent answerIntent = new Intent(this, VoipService.class);
        answerIntent.setAction(ACTION_ANSWER);
        answerIntent.putExtra(EXTRA_CALL_ID, callId);
        PendingIntent answerPI = PendingIntent.getService(this, 1, answerIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent rejectIntent = new Intent(this, VoipService.class);
        rejectIntent.setAction(ACTION_REJECT);
        rejectIntent.putExtra(EXTRA_CALL_ID, callId);
        PendingIntent rejectPI = PendingIntent.getService(this, 2, rejectIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent fullScreen = new Intent(this, IncomingCallActivity.class);
        fullScreen.putExtra(EXTRA_CALL_ID, callId);
        fullScreen.putExtra(EXTRA_FROM, from);
        PendingIntent fullScreenPI = PendingIntent.getActivity(this, 3, fullScreen,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_CALLS)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Входящий звонок")
            .setContentText("Звонит: " + from)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPI, true)
            .addAction(R.drawable.ic_phone, "Ответить", answerPI)
            .addAction(R.drawable.ic_phone_off, "Отклонить", rejectPI)
            .setAutoCancel(false)
            .setOngoing(true)
            .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID_CALL, n);
    }

    private void cancelCallNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(NOTIF_ID_CALL);
    }

    private void broadcast(String action, String key, String value) {
        Intent b = new Intent(action);
        if (key != null) b.putExtra(key, value);
        sendBroadcast(b);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoIP:WakeLock");
        wakeLock.acquire();
    }

    @Override
    public void onDestroy() {
        watchdogHandler.removeCallbacks(watchdog);
        unregisterNetworkCallback();
        if (signalingClient != null) signalingClient.disconnectGracefully();
        if (audioEngine != null) audioEngine.stopCall();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
