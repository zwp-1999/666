package com.adskip.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityServiceInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private TextView tvStatus;
    private TextView tvDelay;
    private TextView tvWindow;
    private TextView tvLog;
    private Button btnAccessibility;
    private Switch swEnable;
    private SeekBar sbDelay;
    private SeekBar sbWindow;
    private RadioGroup rgStrategy;
    private RadioGroup rgCorner;
    private EditText etWhitelist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvDelay = findViewById(R.id.tvDelay);
        tvWindow = findViewById(R.id.tvWindow);
        tvLog = findViewById(R.id.tvLog);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        swEnable = findViewById(R.id.swEnable);
        sbDelay = findViewById(R.id.sbDelay);
        sbWindow = findViewById(R.id.sbWindow);
        rgStrategy = findViewById(R.id.rgStrategy);
        rgCorner = findViewById(R.id.rgCorner);
        etWhitelist = findViewById(R.id.etWhitelist);

        loadValues();
        bindEvents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        tvLog.setText(SkipLog.dump());
    }

    private SharedPreferences sp() {
        return Prefs.sp(this);
    }

    private void loadValues() {
        swEnable.setChecked(Prefs.isEnabled(this));

        int delay = clamp(Prefs.getDelay(this), 0, 2000);
        sbDelay.setProgress(delay);
        tvDelay.setText("点击延迟：" + delay + " ms");

        int seconds = clamp(Prefs.getWindow(this) / 1000, 2, 10);
        sbWindow.setProgress(seconds - 2);
        tvWindow.setText("启动后检测时长：" + seconds + " 秒");

        switch (Prefs.getStrategy(this)) {
            case 0:
                rgStrategy.check(R.id.rbStrategy0);
                break;
            case 1:
                rgStrategy.check(R.id.rbStrategy1);
                break;
            default:
                rgStrategy.check(R.id.rbStrategy2);
                break;
        }

        switch (Prefs.getCorner(this)) {
            case 1:
                rgCorner.check(R.id.rbCorner1);
                break;
            case 2:
                rgCorner.check(R.id.rbCorner2);
                break;
            case 3:
                rgCorner.check(R.id.rbCorner3);
                break;
            default:
                rgCorner.check(R.id.rbCorner0);
                break;
        }

        etWhitelist.setText(Prefs.getWhitelist(this));
    }

    private void bindEvents() {
        btnAccessibility.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "请在列表中找到「开屏跳过」并开启", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "无法打开无障碍设置，请手动进入：设置 → 无障碍", Toast.LENGTH_LONG).show();
            }
        });

        swEnable.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp().edit().putBoolean(Prefs.KEY_ENABLED, isChecked).apply());

        sbDelay.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvDelay.setText("点击延迟：" + progress + " ms");
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sp().edit().putInt(Prefs.KEY_DELAY, seekBar.getProgress()).apply();
            }
        });

        sbWindow.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvWindow.setText("启动后检测时长：" + (progress + 2) + " 秒");
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sp().edit().putInt(Prefs.KEY_WINDOW, (seekBar.getProgress() + 2) * 1000).apply();
            }
        });

        rgStrategy.setOnCheckedChangeListener((group, checkedId) -> {
            int v = 2;
            if (checkedId == R.id.rbStrategy0) {
                v = 0;
            } else if (checkedId == R.id.rbStrategy1) {
                v = 1;
            }
            sp().edit().putInt(Prefs.KEY_STRATEGY, v).apply();
        });

        rgCorner.setOnCheckedChangeListener((group, checkedId) -> {
            int v = 0;
            if (checkedId == R.id.rbCorner1) {
                v = 1;
            } else if (checkedId == R.id.rbCorner2) {
                v = 2;
            } else if (checkedId == R.id.rbCorner3) {
                v = 3;
            }
            sp().edit().putInt(Prefs.KEY_CORNER, v).apply();
        });

        etWhitelist.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sp().edit().putString(Prefs.KEY_WHITELIST, s.toString().trim()).apply();
            }
        });
    }

    private void refreshStatus() {
        boolean on = isServiceEnabled();
        if (on) {
            tvStatus.setText(R.string.status_on);
            tvStatus.setTextColor(getColor(R.color.ok));
            btnAccessibility.setText("无障碍服务已开启（点此查看）");
        } else {
            tvStatus.setText(R.string.status_off);
            tvStatus.setTextColor(getColor(R.color.warn));
            btnAccessibility.setText(R.string.open_accessibility);
        }
    }

    private boolean isServiceEnabled() {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : list) {
                if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                    continue;
                }
                if (getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 只关心进度变化与松手，省去空实现。 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }
    }
}
