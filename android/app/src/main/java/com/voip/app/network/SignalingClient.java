package com.voip.app.network;

import android.util.Log;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SignalingClient extends WebSocketClient {

    private static final String TAG = "SignalingClient";
    private static final int RECONNECT_DELAY_SEC = 5;
    private static final int CONNECT_TIMEOUT_SEC = 15;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    public interface Listener {
        void onRegistered(String number);
        void onIncomingCall(String callId, String from, String callerIp, int callerUdpPort);
        void onCallRinging(String callId, String to);
        void onCallAccepted(String callId, String calleeIp, int calleeUdpPort);
        void onCallRejected(String callId);
        void onCallEnded(String callId, String reason);
        void onIceCandidate(String callId, String candidate);
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    private final Listener listener;
    private final String serverUrl;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private String myNumber;
    private final int myUdpPort;
    private final AtomicBoolean intentionalClose = new AtomicBoolean(false);
    private int reconnectAttempts = 0;
    private ScheduledFuture<?> keepaliveFuture;

    public SignalingClient(String serverUrl, int udpPort, Listener listener) throws Exception {
        super(new URI(serverUrl));
        this.serverUrl = serverUrl;
        this.myUdpPort = udpPort;
        this.listener = listener;
        setConnectionLostTimeout(CONNECT_TIMEOUT_SEC);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        Log.i(TAG, "Connected to " + serverUrl);
        reconnectAttempts = 0;
        listener.onConnected();
        register(myNumber, myUdpPort);
        // Keepalive каждые 25 секунд
        keepaliveFuture = scheduler.scheduleAtFixedRate(this::sendKeepalive, 25, 25, TimeUnit.SECONDS);
    }

    @Override
    public void onMessage(String raw) {
        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.getString("type");
            Log.d(TAG, "MSG: " + type);

            switch (type) {
                case "registered":
                    myNumber = msg.getString("number");
                    listener.onRegistered(myNumber);
                    break;
                case "incoming_call":
                    listener.onIncomingCall(
                        msg.getString("call_id"), msg.getString("from"),
                        msg.getString("caller_ip"), msg.getInt("caller_udp_port"));
                    break;
                case "call_ringing":
                    listener.onCallRinging(msg.getString("call_id"), msg.getString("to"));
                    break;
                case "call_accepted":
                    listener.onCallAccepted(
                        msg.getString("call_id"),
                        msg.getString("callee_ip"), msg.getInt("callee_udp_port"));
                    break;
                case "call_rejected":
                    listener.onCallRejected(msg.getString("call_id"));
                    break;
                case "call_ended":
                    listener.onCallEnded(msg.getString("call_id"), msg.optString("reason", "unknown"));
                    break;
                case "ice_candidate":
                    listener.onIceCandidate(msg.getString("call_id"), msg.optString("candidate", ""));
                    break;
                case "error":
                    listener.onError(msg.optString("message", "Unknown error"));
                    break;
                case "pong":
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        Log.w(TAG, "Disconnected: code=" + code + " reason=" + reason);
        cancelKeepalive();
        listener.onDisconnected();

        if (!intentionalClose.get()) {
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                scheduleReconnect();
            } else {
                Log.e(TAG, "Max reconnect attempts reached");
            }
        }
    }

    @Override
    public void onError(Exception e) {
        Log.e(TAG, "WS Error: " + (e != null ? e.getMessage() : "null"));
        if (e != null) listener.onError(e.getMessage());
    }

    // ─── Отправка ─────────────────────────────────────────────────────────────

    private void register(String existingNumber, int udpPort) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "register");
            msg.put("udp_port", udpPort);
            if (existingNumber != null) msg.put("number", existingNumber);
            send(msg.toString());
        } catch (Exception e) { Log.e(TAG, e.getMessage()); }
    }

    public void call(String toNumber) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "call");
            msg.put("to", toNumber);
            send(msg.toString());
        } catch (Exception e) { Log.e(TAG, e.getMessage()); }
    }

    public void answerCall(String callId) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "call_answer");
            msg.put("call_id", callId);
            send(msg.toString());
        } catch (Exception e) { Log.e(TAG, e.getMessage()); }
    }

    public void rejectCall(String callId) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "call_reject");
            msg.put("call_id", callId);
            send(msg.toString());
        } catch (Exception e) { Log.e(TAG, e.getMessage()); }
    }

    public void endCall(String callId) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "call_end");
            msg.put("call_id", callId);
            send(msg.toString());
        } catch (Exception e) { Log.e(TAG, e.getMessage()); }
    }

    public void sendIceCandidate(String callId, String candidate) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "ice_candidate");
            msg.put("call_id", callId);
            msg.put("candidate", candidate);
            send(msg.toString());
        } catch (Exception e) { Log.e(TAG, e.getMessage()); }
    }

    private void sendKeepalive() {
        try {
            if (isOpen()) {
                JSONObject msg = new JSONObject();
                msg.put("type", "keepalive");
                send(msg.toString());
            }
        } catch (Exception ignored) {}
    }

    private void cancelKeepalive() {
        if (keepaliveFuture != null && !keepaliveFuture.isDone()) {
            keepaliveFuture.cancel(false);
        }
    }

    private void scheduleReconnect() {
        reconnectAttempts++;
        long delay = Math.min(RECONNECT_DELAY_SEC * reconnectAttempts, 60);
        Log.i(TAG, "Reconnecting in " + delay + "s (attempt " + reconnectAttempts + ")");
        scheduler.schedule(() -> {
            try {
                if (!intentionalClose.get()) reconnect();
            } catch (Exception e) {
                Log.e(TAG, "Reconnect failed: " + e.getMessage());
                scheduleReconnect();
            }
        }, delay, TimeUnit.SECONDS);
    }

    public void disconnectGracefully() {
        intentionalClose.set(true);
        cancelKeepalive();
        scheduler.shutdown();
        try { close(); } catch (Exception ignored) {}
    }

    public String getMyNumber() { return myNumber; }
    public void setMyNumber(String n) { this.myNumber = n; }
}
