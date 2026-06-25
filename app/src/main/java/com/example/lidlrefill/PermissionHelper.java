package com.example.lidlrefill;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

public class PermissionHelper {
    
    // Vereinfachte Version – funktioniert auf allen Android-Versionen
    public static boolean isBatteryOptimizationDisabled(Context context) {
        // Bei Android 6.0+ prüfen, sonst immer true
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                // Reflektion verwenden, um den Compiler-Fehler zu umgehen
                Class<?> settingsClass = Class.forName("android.provider.Settings");
                java.lang.reflect.Method method = settingsClass.getMethod(
                    "isIgnoringBatteryOptimizations", 
                    String.class
                );
                return (boolean) method.invoke(null, context.getPackageName());
            } catch (Exception e) {
                // Bei Fehler nehmen wir an, dass die Optimierung deaktiviert ist
                return true;
            }
        }
        return true;
    }
    
    public static Intent getBatterySettingsIntent() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Class<?> settingsClass = Class.forName("android.provider.Settings");
                java.lang.reflect.Field field = settingsClass.getField("ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS");
                String action = (String) field.get(null);
                return new Intent(action);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
    
    public static Intent getAppSettingsIntent(Context context) {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }
    
    public static boolean isAutostartAvailable() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("xiaomi") || 
               manufacturer.contains("huawei") || 
               manufacturer.contains("oneplus") || 
               manufacturer.contains("oppo") || 
               manufacturer.contains("vivo");
    }
    
    public static Intent getAutostartIntent(Context context) {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        Intent intent = null;
        
        if (manufacturer.contains("xiaomi")) {
            intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
            intent.putExtra("extra_pkgname", context.getPackageName());
        } else if (manufacturer.contains("huawei")) {
            intent = new Intent("huawei.intent.action.HUAWEI_PERMISSION_MANAGER");
        } else if (manufacturer.contains("oneplus")) {
            intent = new Intent("oneplus.intent.action.APP_PERMISSION_SETTINGS");
        } else {
            intent = getAppSettingsIntent(context);
        }
        
        return intent;
    }
}
