package cl.coders.faketraveler;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One test provider. Install failures and a provider removed while the app is
 * backgrounded are reported to the caller instead of escaping the inject loop.
 */
public class MockedLocationProvider {

    @NonNull
    private static final String TAG = MockedLocationProvider.class.getSimpleName();

    @NonNull
    private final String providerName;
    @NonNull
    private final Context ctx;
    private final int powerUsage;
    private final int accuracy;
    private boolean installed;
    /** Avoid reinstalling a provider the device rejects on every continuous tick. */
    private long nextInstallAttemptElapsed;

    /**
     * @param name provider
     * @param ctx  context
     */
    public MockedLocationProvider(@NonNull String name, @NonNull Context ctx) {
        this.providerName = name;
        this.ctx = ctx;

        int power = 0;
        int acc = 5;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            power = 1;
            acc = 2;
        }
        this.powerUsage = power;
        this.accuracy = acc;
        this.installed = install();
    }

    public boolean isInstalled() {
        return installed;
    }

    /**
     * Pushes one fix. Reinstalls the test provider if the system dropped it
     * (typical once another app is in front) and tries the push again.
     *
     * @return true when {@link LocationManager#setTestProviderLocation} accepted the fix
     */
    public boolean pushLocation(double lat, double lon, float speedMetersPerSecond) {
        LocationManager lm = locationManager();
        if (lm == null) return false;
        if (!installed && !installWhenDue()) return false;
        try {
            write(lm, lat, lon, speedMetersPerSecond);
            return true;
        } catch (SecurityException se) {
            Log.e(TAG, "Mock permission lost for " + providerName, se);
            installed = false;
            nextInstallAttemptElapsed = SystemClock.elapsedRealtime() + 5_000L;
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Provider " + providerName + " dropped; reinstalling", t);
            installed = false;
            if (!installWhenDue()) return false;
            try {
                write(lm, lat, lon, speedMetersPerSecond);
                return true;
            } catch (Throwable again) {
                Log.e(TAG, "Retry failed for " + providerName, again);
                installed = false;
                return false;
            }
        }
    }

    private void write(@NonNull LocationManager lm, double lat, double lon, float speedMetersPerSecond) {
        lm.setTestProviderEnabled(providerName, true);
        lm.setTestProviderLocation(providerName, buildLocation(lat, lon, speedMetersPerSecond));
    }

    @NonNull
    private Location buildLocation(double lat, double lon, float speedMetersPerSecond) {
        Location mockLocation = new Location(providerName);
        mockLocation.setLatitude(lat);
        mockLocation.setLongitude(lon);
        mockLocation.setAltitude(3D);
        mockLocation.setTime(System.currentTimeMillis());
        mockLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        mockLocation.setSpeed(speedMetersPerSecond > 0f ? speedMetersPerSecond : 0.01f);
        mockLocation.setBearing(1f);
        mockLocation.setAccuracy(3f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mockLocation.setBearingAccuracyDegrees(0.1f);
            mockLocation.setVerticalAccuracyMeters(0.1f);
            mockLocation.setSpeedAccuracyMetersPerSecond(0.01f);
        }
        // Clients that require a satellite count otherwise ignore the fix and
        // fall back to a real provider while another app is in front.
        Bundle extras = new Bundle();
        extras.putInt("satellites", 12);
        mockLocation.setExtras(extras);
        return mockLocation;
    }

    private boolean installWhenDue() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextInstallAttemptElapsed) return false;
        if (install()) return true;
        nextInstallAttemptElapsed = now + 5_000L;
        return false;
    }

    private boolean install() {
        LocationManager lm = locationManager();
        if (lm == null) return false;
        try {
            try {
                lm.removeTestProvider(providerName);
            } catch (Throwable ignored) {
            }
            try {
                lm.addTestProvider(providerName, false, false, false, false, false, true, true,
                        powerUsage, accuracy);
            } catch (IllegalArgumentException already) {
                // Provider is already a test provider; keep using it.
                Log.w(TAG, providerName + " already present: " + already.getMessage());
            }
            lm.setTestProviderEnabled(providerName, true);
            installed = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Could not install " + providerName, t);
            installed = false;
            return false;
        }
    }

    /**
     * Removes the provider.
     */
    public void shutdown() {
        installed = false;
        try {
            LocationManager lm = locationManager();
            if (lm != null) lm.removeTestProvider(providerName);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private LocationManager locationManager() {
        return (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }
}
