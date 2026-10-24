package cl.coders.faketraveler;

import static cl.coders.faketraveler.MainActivity.DECIMAL_FORMAT;
import static cl.coders.faketraveler.MainActivity.sharedPrefKey;
import static cl.coders.faketraveler.SharedPrefsUtil.getDouble;
import static cl.coders.faketraveler.SharedPrefsUtil.putDouble;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

public class MoreActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.setLocale(Locale.SIMPLIFIED_CHINESE);
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_more);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.more_layout), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        Context context = getApplicationContext();
        SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);

        TextView tvLeafletLicense = findViewById(R.id.tv_LeafletLicense);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tvLeafletLicense.setText(Html.fromHtml(getString(R.string.ActivityMore_LeafletLicense),
                    Html.FROM_HTML_MODE_LEGACY));
        } else {
            tvLeafletLicense.setText(Html.fromHtml(getString(R.string.ActivityMore_LeafletLicense)));
        }
        tvLeafletLicense.setMovementMethod(LinkMovementMethod.getInstance());

        EditText etDMockLat = findViewById(R.id.et_DMockLat);
        etDMockLat.setText(DECIMAL_FORMAT.format(getDouble(sharedPref, "dLat", 0)));
        etDMockLat.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Context context = getApplicationContext();
                SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();

                if (etDMockLat.getText().toString().isBlank()) {
                    putDouble(editor, "dLat", 0);
                } else {
                    try {
                        putDouble(editor, "dLat", Double.parseDouble(etDMockLat.getText().toString()));
                    } catch (Throwable t) {
                        Log.e(MoreActivity.class.toString(), "Could not parse dLat!", t);
                    }
                }

                editor.apply();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start,
                                      int before, int count) {
            }
        });

        EditText etDMockLon = findViewById(R.id.et_DMockLon);
        etDMockLon.setText(DECIMAL_FORMAT.format(getDouble(sharedPref, "dLng", 0)));
        etDMockLon.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Context context = getApplicationContext();
                SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();

                if (etDMockLon.getText().toString().isBlank()) {
                    putDouble(editor, "dLng", 0);
                } else {
                    try {
                        putDouble(editor, "dLng", Double.parseDouble(etDMockLon.getText().toString()));
                    } catch (Throwable t) {
                        Log.e(MoreActivity.class.toString(), "Could not parse dLng!", t);
                    }
                }

                editor.apply();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start,
                                      int before, int count) {
            }
        });

        EditText etMockCount = findViewById(R.id.et_MockCount);
        etMockCount.setText(String.format(Locale.ROOT, "%d", sharedPref.getInt("mockCount", 0)));
        etMockCount.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Context context = getApplicationContext();
                SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();

                if (etMockCount.getText().toString().isBlank()) {
                    editor.putInt("mockCount", 0);
                } else {
                    try {
                        editor.putInt("mockCount", Integer.parseInt(etMockCount.getText().toString()));
                    } catch (Throwable t) {
                        Log.e(MoreActivity.class.toString(), "Could not parse mockCount!", t);
                    }
                }

                editor.apply();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start,
                                      int before, int count) {
            }
        });

        EditText etMockFrequency = findViewById(R.id.et_MockFrequency);
        etMockFrequency.setText(String.format(Locale.ROOT, "%d", sharedPref.getInt("mockFrequency", 10)));
        etMockFrequency.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Context context = getApplicationContext();
                SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();

                if (etMockFrequency.getText().toString().isBlank()) {
                    // Empty while the user is deleting the old value. Do not
                    // write 10, or interval 0 never survives until Save.
                    return;
                }
                int seconds;
                try {
                    seconds = Integer.parseInt(etMockFrequency.getText().toString());
                } catch (Throwable t) {
                    Log.e(MoreActivity.class.toString(), "Could not parse mockFrequency!", t);
                    return;
                }
                editor.putInt("mockFrequency", seconds);
                editor.apply();
                // Apply to a live inject loop now. Otherwise 0 stays on screen
                // and the service keeps the previous delay until a process restart.
                pushIntervalToRunningService(seconds);
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });
        etMockFrequency.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                persistSettings(true);
                return true;
            }
            return false;
        });

        CheckBox mockSpeed = findViewById(R.id.cb_MockSpeed);
        mockSpeed.setChecked(sharedPref.getBoolean("mockSpeed", true));
        mockSpeed.setOnCheckedChangeListener((compoundButton, b) -> {
            Context context1 = getApplicationContext();
            SharedPreferences sharedPref1 = context1.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref1.edit();

            editor.putBoolean("mockSpeed", mockSpeed.isChecked());

            editor.apply();
        });


        EditText etAmapKey = findViewById(R.id.et_AmapKey);
        etAmapKey.setText(sharedPref.getString("geocoderKey", ""));
        etAmapKey.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Context context = getApplicationContext();
                SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putString("geocoderKey", etAmapKey.getText().toString().trim());
                editor.apply();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        EditText etTencentSk = findViewById(R.id.et_TencentSk);
        etTencentSk.setText(sharedPref.getString("geocoderSk", ""));
        etTencentSk.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Context context = getApplicationContext();
                SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putString("geocoderSk", etTencentSk.getText().toString().trim());
                editor.apply();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        MaterialButton btnSave = findViewById(R.id.btn_SaveSettings);
        btnSave.setOnClickListener(v -> persistSettings(true));

        MaterialButton btnRestart = findViewById(R.id.btn_RestartApp);
        btnRestart.setOnClickListener(v -> {
            if (!persistSettings(false)) {
                showSnackbar(R.string.ActivityMore_SaveInvalid);
                return;
            }
            android.app.PendingIntent pending = android.app.PendingIntent.getActivity(
                    getApplicationContext(), 0,
                    getPackageManager().getLaunchIntentForPackage(getPackageName()),
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            ((android.app.AlarmManager) getSystemService(ALARM_SERVICE))
                    .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 200, pending);
            android.os.Process.killProcess(android.os.Process.myPid());
        });

        EditText etTileUrl = findViewById(R.id.et_MapProvider);
        etTileUrl.setText(sharedPref.getString("tileUrl", MainActivity.DEFAULT_TILE_URL));
        etTileUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Context context = getApplicationContext();
                SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                String val = etTileUrl.getText().toString().trim();
                editor.putString("tileUrl", val.isEmpty() ? MainActivity.DEFAULT_TILE_URL : val);
                editor.apply();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });
    }

    @Override
    protected void onPause() {
        // Leaving the screen must flush the form. Text watchers use apply(),
        // which can still be in memory when the process is backgrounded.
        persistSettings(false);
        super.onPause();
    }

    /**
     * Writes every settings field. Interval 0 is stored as 0 (continuous).
     *
     * @return false when a numeric field cannot be parsed
     */
    private boolean persistSettings(boolean notify) {
        EditText countView = findViewById(R.id.et_MockCount);
        EditText frequencyView = findViewById(R.id.et_MockFrequency);
        EditText dLatView = findViewById(R.id.et_DMockLat);
        EditText dLngView = findViewById(R.id.et_DMockLon);
        CheckBox speedView = findViewById(R.id.cb_MockSpeed);
        EditText keyView = findViewById(R.id.et_AmapKey);
        EditText skView = findViewById(R.id.et_TencentSk);
        EditText tileView = findViewById(R.id.et_MapProvider);
        if (countView == null || frequencyView == null) return false;

        int count;
        String countText = countView.getText().toString().trim();
        if (countText.isEmpty()) {
            count = 0;
        } else {
            try {
                count = Integer.parseInt(countText);
            } catch (NumberFormatException e) {
                if (notify) showSnackbar(R.string.ActivityMore_SaveInvalid);
                return false;
            }
        }
        if (count < 0) count = 0;

        String frequencyText = frequencyView.getText().toString().trim();
        if (frequencyText.isEmpty()) {
            if (notify) showSnackbar(R.string.ActivityMore_SaveInvalid);
            return false;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(frequencyText);
        } catch (NumberFormatException e) {
            if (notify) showSnackbar(R.string.ActivityMore_SaveInvalid);
            return false;
        }
        if (seconds < 0) {
            if (notify) showSnackbar(R.string.ActivityMore_SaveInvalid);
            return false;
        }

        double dLat = 0;
        double dLng = 0;
        String dLatText = dLatView.getText().toString().trim();
        String dLngText = dLngView.getText().toString().trim();
        try {
            if (!dLatText.isEmpty()) dLat = Double.parseDouble(dLatText);
            if (!dLngText.isEmpty()) dLng = Double.parseDouble(dLngText);
        } catch (NumberFormatException e) {
            if (notify) showSnackbar(R.string.ActivityMore_SaveInvalid);
            return false;
        }

        String tile = tileView.getText().toString().trim();
        SharedPreferences.Editor editor = getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE).edit();
        editor.putInt("mockCount", count);
        editor.putInt("mockFrequency", seconds);
        putDouble(editor, "dLat", dLat);
        putDouble(editor, "dLng", dLng);
        editor.putBoolean("mockSpeed", speedView.isChecked());
        editor.putString("geocoderKey", keyView.getText().toString().trim());
        editor.putString("geocoderSk", skView.getText().toString().trim());
        editor.putString("tileUrl", tile.isEmpty() ? MainActivity.DEFAULT_TILE_URL : tile);
        if (!editor.commit()) {
            if (notify) showSnackbar(R.string.ActivityMore_SaveInvalid);
            return false;
        }
        pushIntervalToRunningService(seconds);
        if (notify) showSnackbar(R.string.ActivityMore_Saved);
        return true;
    }

    private void showSnackbar(int message) {
        Snackbar.make(findViewById(R.id.more_layout), message, Snackbar.LENGTH_SHORT).show();
    }

    /**
     * Push a new interval into an already-running mock. Starting a session is
     * still the Apply button; this only changes the live delay.
     */
    private void pushIntervalToRunningService(int intervalSeconds) {
        SharedPreferences prefs = getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("mockRunning", false)) return;
        Intent intent = new Intent(this, MockedLocationService.class);
        intent.setAction(MockedLocationService.ACTION_ENSURE);
        intent.putExtra(MockedLocationService.EXTRA_MOCK_MILLI, MockInterval.toDelayMillis(intervalSeconds));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
    }

}
