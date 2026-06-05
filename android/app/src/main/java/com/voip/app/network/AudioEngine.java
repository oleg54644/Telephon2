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
 * Захват микрофона → UDP → воспроизведение
 * Codec: RAW PCM 8kHz mono (можно заменить на Opus)
 */
public class AudioEngine {

    private static final String TAG = "AudioEngine";
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int PACKET_SIZE = 320; // 20мс при 8кHz

    private DatagramSocket udpSocket;
    private AudioRecord recorder;
    private AudioTrack player;
    private Thread sendThread, recvThread;
    private volatile boolean running = false;

    private String remoteIp;
    private int remotePort;
    private int localPort;

    public AudioEngine(int localPort) {
        this.localPort = localPort;
    }

    public void startCall(String remoteIp, int remotePort) {
        this.remoteIp = remoteIp;
        this.remotePort = remotePort;
        running = true;

        try {
            udpSocket = new DatagramSocket(localPort);
            udpSocket.setSoTimeout(100);
        } catch (Exception e) {
            Log.e(TAG, "Socket error: " + e.getMessage());
            return;
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

        Log.i(TAG, "Audio started: " + remoteIp + ":" + remotePort);
    }

    private void initRecorder() {
        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
        bufSize = Math.max(bufSize, PACKET_SIZE * 2);
        recorder = new AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize
        );
    }

    private void initPlayer() {
        int bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
        bufSize = Math.max(bufSize, PACKET_SIZE * 4);
        player = new AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE, CHANNEL_OUT, ENCODING, bufSize,
            AudioTrack.MODE_STREAM
        );
    }

    private void sendAudio() {
        byte[] buf = new byte[PACKET_SIZE];
        try {
            InetAddress addr = InetAddress.getByName(remoteIp);
            while (running) {
                int read = recorder.read(buf, 0, PACKET_SIZE);
                if (read > 0) {
                    DatagramPacket pkt = new DatagramPacket(buf, read, addr, remotePort);
                    udpSocket.send(pkt);
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Send error: " + e.getMessage());
        }
    }

    private void receiveAudio() {
        byte[] buf = new byte[PACKET_SIZE * 2];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                udpSocket.receive(pkt);
                player.write(buf, 0, pkt.getLength());
            } catch (java.net.SocketTimeoutException ignored) {
                // timeout - продолжаем
            } catch (Exception e) {
                if (running) Log.e(TAG, "Recv error: " + e.getMessage());
            }
        }
    }

    public void stopCall() {
        running = false;
        try {
            if (recorder != null && recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                recorder.stop();
                recorder.release();
            }
            if (player != null) {
                player.stop();
                player.release();
            }
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
            if (sendThread != null) sendThread.interrupt();
            if (recvThread != null) recvThread.interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Stop error: " + e.getMessage());
        }
        Log.i(TAG, "Audio stopped");
    }

    public boolean isRunning() { return running; }
}
