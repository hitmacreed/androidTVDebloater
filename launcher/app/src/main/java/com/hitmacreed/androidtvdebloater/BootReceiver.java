package com.hitmacreed.androidtvdebloater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Ensures NLRC launches automatically on boot and wake from deep sleep.
 * Marked android:directBootAware="true" so it can trigger as early as possible.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received boot/power broadcast: " + action);

        ensureAccessibilityEnabled(context);

        try {
            Intent home = new Intent(context, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(home);
        } catch (Exception e) {
            Log.e(TAG, "Failed to autostart NLRC on boot", e);
        }
    }

    public static void ensureAccessibilityEnabled(Context context) {
        try {
            String serviceName = context.getPackageName() + "/" + HomeKeyService.class.getName();
            String enabledServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || !enabledServices.contains(serviceName)) {
                String newServices = (enabledServices == null || enabledServices.isEmpty()) ? serviceName : enabledServices + ":" + serviceName;
                Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices);
            }
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) {
            Log.w(TAG, "Could not self-heal accessibility service: " + e.getMessage());
        }
    }
}
