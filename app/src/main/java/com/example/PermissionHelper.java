package com.example.lidlrefill;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

public class PermissionHelper {
    
    public static boolean isBatteryOptimizationDisabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return true;
    }
    
    public static Intent getBatterySettingsIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        }
        return null;
    }
    
    public static Intent getAppSettingsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
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
