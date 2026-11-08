package com.jass.encuentramimovil;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private Button btn;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean askedBattery = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btn = findViewById(R.id.btn);
        status = findViewById(R.id.status);
        btn.setOnClickListener(v -> toggle());
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    private void toggle() {
        if (ClapDetectorService.running) {
            Intent i = new Intent(this, ClapDetectorService.class);
            i.setAction(ClapDetectorService.ACTION_STOP);
            startService(i);
            handler.postDelayed(this::updateUi, 300);
        } else {
            if (!hasMicPermission() || !hasNotificationPermission()) {
                requestPermissions(new String[]{
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
                return;
            }
            startDetector();
        }
    }

    private void startDetector() {
        if (!askedBattery) {
            askedBattery = true;
            try {
                Intent o = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(o);
            } catch (Exception ignored) { }
        }
        Intent i = new Intent(this, ClapDetectorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        Toast.makeText(this, R.string.starting, Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::updateUi, 600);
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req == REQ_PERMS && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startDetector();
        } else {
            Toast.makeText(this, R.string.mic_needed, Toast.LENGTH_LONG).show();
        }
    }

    private void updateUi() {
        if (ClapDetectorService.running) {
            btn.setText(R.string.stop);
            status.setText(R.string.listening);
            status.setTextColor(0xFF22C55E);
        } else {
            btn.setText(R.string.start);
            status.setText(R.string.idle);
            status.setTextColor(0xFF94A3B8);
        }
    }
}
