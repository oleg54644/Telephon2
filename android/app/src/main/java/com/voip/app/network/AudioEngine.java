package com.voip.app.network;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Аудио через сервер-relay (UDP).
 * Телефон → сервер:5004 → другой телефон
 * Первые 4 байта каждого пакета — call_id (int) для маршрутизации на сервере.
 */
public class AudioEngine {

    private static final String TAG = "AudioEngine";
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_IN  = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING    = AudioFormat.ENCODING_PCM_16BIT;
    private static final int PACKET_SIZE = 320; // 20мс при 8кHz
    private static final int HEADER_SIZE = 4;   // 4 байта call_id

    private DatagramSocket udpSocket;
    private AudioRecord recorder;
    private AudioTrack  player;
    private Thread sendThread, recvThread;
    private volatile boolean running = false;

    // Адрес сервера-relay
    private String relayHost;
    private int    relayPort;
    private int    localPort;
    private int    callId; // числовой ID звонка для маршрутизации

    public AudioEngine(int localPort) {
        this.localPort = localPort;
    }

    /**
     * @param relayHost  IP сервера (например 45.128.204.171)
     * @param relayPort  UDP порт сервера (5004)
     * @param callId     числовой ID звонка
     */
    public void startCall(String relayHost, int relayPort, int callId) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.callId    = callId;
        running = true;

        try {
            udpSocket = new DatagramSocket(localPort);
            udpSocket.setSoTimeout(100);
        } catch (Exception e) {
            Log.e(TAG, "Socket error: " + e.getMessage());
            try {
                // Если порт занят — берём случайный
                udpSocket = new DatagramSocket();
                udpSocket.setSoTimeout(100);
            } catch (Exception ex) {
                Log.e(TAG, "Socket fallback error: " + ex.getMessage());
                return;
            }
        }

        initRecorder();
        initPlayer();

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            return;
        }

        recorder.startRecording();
        player.play();

        sendThread = new Thread(this::sendAudio, "VoIP-Send");
        recvThread = new Thread(this::receiveAudio, "VoIP-Recv");
        sendThread.start();
        recvThread.start();

        Log.i(TAG, "Audio relay started → " + relayHost + ":" + relayPort + " callId=" + callId);
    }

    // Обратная совместимость со старым кодом (VoipService вызывает startCall(ip, port))
    public void startCall(String remoteIp, int remotePort) {
        // В relay-режиме remoteIp = сервер, callId = 0
        startCall(remoteIp, remotePort, 0);
    }

    private void initRecorder() {
        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
        bufSize = Math.max(bufSize, PACKET_SIZE * 4);
        recorder = new AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize
        );
    }

    private void initPlayer() {
        int bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
        bufSize = Math.max(bufSize, PACKET_SIZE * 8);
        player = new AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE, CHANNEL_OUT, ENCODING, bufSize,
            AudioTrack.MODE_STREAM
        );
    }

    private void sendAudio() {
        byte[] audio  = new byte[PACKET_SIZE];
        byte[] packet = new byte[HEADER_SIZE + PACKET_SIZE];

        // Записываем call_id в заголовок (big-endian int)
        packet[0] = (byte)(callId >> 24);
        packet[1] = (byte)(callId >> 16);
        packet[2] = (byte)(callId >> 8);
        packet[3] = (byte)(callId);

        try {
            InetAddress addr = InetAddress.getByName(relayHost);
            while (running) {
                int read = recorder.read(audio, 0, PACKET_SIZE);
                if (read > 0) {
                    System.arraycopy(audio, 0, packet, HEADER_SIZE, read);
                    DatagramPacket pkt = new DatagramPacket(
                        packet, HEADER_SIZE + read, addr, relayPort);
                    udpSocket.send(pkt);
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Send error: " + e.getMessage());
        }
    }

    private void receiveAudio() {
        byte[] buf = new byte[HEADER_SIZE + PACKET_SIZE * 2];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                udpSocket.receive(pkt);
                int len = pkt.getLength();
                if (len > HEADER_SIZE) {
                    // Пропускаем 4-байтовый заголовок
                    player.write(buf, HEADER_SIZE, len - HEADER_SIZE);
                }
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (running) Log.e(TAG, "Recv error: " + e.getMessage());
            }
        }
    }

    public void stopCall() {
        running = false;
        try {
            if (recorder != null && recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                recorder.stop(); recorder.release();
            }
            if (player != null) { player.stop(); player.release(); }
            if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
            if (sendThread != null) sendThread.interrupt();
            if (recvThread != null) recvThread.interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Stop error: " + e.getMessage());
        }
        Log.i(TAG, "Audio stopped");
    }

    public boolean isRunning() { return running; }
}

