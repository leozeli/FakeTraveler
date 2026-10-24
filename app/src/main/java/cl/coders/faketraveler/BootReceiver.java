package cl.coders.faketraveler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Restarts mock FGS after reboot / update if mock was left running.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.sharedPrefKey, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("mockRunning", false)) {
            Log.d(TAG, "mock not marked running; skip");
            return;
        }
        Log.i(TAG, "Resuming mock after " + intent.getAction());
        Intent svc = new Intent(context, MockedLocationService.class);
        svc.setAction(MockedLocationService.ACTION_START);
        svc.putExtra(MockedLocationService.EXTRA_LATITUDE, Double.longBitsToDouble(prefs.getLong("lat", 0)));
        svc.putExtra(MockedLocationService.EXTRA_LONGITUDE, Double.longBitsToDouble(prefs.getLong("lng", 0)));
        svc.putExtra(MockedLocationService.EXTRA_LATITUDE_DISTANCE, Double.longBitsToDouble(prefs.getLong("dLat", 0)) / 1000000.0);
        svc.putExtra(MockedLocationService.EXTRA_LONGITUDE_DISTANCE, Double.longBitsToDouble(prefs.getLong("dLng", 0)) / 1000000.0);
        int freq = prefs.getInt("mockFrequency", 10);
        svc.putExtra(MockedLocationService.EXTRA_MOCK_MILLI, MockInterval.toDelayMillis(freq));
        svc.putExtra(MockedLocationService.EXTRA_MAX_TIMES, 0); // infinite after reboot
        svc.putExtra(MockedLocationService.EXTRA_MOCK_SPEED, 0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, svc);
        } else {
            context.startService(svc);
        }
    }
}
