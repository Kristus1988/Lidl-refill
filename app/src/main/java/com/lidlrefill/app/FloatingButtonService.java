package com.lidlrefill.app;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class FloatingButtonService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, null);
        }
        return START_STICKY;
    }
}
