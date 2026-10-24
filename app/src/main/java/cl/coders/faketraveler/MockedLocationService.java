package cl.coders.faketraveler;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Foreground mock-location service.
 * Injection runs on a {@link HandlerThread} and is re-armed by an exact alarm
 * if that thread stalls or the process is killed. A failed
 * {@code setTestProviderLocation} does not cancel the next tick.
 */
public class MockedLocationService extends Service {

    @NonNull
    private static final String TAG = MockedLocationService.class.getSimpleName();

    public static final String ACTION_STOP = "cl.coders.faketraveler.action.STOP";
    public static final String ACTION_START = "cl.coders.faketraveler.action.START";
    /** Resume if needed, otherwise only refresh the live interval. */
    public static final String ACTION_ENSURE = "cl.coders.faketraveler.action.ENSURE";
    public static final String ACTION_WATCHDOG = "cl.coders.faketraveler.action.WATCHDOG";

    public static final String EXTRA_LONGITUDE = "longitude";
    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE_DISTANCE = "longitudeDistance";
    public static final String EXTRA_LATITUDE_DISTANCE = "latitudeDistance";
    public static final String EXTRA_MOCK_MILLI = "mockMilli";
    public static final String EXTRA_MAX_TIMES = "maxTimes";
    public static final String EXTRA_MOCK_SPEED = "mockSpeed";

    private static final String CHANNEL_ID = "mock_location";
    private static final int NOTIFICATION_ID = 42;
    private static final int WATCHDOG_REQUEST = 7;
    private static final long WAKE_LOCK_MS = 10 * 60 * 1000L;

    @NonNull
    protected final MutableLiveData<MockState> mockState = new MutableLiveData<>();
    @NonNull
    protected final MutableLiveData<Location> mockedLocation = new MutableLiveData<>();

    @NonNull
    private final List<MockedLocationProvider> providers = new ArrayList<>();
    @NonNull
    private final Object providerLock = new Object();

    private HandlerThread injectThread;
    private Handler injectHandler;
    private Runnable injectRunnable;
    private PowerManager.WakeLock wakeLock;
    private long wakeLockAcquiredAt;

    private double longitude;
    private double latitude;
    private double longitudeMockedDistance;
    private double latitudeMockedDistance;
    private volatile long mockMilli = MockInterval.CONTINUOUS_DELAY_MS;
    private int maxLocationTimes;
    private float speed;
    private int currentTimes = 0;
    private volatile boolean injecting = false;
    private volatile int loopGeneration;
    private volatile long lastInjectElapsed;
    private long lastUiElapsed;
    private long lastNotificationElapsed;
    private long lastPersistElapsed;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        ensureInjectThread();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FakeTraveler:Inject");
            wakeLock.setReferenceCounted(false);
        }
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        indicateBinding();
        return new MockedBinder(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Keep injecting while FGS is running; only stop when explicitly asked.
        Log.d(TAG, "Client unbound; mock continues in foreground");
        return true; // allow rebind
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        indicateBinding();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must enter foreground quickly after Context.startForegroundService().
        try {
            startAsForeground();
        } catch (Exception e) {
            Log.e(TAG, "Unable to enter foreground", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null) {
            resumeFromPrefsIfNeeded();
            return injecting ? START_STICKY : START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMocking();
            return START_NOT_STICKY;
        }
        if (ACTION_WATCHDOG.equals(action)) {
            handleWatchdog();
            return injecting ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_ENSURE.equals(action)) {
            if (injecting) {
                if (intent.hasExtra(EXTRA_MOCK_MILLI)) {
                    updateInterval(intent.getLongExtra(EXTRA_MOCK_MILLI, mockMilli));
                }
            } else if (intent.hasExtra(EXTRA_LATITUDE)) {
                startFromIntent(intent);
            } else {
                // Settings changed the stored interval. Resume only if a session
                // was already running; do not start one from a bare interval update.
                resumeFromPrefsIfNeeded();
                if (injecting && intent.hasExtra(EXTRA_MOCK_MILLI)) {
                    updateInterval(intent.getLongExtra(EXTRA_MOCK_MILLI, mockMilli));
                }
            }
            return START_STICKY;
        }
        if (ACTION_START.equals(action) || intent.hasExtra(EXTRA_LATITUDE)) {
            startFromIntent(intent);
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Some OEMs kill the process after a swipe. The alarm brings the FGS back.
        if (injecting) {
            persistRunning(true);
            scheduleWatchdog(1_000L);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        boolean resume = injecting;
        stopInjectLoop();
        shutdownProviders();
        releaseWakeLock();
        if (injectThread != null) {
            injectThread.quitSafely();
            injectThread = null;
            injectHandler = null;
        }
        if (resume) {
            scheduleWatchdog(1_000L);
        } else {
            cancelWatchdog();
            mockState.postValue(MockState.NOT_MOCKED);
        }
        super.onDestroy();
    }

    private void indicateBinding() {
        mockState.postValue(injecting ? MockState.MOCKED : MockState.SERVICE_BOUND);
    }

    private void startFromIntent(@NonNull Intent intent) {
        double lon = intent.getDoubleExtra(EXTRA_LONGITUDE, longitude);
        double lat = intent.getDoubleExtra(EXTRA_LATITUDE, latitude);
        double lonDist = intent.getDoubleExtra(EXTRA_LONGITUDE_DISTANCE, 0);
        double latDist = intent.getDoubleExtra(EXTRA_LATITUDE_DISTANCE, 0);
        long milli = intent.hasExtra(EXTRA_MOCK_MILLI)
                ? intent.getLongExtra(EXTRA_MOCK_MILLI, mockMilli)
                : mockMilli;
        int maxTimes = intent.getIntExtra(EXTRA_MAX_TIMES, 0);
        float mockSpeed = intent.getFloatExtra(EXTRA_MOCK_SPEED, 0f);
        startMockedService(lon, lat, lonDist, latDist, milli, maxTimes, mockSpeed);
    }

    protected void startMockedService(double longitude, double latitude, double longitudeDistance,
                                      double latitudeDistance, long mockMilli, int maxTime, float mockSpeed) {
        try {
            this.longitude = longitude;
            this.latitude = latitude;
            this.longitudeMockedDistance = longitudeDistance;
            this.latitudeMockedDistance = latitudeDistance;
            this.mockMilli = MockInterval.sanitizeDelayMillis(mockMilli);
            this.maxLocationTimes = maxTime;
            this.speed = mockSpeed;
            this.currentTimes = 0;
            this.lastInjectElapsed = 0L;

            installProviders();
            startAsForeground();
            refreshWakeLock(true);
            startInjectLoop();
            persistRunning(true);
            mockState.postValue(MockState.MOCKED);
        } catch (SecurityException e) {
            Log.e(TAG, "Could not construct mock location providers!", e);
            stopInjectLoop();
            shutdownProviders();
            cancelWatchdog();
            persistRunning(false);
            stopForegroundCompat();
            mockState.postValue(MockState.MOCK_ERROR);
        }
    }

    private void installProviders() {
        synchronized (providerLock) {
            shutdownProvidersLocked();
            providers.add(new MockedLocationProvider(LocationManager.GPS_PROVIDER, this));
            providers.add(new MockedLocationProvider(LocationManager.NETWORK_PROVIDER, this));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Fused is best-effort. A failure here must not disable GPS.
                providers.add(new MockedLocationProvider(LocationManager.FUSED_PROVIDER, this));
            }
        }
        if (!anyInstalled()) {
            throw new SecurityException("Not allowed to perform MOCK_LOCATION");
        }
    }

    private boolean anyInstalled() {
        synchronized (providerLock) {
            for (MockedLocationProvider provider : providers) {
                if (provider.isInstalled()) return true;
            }
        }
        return false;
    }

    private void ensureInjectThread() {
        if (injectThread != null && injectThread.isAlive() && injectHandler != null) return;
        injectThread = new HandlerThread("FakeTravelerInject", Process.THREAD_PRIORITY_FOREGROUND);
        injectThread.start();
        injectHandler = new Handler(injectThread.getLooper());
    }

    private void startInjectLoop() {
        ensureInjectThread();
        final int generation = ++loopGeneration;
        if (injectHandler != null && injectRunnable != null) {
            injectHandler.removeCallbacks(injectRunnable);
        }
        injecting = true;
        injectRunnable = new Runnable() {
            @Override
            public void run() {
                if (!injecting || generation != loopGeneration || injectHandler == null) return;
                long delay = mockMilli;
                boolean delivered = false;
                try {
                    delivered = pushOnce();
                } catch (Throwable t) {
                    // Previously this escaped the runnable, so postDelayed never ran
                    // and injection stopped while the notification stayed up.
                    Log.e(TAG, "Inject tick failed", t);
                }
                if (!injecting || generation != loopGeneration || injectHandler == null) return;
                if (maxLocationTimes != 0 && currentTimes >= maxLocationTimes) {
                    stopMocking();
                    return;
                }
                if (!delivered) {
                    delay = Math.max(delay, 1000L);
                }
                injectHandler.postDelayed(this, delay);
            }
        };
        if (injectHandler != null) injectHandler.post(injectRunnable);
        scheduleWatchdog(MockInterval.watchdogPeriodMillis(mockMilli));
    }

    private void updateInterval(long delayMillis) {
        long sanitized = MockInterval.sanitizeDelayMillis(delayMillis);
        if (sanitized == mockMilli) return;
        Log.i(TAG, "Interval updated to " + sanitized + " ms");
        mockMilli = sanitized;
        persistInterval();
        if (!injecting) return;
        // New generation drops any tick still queued with the old delay.
        startInjectLoop();
    }

    private void stopInjectLoop() {
        injecting = false;
        if (injectHandler != null && injectRunnable != null) {
            injectHandler.removeCallbacks(injectRunnable);
        }
    }

    /** @return true when at least one provider accepted the fix */
    private boolean pushOnce() {
        float pushSpeed = speed > 0f ? speed : 0.01f;
        Location value = new Location(LocationManager.GPS_PROVIDER);
        value.setLongitude(longitude);
        value.setLatitude(latitude);
        value.setTime(System.currentTimeMillis());
        value.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (speed > 0) {
            value.setSpeed(speed);
            value.setAccuracy(0.1f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                value.setSpeedAccuracyMetersPerSecond(0.01f);
            }
        }

        refreshWakeLock(false);
        long now = SystemClock.elapsedRealtime();
        long uiGap = mockMilli <= MockInterval.CONTINUOUS_DELAY_MS ? 200L : mockMilli;
        if (now - lastUiElapsed >= uiGap) {
            lastUiElapsed = now;
            mockedLocation.postValue(value);
        }

        boolean delivered = false;
        List<MockedLocationProvider> snapshot;
        synchronized (providerLock) {
            snapshot = new ArrayList<>(providers);
        }
        for (MockedLocationProvider prov : snapshot) {
            if (prov.pushLocation(latitude, longitude, pushSpeed)) delivered = true;
        }
        if (!delivered) {
            try {
                installProviders();
            } catch (SecurityException e) {
                Log.e(TAG, "Providers still unavailable", e);
            }
        } else {
            lastInjectElapsed = now;
            ++currentTimes;
            latitude += latitudeMockedDistance;
            longitude += longitudeMockedDistance;
            if (now - lastPersistElapsed >= 5_000L) {
                lastPersistElapsed = now;
                persistPosition();
            }
        }
        updateNotification();
        return delivered;
    }

    private void handleWatchdog() {
        if (!injecting) {
            resumeFromPrefsIfNeeded();
        } else {
            nudgeIfStalled();
        }
        if (injecting) {
            scheduleWatchdog(MockInterval.watchdogPeriodMillis(mockMilli));
        } else {
            cancelWatchdog();
            stopForegroundCompat();
            stopSelf();
        }
    }

    private void nudgeIfStalled() {
        long now = SystemClock.elapsedRealtime();
        if (lastInjectElapsed != 0
                && now - lastInjectElapsed <= MockInterval.stallLimitMillis(mockMilli)) {
            return;
        }
        Log.w(TAG, "Inject loop stalled; restarting");
        ensureInjectThread();
        if (!anyInstalled()) {
            try {
                installProviders();
            } catch (SecurityException e) {
                Log.e(TAG, "Could not reinstall providers after stall", e);
                return;
            }
        }
        startInjectLoop();
    }

    protected void stopMocking() {
        Log.d(TAG, "Stopping mock");
        stopInjectLoop();
        shutdownProviders();
        releaseWakeLock();
        cancelWatchdog();
        persistRunning(false);
        stopForegroundCompat();
        mockState.postValue(MockState.NOT_MOCKED);
        stopSelf();
    }

    private void shutdownProviders() {
        synchronized (providerLock) {
            shutdownProvidersLocked();
        }
    }

    private void shutdownProvidersLocked() {
        for (MockedLocationProvider prov : providers) {
            prov.shutdown();
        }
        providers.clear();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.MockService_ChannelName),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.MockService_ChannelDesc));
        channel.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        lastNotificationElapsed = SystemClock.elapsedRealtime();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        long now = SystemClock.elapsedRealtime();
        // Continuous mode must not repost the notification on every fix.
        if (now - lastNotificationElapsed < 1000L) return;
        lastNotificationElapsed = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stop = new Intent(this, MockedLocationService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String coords = String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
        String cadence = mockMilli <= MockInterval.CONTINUOUS_DELAY_MS
                ? getString(R.string.MockService_Continuous)
                : getString(R.string.MockService_EverySeconds, (int) (mockMilli / 1000L));
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.MockService_Title))
                .setContentText(getString(R.string.MockService_Text, coords, cadence))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.ActivityMain_Stop), stopPi)
                .build();
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The previous code acquired a 1h lock only when {@code !isHeld()}, so the
     * "refresh every tick" comment never ran and the lock expired.
     */
    private void refreshWakeLock(boolean force) {
        if (wakeLock == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && wakeLock.isHeld() && now - wakeLockAcquiredAt < WAKE_LOCK_MS - 60_000L) return;
        if (wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
            }
        }
        wakeLock.acquire(WAKE_LOCK_MS);
        wakeLockAcquiredAt = now;
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
            }
        }
        wakeLockAcquiredAt = 0L;
    }

    private void persistRunning(boolean running) {
        SharedPreferences.Editor ed = prefs().edit();
        ed.putBoolean("mockRunning", running);
        if (running) {
            ed.putLong("lat", Double.doubleToRawLongBits(latitude));
            ed.putLong("lng", Double.doubleToRawLongBits(longitude));
            ed.putInt("mockFrequency", MockInterval.toStoredSeconds(mockMilli));
        }
        ed.apply();
    }

    private void persistPosition() {
        if (!injecting) return;
        SharedPreferences.Editor ed = prefs().edit();
        ed.putBoolean("mockRunning", true);
        ed.putLong("lat", Double.doubleToRawLongBits(latitude));
        ed.putLong("lng", Double.doubleToRawLongBits(longitude));
        ed.putInt("mockFrequency", MockInterval.toStoredSeconds(mockMilli));
        ed.apply();
    }

    private void persistInterval() {
        prefs().edit()
                .putInt("mockFrequency", MockInterval.toStoredSeconds(mockMilli))
                .apply();
    }

    private void resumeFromPrefsIfNeeded() {
        SharedPreferences prefs = prefs();
        if (!prefs.getBoolean("mockRunning", false)) return;
        if (injecting) {
            updateInterval(MockInterval.toDelayMillis(prefs.getInt("mockFrequency", 10)));
            return;
        }
        double lat = Double.longBitsToDouble(prefs.getLong("lat", 0));
        double lon = Double.longBitsToDouble(prefs.getLong("lng", 0));
        double latDist = Double.longBitsToDouble(prefs.getLong("dLat", 0)) / 1000000.0;
        double lonDist = Double.longBitsToDouble(prefs.getLong("dLng", 0)) / 1000000.0;
        int freq = prefs.getInt("mockFrequency", 10);
        Log.i(TAG, "Resuming mock from prefs: " + lat + "," + lon + " intervalSeconds=" + freq);
        startMockedService(lon, lat, lonDist, latDist, MockInterval.toDelayMillis(freq), 0, 0f);
    }

    @NonNull
    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.sharedPrefKey, MODE_PRIVATE);
    }

    private void scheduleWatchdog(long delayMillis) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        PendingIntent pi = watchdogIntent();
        if (am == null || pi == null) return;
        long trigger = SystemClock.elapsedRealtime() + Math.max(1_000L, delayMillis);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Exact alarm denied; using idle alarm", e);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        }
    }

    private void cancelWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        PendingIntent pi = watchdogIntent();
        if (am != null && pi != null) am.cancel(pi);
    }

    private boolean canExact(@NonNull AlarmManager am) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return am.canScheduleExactAlarms();
    }

    @Nullable
    private PendingIntent watchdogIntent() {
        Intent intent = new Intent(this, MockedLocationService.class);
        intent.setAction(ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, WATCHDOG_REQUEST, intent, flags);
        }
        return PendingIntent.getService(this, WATCHDOG_REQUEST, intent, flags);
    }

    public static class MockedBinder extends Binder {
        @NonNull
        private final MockedLocationService service;
        @NonNull
        public final LiveData<MockState> mockState;
        @NonNull
        public final LiveData<Location> mockedLocation;

        public MockedBinder(@NonNull MockedLocationService service) {
            this.service = service;
            this.mockState = service.mockState;
            this.mockedLocation = service.mockedLocation;
        }

        public void continueMock() {
            service.indicateBinding();
        }

        public void startMock(double longitude, double latitude, double longitudeDistance,
                              double latitudeDistance, long mockMilli, int maxTimes, float mockSpeed) {
            service.startMockedService(longitude, latitude, longitudeDistance, latitudeDistance,
                    mockMilli, maxTimes, mockSpeed);
        }

        /** Applies a new inject delay without resetting the current fix. */
        public void setIntervalMillis(long delayMillis) {
            service.updateInterval(delayMillis);
        }

        public void stopMock() {
            service.stopMocking();
        }

        public boolean isInjecting() {
            return service.injecting;
        }
    }
}
