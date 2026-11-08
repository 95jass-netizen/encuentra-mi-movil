package com.jass.encuentramimovil;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Toast;

public class ClapDetectorService extends Service {

    public static volatile boolean running = false;
    public static final String ACTION_STOP = "com.jass.encuentramimovil.STOP";

    private static final int SAMPLE_RATE = 44100;
    private static final int FRAME = 2048;
    private static final String CHANNEL_ID = "detector";
    private static final long ALARM_MS = 15000;
    private static final long COOLDOWN_MS = 17000;

    private static final double CLAP_RMS = 0.06;
    private static final double WHISTLE_RMS = 0.02;
    private static final int WHISTLE_FRAMES_NEEDED = 8;

    private PowerManager.WakeLock wakeLock;
    private Thread detectThread;
    private Vibrator vibrator;
    private volatile boolean alarming = false;
    private volatile long lastTrigger = 0;
    private MediaPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);

        Intent stopIntent = new Intent(this, ClapDetectorService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), pi).build())
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }

        if (Build.VERSION.SDK_INT >= 31) {
            vibrator = ((VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EncuentraMiMovil:mic");
        wakeLock.acquire();

        running = true;
        detectThread = new Thread(this::detectLoop, "ClapDetector");
        detectThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        }
        return START_STICKY;
    }

    private void detectLoop() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf * 2, FRAME * 4));
        } catch (Exception e) {
            rec = null;
        }
        if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
            handler.post(() -> Toast.makeText(this, R.string.mic_error, Toast.LENGTH_LONG).show());
            stopSelf();
            return;
        }

        short[] buf = new short[FRAME];
        rec.startRecording();
        int whistleFrames = 0;

        while (running) {
            int n = rec.read(buf, 0, FRAME);
            if (n <= 0) continue;

            double sumSq = 0;
            int crossings = 0;
            int prev = buf[0];
            for (int i = 0; i < n; i++) {
                double v = buf[i] / 32768.0;
                sumSq += v * v;
                if ((buf[i] >= 0 && prev < 0) || (buf[i] < 0 && prev >= 0)) crossings++;
                prev = buf[i];
            }
            double rms = Math.sqrt(sumSq / n);
            double dominantHz = (crossings * (double) SAMPLE_RATE / n) / 2.0;
            long now = SystemClock.elapsedRealtime();

            if (alarming || now - lastTrigger < COOLDOWN_MS) {
                whistleFrames = 0;
                continue;
            }

            if (rms > CLAP_RMS) {
                lastTrigger = now;
                whistleFrames = 0;
                trigger(getString(R.string.clap_detected));
            } else if (dominantHz > 900 && dominantHz < 4000 && rms > WHISTLE_RMS) {
                whistleFrames++;
                if (whistleFrames >= WHISTLE_FRAMES_NEEDED) {
                    whistleFrames = 0;
                    lastTrigger = now;
                    trigger(getString(R.string.whistle_detected));
                }
            } else {
                whistleFrames = 0;
            }
        }

        try { rec.stop(); } catch (Exception ignored) { }
        rec.release();
    }

    private void trigger(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        alarming = true;

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);

        try {
            player = new MediaPlayer();
            player.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) { }

        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 500, 300, 500, 300, 500, 300, 500, 300};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        }

        handler.postDelayed(this::stopAlarm, ALARM_MS);
    }

    private void stopAlarm() {
        alarming = false;
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (vibrator != null) vibrator.cancel();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (detectThread != null) detectThread.interrupt();
        stopAlarm();
        if (wakeLock != null) wakeLock.release();
        super.onDestroy();
    }
}
