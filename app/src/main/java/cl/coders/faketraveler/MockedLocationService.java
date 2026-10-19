package cl.coders.faketraveler;

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
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Foreground mock-location service.
 * Uses a dedicated HandlerThread loop instead of java.util.Timer so OEM
 * background freezes (e.g. Honor) do not stop injection while the FGS is alive.
 */
public class MockedLocationService extends Service {

    @NonNull
    private static final String TAG = MockedLocationService.class.getSimpleName();

    public static final String ACTION_STOP = "cl.coders.faketraveler.action.STOP";
    public static final String ACTION_START = "cl.coders.faketraveler.action.START";

    public static final String EXTRA_LONGITUDE = "longitude";
    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE_DISTANCE = "longitudeDistance";
    public static final String EXTRA_LATITUDE_DISTANCE = "latitudeDistance";
    public static final String EXTRA_MOCK_MILLI = "mockMilli";
    public static final String EXTRA_MAX_TIMES = "maxTimes";
    public static final String EXTRA_MOCK_SPEED = "mockSpeed";

    private static final String CHANNEL_ID = "mock_location";
    private static final int NOTIFICATION_ID = 42;

    @NonNull
    protected final MutableLiveData<MockState> mockState = new MutableLiveData<>();
    @NonNull
    protected final MutableLiveData<Location> mockedLocation = new MutableLiveData<>();

    @NonNull
    private final List<MockedLocationProvider> providers = new ArrayList<>();

    private HandlerThread injectThread;
    private Handler injectHandler;
    private Runnable injectRunnable;
    private PowerManager.WakeLock wakeLock;

    private double longitude;
    private double latitude;
    private double longitudeMockedDistance;
    private double latitudeMockedDistance;
    private long mockMilli = 2000L;
    private int maxLocationTimes;
    private float speed;
    private int currentTimes = 0;
    private boolean injecting = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        injectThread = new HandlerThread("FakeTravelerInject");
        injectThread.start();
        injectHandler = new Handler(injectThread.getLooper());
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
        if (injecting) {
            mockState.postValue(MockState.MOCKED);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must enter foreground quickly after Context.startForegroundService().
        startAsForeground();

        if (intent == null) {
            // Sticky restart: restore last mock if it was running.
            resumeFromPrefsIfNeeded();
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMocking();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) || intent.hasExtra(EXTRA_LATITUDE)) {
            double lon = intent.getDoubleExtra(EXTRA_LONGITUDE, longitude);
            double lat = intent.getDoubleExtra(EXTRA_LATITUDE, latitude);
            double lonDist = intent.getDoubleExtra(EXTRA_LONGITUDE_DISTANCE, 0);
            double latDist = intent.getDoubleExtra(EXTRA_LATITUDE_DISTANCE, 0);
            long milli = intent.getLongExtra(EXTRA_MOCK_MILLI, mockMilli);
            int maxTimes = intent.getIntExtra(EXTRA_MAX_TIMES, 0);
            float mockSpeed = intent.getFloatExtra(EXTRA_MOCK_SPEED, 0f);
            startMockedService(lon, lat, lonDist, latDist, milli, maxTimes, mockSpeed);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopInjectLoop();
        shutdownProviders();
        releaseWakeLock();
        if (injectThread != null) {
            injectThread.quitSafely();
            injectThread = null;
            injectHandler = null;
        }
        mockState.postValue(MockState.NOT_MOCKED);
        super.onDestroy();
    }

    private void indicateBinding() {
        mockState.postValue(injecting ? MockState.MOCKED : MockState.SERVICE_BOUND);
    }

    protected void startMockedService(double longitude, double latitude, double longitudeDistance,
                                      double latitudeDistance, long mockMilli, int maxTime, float mockSpeed) {
        try {
            this.longitude = longitude;
            this.latitude = latitude;
            this.longitudeMockedDistance = longitudeDistance;
            this.latitudeMockedDistance = latitudeDistance;
            this.mockMilli = Math.max(200L, mockMilli);
            this.maxLocationTimes = maxTime;
            this.speed = mockSpeed;
            this.currentTimes = 0;

            shutdownProviders();
            providers.clear();
            providers.add(new MockedLocationProvider(LocationManager.GPS_PROVIDER, this));
            providers.add(new MockedLocationProvider(LocationManager.NETWORK_PROVIDER, this));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                providers.add(new MockedLocationProvider(LocationManager.FUSED_PROVIDER, this));
            }

            startAsForeground();
            acquireWakeLock();
            startInjectLoop();
            injecting = true;
            persistRunning(true);
            mockState.postValue(MockState.MOCKED);
        } catch (SecurityException e) {
            Log.e(TAG, "Could not construct mock location providers!", e);
            stopForegroundCompat();
            mockState.postValue(MockState.MOCK_ERROR);
        }
    }

    private void startInjectLoop() {
        stopInjectLoop();
        injectRunnable = new Runnable() {
            @Override
            public void run() {
                if (!injecting || injectHandler == null) return;
                pushOnce();
                if (maxLocationTimes != 0 && currentTimes >= maxLocationTimes) {
                    stopMocking();
                    return;
                }
                injectHandler.postDelayed(this, mockMilli);
            }
        };
        injecting = true;
        injectHandler.post(injectRunnable);
    }

    private void stopInjectLoop() {
        injecting = false;
        if (injectHandler != null && injectRunnable != null) {
            injectHandler.removeCallbacks(injectRunnable);
        }
        injectRunnable = null;
    }

    private void pushOnce() {
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

        acquireWakeLock();
        mockedLocation.postValue(value);
        for (MockedLocationProvider prov : providers) {
            prov.pushLocation(latitude, longitude);
        }
        ++currentTimes;
        latitude += latitudeMockedDistance;
        longitude += longitudeMockedDistance;

        updateNotification();
    }

    protected void stopMocking() {
        Log.d(TAG, "Stopping mock");
        stopInjectLoop();
        shutdownProviders();
        releaseWakeLock();
        persistRunning(false);
        stopForegroundCompat();
        mockState.postValue(MockState.NOT_MOCKED);
        stopSelf();
    }

    private void shutdownProviders() {
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
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
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
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.MockService_Title))
                .setContentText(getString(R.string.MockService_Text, coords))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.ActivityMain_Stop), stopPi)
                .build();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L); // 1h max; refreshed by ongoing inject
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
            }
        }
    }


    private void persistRunning(boolean running) {
        SharedPreferences.Editor ed = getSharedPreferences(MainActivity.sharedPrefKey, MODE_PRIVATE).edit();
        ed.putBoolean("mockRunning", running);
        if (running) {
            ed.putLong("lat", Double.doubleToRawLongBits(latitude));
            ed.putLong("lng", Double.doubleToRawLongBits(longitude));
        }
        ed.apply();
    }

    private void resumeFromPrefsIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.sharedPrefKey, MODE_PRIVATE);
        if (!prefs.getBoolean("mockRunning", false)) return;
        if (injecting) return;
        double lat = Double.longBitsToDouble(prefs.getLong("lat", 0));
        double lon = Double.longBitsToDouble(prefs.getLong("lng", 0));
        double latDist = Double.longBitsToDouble(prefs.getLong("dLat", 0)) / 1000000.0;
        double lonDist = Double.longBitsToDouble(prefs.getLong("dLng", 0)) / 1000000.0;
        int freq = prefs.getInt("mockFrequency", 10);
        if (freq <= 0) freq = 1;
        Log.i(TAG, "Sticky/resume mock from prefs: " + lat + "," + lon);
        startMockedService(lon, lat, lonDist, latDist, freq * 1000L, 0, 0f);
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

        public void stopMock() {
            service.stopMocking();
        }

        public boolean isInjecting() {
            return service.injecting;
        }
    }
}
