package cl.coders.faketraveler;

import static cl.coders.faketraveler.MainActivity.SourceChange.CHANGE_FROM_EDITTEXT;
import static cl.coders.faketraveler.MainActivity.SourceChange.CHANGE_FROM_MAP;
import static cl.coders.faketraveler.MainActivity.SourceChange.LOAD;
import static cl.coders.faketraveler.MainActivity.SourceChange.NONE;
import static cl.coders.faketraveler.SharedPrefsUtil.getDouble;
import static cl.coders.faketraveler.SharedPrefsUtil.migrateOldPreferences;
import static cl.coders.faketraveler.SharedPrefsUtil.putDouble;

import android.Manifest;
import android.annotation.SuppressLint;
import androidx.core.app.ActivityCompat;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.PowerManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.maplibre.android.MapLibre;
import org.maplibre.android.WellKnownTileServer;
import org.maplibre.android.module.http.HttpRequestUtil;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity implements ServiceConnection {

    @NonNull
    public static final String sharedPrefKey = "cl.coders.faketraveler.sharedprefs";
    @NonNull
    public static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.######", DecimalFormatSymbols.getInstance(Locale.ROOT));

    static final String DEFAULT_TILE_URL = "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}";
    private static final String OLD_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

    private static final String MARKER_SOURCE_ID = "marker-source";
    private static final String MARKER_LAYER_ID = "marker-layer";

    private MaterialButton buttonApplyStop;
    private MapView mapView;
    private EditText editTextLat;
    private EditText editTextLng;
    private EditText editTextSearch;
    private Context context;
    private int currentVersion;

    @Nullable
    private MapLibreMap mapLibreMap;
    @Nullable
    private GeoJsonSource markerSource;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    @NonNull
    private SourceChange srcChange = NONE;

    @Nullable
    private MockedLocationService.MockedBinder binder = null;
    /** True after bindService until onServiceConnected or a disconnect. */
    private boolean bindRequested;

    // Config
    private int version;
    private String tileUrl;
    private double lat;
    private double lng;
    private double zoom;
    private int mockCount;
    private int mockFrequency;
    private double dLat;
    private double dLng;
    private boolean mockSpeed;
    private long endTime;
    private RecyclerView suggestionList;
    private View suggestionDivider;
    private SuggestionAdapter suggestionAdapter;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this, "", WellKnownTileServer.MapLibre);
        setContentView(R.layout.activity_main);

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                            || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION))) {
                        moveToCurrentLocation();
                    }
                });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_layout), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        context = getApplicationContext();

        buttonApplyStop = findViewById(R.id.button_applyStop);
        MaterialButton buttonSettings = findViewById(R.id.button_settings);
        MaterialButton buttonSearch = findViewById(R.id.button_search);
        editTextLat = findViewById(R.id.editTextLat);
        editTextLng = findViewById(R.id.editTextLng);
        editTextSearch = findViewById(R.id.editTextSearch);
        suggestionDivider = findViewById(R.id.suggestion_divider);
        suggestionList = findViewById(R.id.suggestion_list);
        suggestionAdapter = new SuggestionAdapter(result -> {
            hideSuggestions();
            editTextSearch.setText("");
            setLatLng(result.wgsLat, result.wgsLng, LOAD);
        });
        suggestionList.setLayoutManager(new LinearLayoutManager(this));
        suggestionList.setAdapter(suggestionAdapter);

        buttonApplyStop.setOnClickListener(view -> startOrBindMockService());
        buttonSettings.setOnClickListener(view -> {
            Intent myIntent = new Intent(getBaseContext(), MoreActivity.class);
            startActivity(myIntent);
        });
        buttonSearch.setOnClickListener(view -> {
            hideSuggestions();
            searchAddress(editTextSearch.getText().toString());
        });
        editTextSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideSuggestions();
                searchAddress(editTextSearch.getText().toString());
                return true;
            }
            return false;
        });
        editTextSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
                if (q.isEmpty()) {
                    hideSuggestions();
                    return;
                }
                searchRunnable = () -> fetchAndShowSuggestions(q);
                searchHandler.postDelayed(searchRunnable, 300);
            }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });

        try {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentVersion = (int) (pInfo.getLongVersionCode() >> 32);
            } else {
                currentVersion = pInfo.versionCode;
            }
        } catch (NameNotFoundException e) {
            Log.e(MainActivity.class.toString(), "Could not read version info!", e);
        }

        loadSharedPrefs();
        setupOkHttpClient();
        applyIntentOrDefault(getIntent());

        editTextLat.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!editTextLat.getText().toString().isEmpty() && !editTextLat.getText().toString().equals("-")) {
                    if (srcChange != CHANGE_FROM_MAP) {
                        try {
                            lat = Double.parseDouble(editTextLat.getText().toString());
                            setLatLng(lat, lng, CHANGE_FROM_EDITTEXT);
                        } catch (Throwable t) {
                            Log.e(MainActivity.class.toString(), "Could not read latitude!", t);
                        }
                    }
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        editTextLng.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!editTextLng.getText().toString().isEmpty() && !editTextLng.getText().toString().equals("-")) {
                    if (srcChange != CHANGE_FROM_MAP) {
                        try {
                            lng = Double.parseDouble(editTextLng.getText().toString());
                            setLatLng(lat, lng, CHANGE_FROM_EDITTEXT);
                        } catch (Throwable t) {
                            Log.e(MainActivity.class.toString(), "Could not read longitude!", t);
                        }
                    }
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(map -> {
            mapLibreMap = map;
            map.setStyle(new Style.Builder().fromJson(buildStyleJson(tileUrl)), style -> {
                markerSource = new GeoJsonSource(MARKER_SOURCE_ID,
                        Feature.fromGeometry(Point.fromLngLat(lng, lat)));
                style.addSource(markerSource);
                CircleLayer layer = new CircleLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID);
                layer.setProperties(
                        PropertyFactory.circleRadius(10f),
                        PropertyFactory.circleColor(Color.parseColor("#E53935")),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(Color.WHITE)
                );
                style.addLayer(layer);
            });
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), zoom));
            map.addOnMapClickListener(point -> {
                setLatLng(point.getLatitude(), point.getLongitude(), CHANGE_FROM_MAP);
                return true;
            });
            map.addOnCameraIdleListener(() -> {
                zoom = map.getCameraPosition().zoom;
                saveSettings();
            });
        });

        requestCurrentLocation();

        boolean mockRunning = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE)
                .getBoolean("mockRunning", false);
        boolean unlimited = mockCount <= 0;
        if (mockRunning && (unlimited || endTime == 0 || endTime > System.currentTimeMillis())) {
            changeButtonToStop();
        } else if (!unlimited && endTime > System.currentTimeMillis()) {
            changeButtonToStop();
        } else {
            endTime = 0;
            saveSettings();
        }
    }

    private String buildStyleJson(String url) {
        String escaped = url.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"version\":8,\"sources\":{\"tiles\":{\"type\":\"raster\",\"tiles\":[\"" +
                escaped + "\"],\"tileSize\":256}},\"layers\":[{\"id\":\"tiles\",\"type\":\"raster\",\"source\":\"tiles\"}]}";
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        context = getApplicationContext();
        String previousTileUrl = tileUrl;
        loadSharedPrefs();
        setupOkHttpClient();
        ensureMockServiceRunning();
        if (!tileUrl.equals(previousTileUrl) && mapLibreMap != null) {
            mapLibreMap.setStyle(new Style.Builder().fromJson(buildStyleJson(tileUrl)), style -> {
                markerSource = new GeoJsonSource(MARKER_SOURCE_ID,
                        Feature.fromGeometry(Point.fromLngLat(lng, lat)));
                style.addSource(markerSource);
                CircleLayer layer = new CircleLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID);
                layer.setProperties(
                        PropertyFactory.circleRadius(10f),
                        PropertyFactory.circleColor(Color.parseColor("#E53935")),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(Color.WHITE)
                );
                style.addLayer(layer);
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.setLocale(Locale.SIMPLIFIED_CHINESE);
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        loadSharedPrefs();
        applyIntentOrDefault(intent);
    }

    private void loadSharedPrefs() {
        migrateOldPreferences(context);

        SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);

        version = sharedPref.getInt("version", 0);
        lat = getDouble(sharedPref, "lat", 12);
        lng = getDouble(sharedPref, "lng", 15);
        zoom = getDouble(sharedPref, "zoom", 12);
        mockCount = sharedPref.getInt("mockCount", 0);
        // 0 seconds is continuous mode. Do not rewrite it to 1; that made the
        // setting look saved while the inject loop waited a full second.
        mockFrequency = sharedPref.getInt("mockFrequency", 10);
        dLat = getDouble(sharedPref, "dLat", 0);
        dLng = getDouble(sharedPref, "dLng", 0);
        mockSpeed = sharedPref.getBoolean("mockSpeed", true);
        endTime = sharedPref.getLong("endTime", 0);
        String savedTile = sharedPref.getString("tileUrl", "");
        String geocoderKey = sharedPref.getString("geocoderKey", "").trim();
        boolean isTencentWmts = savedTile.contains("apis.map.qq.com/maptile");
        boolean isDefaultTile = !savedTile.startsWith("http") || savedTile.equals(OLD_TILE_URL)
                || savedTile.equals("https://a.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png")
                || savedTile.equals(DEFAULT_TILE_URL)
                || isTencentWmts;
        if (!isDefaultTile) {
            tileUrl = savedTile;
        } else if (!geocoderKey.isEmpty()) {
            tileUrl = "https://apis.map.qq.com/maptile/base/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=default&STYLE=default&FORMAT=image/png&TILEMATRIXSET=EPSG:3857&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&key=" + geocoderKey;
        } else {
            tileUrl = DEFAULT_TILE_URL;
        }

        if (version != currentVersion || !tileUrl.equals(savedTile)) {
            version = currentVersion;
            saveSettings();
        }
    }

    private void setupOkHttpClient() {
        SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
        String sk = sharedPref.getString("geocoderSk", "").trim();
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        if (!sk.isEmpty() && tileUrl.contains("apis.map.qq.com/maptile")) {
            builder.addInterceptor(new TencentTileSigningInterceptor(sk));
        }
        HttpRequestUtil.setOkHttpClient(builder.build());
    }

    private void saveSettings() {
        Editor editor = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE).edit();

        editor.putInt("version", version);
        putDouble(editor, "lat", lat);
        putDouble(editor, "lng", lng);
        putDouble(editor, "zoom", zoom);
        editor.putInt("mockCount", mockCount);
        editor.putInt("mockFrequency", mockFrequency);
        putDouble(editor, "dLat", dLat);
        putDouble(editor, "dLng", dLng);
        editor.putBoolean("mockSpeed", mockSpeed);
        editor.putLong("endTime", endTime);
        editor.putString("tileUrl", tileUrl);

        editor.apply();
    }

    private void applyIntentOrDefault(Intent intent) {
        String intentData = intent.getDataString();
        if (intentData != null) {
            try {
                GeoUri uri = GeoUri.parse(intentData);
                Log.i(MainActivity.class.toString(), "Received geo intent: " + uri);
                if (uri != null) {
                    lat = uri.lat();
                    lng = uri.lng();
                    Double zoomTmp = uri.zoom();
                    if (zoomTmp != null) zoom = zoomTmp;
                }
            } catch (Throwable t) {
                Log.e(MainActivity.class.toString(), "Could not read geo intent!", t);
            }
        }

        setLatLng(lat, lng, LOAD);
    }

    /**
     * Apply a mocked location. Called when "Apply" button is pressed.
     */
    protected void applyLocation() {
        if (latIsEmpty() || lngIsEmpty()) {
            showSnackbar(context.getResources().getString(R.string.MainActivity_NoLatLong));
            return;
        }

        try {
            lat = Double.parseDouble(editTextLat.getText().toString());
            lng = Double.parseDouble(editTextLng.getText().toString());
        } catch (NumberFormatException e) {
            showSnackbar(context.getResources().getString(R.string.MainActivity_NoLatLong));
            return;
        }

        if (binder != null) {
            if (mockCount < 0) mockCount = 0;
            binder.startMock(lng, lat, dLng / 1000000, dLat / 1000000,
                    MockInterval.toDelayMillis(mockFrequency), mockCount, currentMockSpeed());
            rememberSessionEnd();
        }
    }

    /**
     * Interval 0 has no end time. A positive count still ends inside the service
     * after that many injections; the UI must not treat that session as already finished.
     */
    private void rememberSessionEnd() {
        if (mockCount <= 0 || mockFrequency <= 0) {
            endTime = 0;
        } else {
            endTime = System.currentTimeMillis() + (mockCount - 1L) * mockFrequency * 1000L;
        }
        saveSettings();
    }

    private float currentMockSpeed() {
        if (!mockSpeed) return 0f;
        float[] result = {0f};
        Location.distanceBetween(lat, lng, lat + dLat / 1000000, lng + dLng / 1000000, result);
        result[0] /= MockInterval.speedDivisorMillis(mockFrequency);
        return result[0];
    }

    private Intent buildServiceIntent(String action) {
        Intent intent = new Intent(this, MockedLocationService.class);
        intent.setAction(action);
        intent.putExtra(MockedLocationService.EXTRA_LONGITUDE, lng);
        intent.putExtra(MockedLocationService.EXTRA_LATITUDE, lat);
        intent.putExtra(MockedLocationService.EXTRA_LONGITUDE_DISTANCE, dLng / 1000000d);
        intent.putExtra(MockedLocationService.EXTRA_LATITUDE_DISTANCE, dLat / 1000000d);
        intent.putExtra(MockedLocationService.EXTRA_MOCK_MILLI, MockInterval.toDelayMillis(mockFrequency));
        intent.putExtra(MockedLocationService.EXTRA_MAX_TIMES, Math.max(mockCount, 0));
        intent.putExtra(MockedLocationService.EXTRA_MOCK_SPEED, currentMockSpeed());
        return intent;
    }

    /**
     * If prefs say mock is running, make sure the foreground service is actually
     * injecting. Recreating the activity used to only relabel the button as Stop.
     */
    private void ensureMockServiceRunning() {
        SharedPreferences prefs = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("mockRunning", false)) return;
        long delay = MockInterval.toDelayMillis(mockFrequency);
        if (binder != null) {
            binder.setIntervalMillis(delay);
            if (binder.isInjecting()) return;
        }
        // Do not send the map's current lat/lng. onCreate may already have
        // replaced it with a real last-known fix. Resume the last injected point.
        Intent intent = new Intent(this, MockedLocationService.class);
        intent.setAction(MockedLocationService.ACTION_ENSURE);
        intent.putExtra(MockedLocationService.EXTRA_MOCK_MILLI, delay);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
        bindMockService(intent);
    }

    private void bindMockService(Intent intent) {
        if (binder != null || bindRequested) return;
        bindRequested = true;
        bindService(intent, this, BIND_AUTO_CREATE);
    }

    /**
     * Shows a snackbar
     */
    void showSnackbar(String str) {
        Snackbar.make(findViewById(R.id.main_layout), str, Snackbar.LENGTH_SHORT).show();
    }

    /**
     * Shows a snackbar
     */
    void showSnackbar(@StringRes int strRes) {
        Snackbar.make(findViewById(R.id.main_layout), strRes, Snackbar.LENGTH_SHORT).show();
    }

    boolean latIsEmpty() {
        return editTextLat.getText().toString().isBlank();
    }

    boolean lngIsEmpty() {
        return editTextLng.getText().toString().isBlank();
    }

    // Place marker without moving camera (used on map tap)
    private void placeMarker(double markerLat, double markerLng) {
        if (markerSource == null) return;
        markerSource.setGeoJson(Feature.fromGeometry(Point.fromLngLat(markerLng, markerLat)));
    }

    // Place marker and center camera (used when EditText changes or mock location updates)
    protected void setMapMarker(double markerLat, double markerLng) {
        if (mapLibreMap == null) return;
        placeMarker(markerLat, markerLng);
        mapLibreMap.moveCamera(CameraUpdateFactory.newLatLng(new LatLng(markerLat, markerLng)));
    }

    void changeButtonToApply() {
        buttonApplyStop.setText(context.getResources().getString(R.string.ActivityMain_Apply));
        buttonApplyStop.setOnClickListener(view -> startOrBindMockService());
    }

    private void startOrBindMockService() {
        if (latIsEmpty() || lngIsEmpty()) {
            showSnackbar(context.getResources().getString(R.string.MainActivity_NoLatLong));
            return;
        }
        try {
            lat = Double.parseDouble(editTextLat.getText().toString());
            lng = Double.parseDouble(editTextLng.getText().toString());
        } catch (NumberFormatException e) {
            showSnackbar(context.getResources().getString(R.string.MainActivity_NoLatLong));
            return;
        }
        if (mockCount < 0) mockCount = 0;
        rememberSessionEnd();
        ensureRuntimePermissions();
        // Start the FGS with the real interval before any system dialog pauses us.
        // Waiting for the bind callback dropped the first start when that dialog
        // stopped the activity before LiveData delivered SERVICE_BOUND.
        Intent intent = buildServiceIntent(MockedLocationService.ACTION_START);
        if (binder != null) {
            binder.startMock(lng, lat, dLng / 1000000d, dLat / 1000000d,
                    MockInterval.toDelayMillis(mockFrequency), Math.max(mockCount, 0), currentMockSpeed());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
            bindMockService(intent);
        } else {
            startService(intent);
            bindMockService(intent);
        }
        requestIgnoreBatteryOptimizations();
    }


    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            Log.w(MainActivity.class.toString(), "Could not request ignore battery optimizations", e);
        }
    }

    private void ensureRuntimePermissions() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 1001);
        }
    }

    void changeButtonToStop() {
        buttonApplyStop.setText(context.getResources().getString(R.string.ActivityMain_Stop));
        buttonApplyStop.setOnClickListener(view -> {
            if (binder != null) {
                binder.stopMock();
            } else {
                Intent stop = new Intent(this, MockedLocationService.class);
                stop.setAction(MockedLocationService.ACTION_STOP);
                startService(stop);
            }
            bindRequested = false;
            try {
                unbindService(this);
            } catch (IllegalArgumentException ignored) {
            }
            disconnectService();
        });
    }

    void setLatLng(double mLat, double mLng, SourceChange srcChange) {
        lat = mLat;
        lng = mLng;

        if (srcChange == CHANGE_FROM_EDITTEXT || srcChange == LOAD) {
            setMapMarker(lat, lng);
        } else if (srcChange == CHANGE_FROM_MAP) {
            placeMarker(lat, lng);
        }

        if (srcChange == CHANGE_FROM_MAP || srcChange == LOAD) {
            this.srcChange = CHANGE_FROM_MAP;
            editTextLat.setText(DECIMAL_FORMAT.format(lat));
            editTextLng.setText(DECIMAL_FORMAT.format(lng));
            this.srcChange = NONE;
        }

        saveSettings();
    }

    private static boolean containsCjk(String query) {
        for (int i = 0; i < query.length(); ) {
            int cp = query.codePointAt(i);
            if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private void searchAddress(String query) {
        if (query == null || query.isBlank()) return;
        SharedPreferences prefs = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
        String key = prefs.getString("geocoderKey", "").trim();
        double biasLat = lat;
        double biasLng = lng;
        new Thread(() -> {
            try {
                boolean hasChinese = containsCjk(query);
                final double[] coords;
                if (!key.isEmpty() && hasChinese) {
                    double[] r = geocodeTencentSuggestion(query, key, biasLat, biasLng);
                    coords = (r != null) ? r : geocodeNominatim(query);
                } else {
                    coords = geocodeNominatim(query);
                }
                if (coords != null) {
                    runOnUiThread(() -> setLatLng(coords[0], coords[1], LOAD));
                } else {
                    runOnUiThread(() -> showSnackbar(R.string.MainActivity_SearchNotFound));
                }
            } catch (Exception e) {
                Log.e(MainActivity.class.toString(), "Geocode search failed", e);
                String msg = e.getMessage();
                runOnUiThread(() -> showSnackbar(msg != null ? msg : getString(R.string.MainActivity_SearchNotFound)));
            }
        }).start();
    }

    private void fetchAndShowSuggestions(String query) {
        SharedPreferences prefs = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
        String key = prefs.getString("geocoderKey", "").trim();
        double biasLat = lat;
        double biasLng = lng;
        new Thread(() -> {
            try {
                boolean hasChinese = containsCjk(query);
                final List<SearchResult> results;
                if (!key.isEmpty() && hasChinese) {
                    results = fetchTencentSuggestions(query, key, biasLat, biasLng);
                } else {
                    results = fetchNominatimSuggestions(query);
                }
                runOnUiThread(() -> showSuggestions(results));
            } catch (Exception e) {
                Log.w(MainActivity.class.toString(), "Suggestion fetch failed", e);
            }
        }).start();
    }

    private List<SearchResult> fetchTencentSuggestions(String query, String key, double biasLat, double biasLng) throws Exception {
        String sk = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE)
                .getString("geocoderSk", "").trim();
        String path = "/ws/place/v1/suggestion";
        TreeMap<String, String> params = new TreeMap<>();
        params.put("keyword", query);
        params.put("key", key);
        params.put("output", "json");
        params.put("page_size", "5");
        if (biasLat != 0 || biasLng != 0) {
            params.put("location", String.format(Locale.ROOT, "%.6f,%.6f", biasLat, biasLng));
        }
        if (!sk.isEmpty()) {
            params.put("sig", computeTencentSig(path, params, sk));
        }
        StringBuilder urlSb = new StringBuilder("https://apis.map.qq.com").append(path).append("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) urlSb.append("&");
            urlSb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            first = false;
        }
        String body = httpGet(urlSb.toString(), null);
        JSONObject obj = new JSONObject(body);
        if (obj.getInt("status") != 0) return new ArrayList<>();
        JSONArray data = obj.optJSONArray("data");
        if (data == null) return new ArrayList<>();
        List<SearchResult> list = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String title = item.optString("title", "");
            String address = item.optString("address", "");
            JSONObject loc = item.optJSONObject("location");
            if (loc == null) continue;
            double[] wgs = CoordConverter.gcj02ToWgs84(loc.getDouble("lat"), loc.getDouble("lng"));
            list.add(new SearchResult(title, address, wgs[0], wgs[1]));
        }
        return list;
    }

    private List<SearchResult> fetchNominatimSuggestions(String query) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=json&limit=5&q="
                + URLEncoder.encode(query, "UTF-8");
        String body = httpGet(url, "FakeTraveler/1.0 (https://github.com/mcastillof/FakeTraveler)");
        JSONArray arr = new JSONArray(body);
        List<SearchResult> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);
            String displayName = item.optString("display_name", "");
            int commaIdx = displayName.indexOf(',');
            String title = commaIdx > 0 ? displayName.substring(0, commaIdx).trim() : displayName;
            String address = commaIdx > 0 ? displayName.substring(commaIdx + 1).trim() : "";
            double wgsLat = Double.parseDouble(item.getString("lat"));
            double wgsLng = Double.parseDouble(item.getString("lon"));
            list.add(new SearchResult(title, address, wgsLat, wgsLng));
        }
        return list;
    }

    private void showSuggestions(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            hideSuggestions();
            return;
        }
        suggestionAdapter.setResults(results);
        suggestionDivider.setVisibility(View.VISIBLE);
        suggestionList.setVisibility(View.VISIBLE);
    }

    private void hideSuggestions() {
        if (suggestionDivider != null) suggestionDivider.setVisibility(View.GONE);
        if (suggestionList != null) suggestionList.setVisibility(View.GONE);
    }

    @Nullable
    private double[] geocodeNominatim(String query) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                + URLEncoder.encode(query, "UTF-8");
        String body = httpGet(url, "FakeTraveler/1.0 (https://github.com/mcastillof/FakeTraveler)");
        JSONArray arr = new JSONArray(body);
        if (arr.length() == 0) return null;
        JSONObject first = arr.getJSONObject(0);
        double lat = Double.parseDouble(first.getString("lat"));
        double lon = Double.parseDouble(first.getString("lon"));
        return new double[]{lat, lon};
    }

    @Nullable
    private double[] geocodeTencentSuggestion(String query, String key, double biasLat, double biasLng) throws Exception {
        String sk = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE)
                .getString("geocoderSk", "").trim();
        String path = "/ws/place/v1/suggestion";
        TreeMap<String, String> params = new TreeMap<>();
        params.put("keyword", query);
        params.put("key", key);
        params.put("output", "json");
        params.put("page_size", "1");
        if (biasLat != 0 || biasLng != 0) {
            params.put("location", String.format(Locale.ROOT, "%.6f,%.6f", biasLat, biasLng));
        }
        if (!sk.isEmpty()) {
            params.put("sig", computeTencentSig(path, params, sk));
        }
        StringBuilder urlSb = new StringBuilder("https://apis.map.qq.com").append(path).append("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) urlSb.append("&");
            urlSb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            first = false;
        }
        String body = httpGet(urlSb.toString(), null);
        JSONObject obj = new JSONObject(body);
        int status = obj.getInt("status");
        if (status != 0) {
            Log.w(MainActivity.class.toString(), "Tencent suggest status=" + status + " " + obj.optString("message"));
            return null;
        }
        JSONArray data = obj.optJSONArray("data");
        if (data == null || data.length() == 0) return null;
        JSONObject loc = data.getJSONObject(0).optJSONObject("location");
        if (loc == null) return null;
        double gcjLat = loc.getDouble("lat");
        double gcjLng = loc.getDouble("lng");
        return CoordConverter.gcj02ToWgs84(gcjLat, gcjLng);
    }

    private String computeTencentSig(String path, TreeMap<String, String> params, String sk) throws Exception {
        StringBuilder sb = new StringBuilder(path).append("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        sb.append(sk);
        byte[] hash = MessageDigest.getInstance("MD5").digest(sb.toString().getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private String httpGet(String urlStr, @Nullable String userAgent) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        bindRequested = false;
        binder = (MockedLocationService.MockedBinder) service;
        binder.mockState.observe(this, this::onMockedStateChange);
        binder.mockedLocation.observe(this, this::onMockedLocationChange);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        disconnectService();
    }

    private void disconnectService() {
        bindRequested = false;
        if (binder == null) return;
        binder.mockState.removeObservers(this);
        binder.mockedLocation.removeObservers(this);
        binder = null;
        indicateMockStop();
    }

    private void onMockedStateChange(MockState state) {
        switch (state) {
            case NOT_MOCKED -> indicateMockStop();
            case SERVICE_BOUND -> applyLocation();
            case MOCKED -> {
                changeButtonToStop();
                showSnackbar(R.string.MainActivity_MockApplied);
            }
            case MOCK_ERROR -> showSnackbar(R.string.MainActivity_MockNotApplied);
        }
    }

    private void indicateMockStop() {
        showSnackbar(R.string.MainActivity_MockStopped);
        changeButtonToApply();
    }

    private void onMockedLocationChange(Location location) {
        setMapMarker(location.getLatitude(), location.getLongitude());
    }

    private void requestCurrentLocation() {
        boolean hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (hasFine || hasCoarse) {
            moveToCurrentLocation();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    @SuppressLint("MissingPermission")
    private void moveToCurrentLocation() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        Location loc = null;
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        }
        if (loc == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }

        if (loc != null) {
            setLatLng(loc.getLatitude(), loc.getLongitude(), LOAD);
        } else {
            String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            if (!lm.isProviderEnabled(provider)) return;
            lm.requestLocationUpdates(provider, 0, 0, new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
                    setLatLng(location.getLatitude(), location.getLongitude(), LOAD);
                    lm.removeUpdates(this);
                }
            }, Looper.getMainLooper());
        }
    }

    static class SearchResult {
        final String title;
        final String address;
        final double wgsLat;
        final double wgsLng;

        SearchResult(String title, String address, double wgsLat, double wgsLng) {
            this.title = title;
            this.address = address;
            this.wgsLat = wgsLat;
            this.wgsLng = wgsLng;
        }
    }

    static class SuggestionAdapter extends RecyclerView.Adapter<SuggestionAdapter.ViewHolder> {
        interface OnSuggestionClick {
            void onClick(SearchResult result);
        }

        private final OnSuggestionClick listener;
        private List<SearchResult> results = new ArrayList<>();

        SuggestionAdapter(OnSuggestionClick listener) {
            this.listener = listener;
        }

        void setResults(List<SearchResult> newResults) {
            this.results = newResults;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search_suggestion, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SearchResult r = results.get(position);
            holder.title.setText(r.title);
            holder.address.setText(r.address);
            holder.itemView.setOnClickListener(v -> listener.onClick(r));
        }

        @Override
        public int getItemCount() {
            return results.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView address;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.tv_suggestion_title);
                address = itemView.findViewById(R.id.tv_suggestion_address);
            }
        }
    }

    private static class TencentTileSigningInterceptor implements Interceptor {
        private final String sk;

        TencentTileSigningInterceptor(String sk) {
            this.sk = sk;
        }

        @NonNull
        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request request = chain.request();
            okhttp3.HttpUrl url = request.url();
            if (!url.host().equals("apis.map.qq.com") || !url.encodedPath().startsWith("/maptile")) {
                return chain.proceed(request);
            }
            TreeMap<String, String> params = new TreeMap<>();
            for (String name : url.queryParameterNames()) {
                params.put(name, url.queryParameter(name));
            }
            String sig = computeTileSig(url.encodedPath(), params, sk);
            okhttp3.HttpUrl signed = url.newBuilder().addQueryParameter("sig", sig).build();
            Response response = chain.proceed(request.newBuilder().url(signed).build());
            String ct = response.header("Content-Type", "");
            if (ct != null && ct.contains("application/json")) {
                response.close();
                String z = params.get("TILEMATRIX");
                String x = params.get("TILECOL");
                String y = params.get("TILEROW");
                String fallbackUrl = "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=" + x + "&y=" + y + "&z=" + z;
                return chain.proceed(new Request.Builder()
                        .url(fallbackUrl)
                        .header("User-Agent", "FakeTraveler/1.0")
                        .build());
            }
            return response;
        }

        private static String computeTileSig(String path, TreeMap<String, String> params, String sk) {
            StringBuilder sb = new StringBuilder(path).append("?");
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) sb.append("&");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            sb.append(sk);
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format("%02x", b));
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                return "";
            }
        }
    }

    public enum SourceChange {
        NONE, LOAD, CHANGE_FROM_EDITTEXT, CHANGE_FROM_MAP
    }
}
