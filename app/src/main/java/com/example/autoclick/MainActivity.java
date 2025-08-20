package com.example.autoclick;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.BroadcastReceiver;


import com.example.autoclick.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    // Used to load the 'autoclick' library on application startup.
    static {
        System.loadLibrary("autoclick");
    }
    private ActivityMainBinding binding;
    // private MyAccessibilityService myAccessibilityService;
    private TextView tv;
    private int i = 0;

    private Button btnStartCapture;
    private Button btnStopCapture;
    private Button btnEnableAccessibility;
    private ImageView capturedImageView; // 新增：显示画面的 ImageView
    private BroadcastReceiver frameReceiver; // 新增：接收广播的接收器
    private MediaProjectionManager mediaProjectionManager;
    private ActivityResultLauncher<Intent> screenCaptureLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        //myAccessibilityService = new MyAccessibilityService();
        // Example of a call to a native method
        //tv = binding.sampleText;
        //tv.setText(stringFromJNI());

        btnEnableAccessibility = findViewById(R.id.btn_enable_accessibility);
        btnEnableAccessibility.setOnClickListener(this);
        //button.callOnClick();
        btnStartCapture = binding.btnStartCapture;
        btnStopCapture = binding.btnStopCapture;
        btnStopCapture.setOnClickListener(this);
        btnStartCapture.setOnClickListener(this);
        // 初始化屏幕捕捉服务启动器
        initScreenCaptureLauncher();
        capturedImageView = binding.imageViewCaptured;

        // 初始化广播接收器
        setupFrameReceiver();


    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注册广播，用于接收预览图
        LocalBroadcastManager.getInstance(this).registerReceiver(
                frameReceiver, new IntentFilter(ScreenCaptureService.ACTION_FRAME_CAPTURED)
        );
        // 每次返回App时，更新无障碍服务按钮的状态
        updateAccessibilityButtonState();
    }
    @Override
    protected void onPause() {
        super.onPause();
        // 注销广播接收器，防止内存泄漏
        LocalBroadcastManager.getInstance(this).unregisterReceiver(frameReceiver);
    }
    /**
     * 设置广播接收器
     */
    private void setupFrameReceiver() {
        frameReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ScreenCaptureService.ACTION_FRAME_CAPTURED.equals(intent.getAction())) {
                    // 从 Intent 中取出 Bitmap 数据
                    Bitmap bitmap = intent.getParcelableExtra(ScreenCaptureService.EXTRA_BITMAP);
                    if (bitmap != null) {
                        // 在 ImageView 上显示图片
                        capturedImageView.setImageBitmap(bitmap);
                    }
                }
            }
        };
    }
    /**
     * 初始化屏幕捕捉权限请求的启动器
     */
    private void initScreenCaptureLauncher() {
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Toast.makeText(this, "权限已授予，正在启动服务...", Toast.LENGTH_SHORT).show();
                        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                        serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.getResultCode());
                        serviceIntent.putExtra(ScreenCaptureService.EXTRA_DATA, result.getData());
                        ContextCompat.startForegroundService(this, serviceIntent);
                    } else {
                        Toast.makeText(this, "您拒绝了屏幕捕捉权限", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * 发起屏幕捕捉权限请求
     */
    private void startScreenCapturePermissionRequest() {
        Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
        screenCaptureLauncher.launch(captureIntent);
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "请在列表中找到您的应用，并开启无障碍服务", Toast.LENGTH_LONG).show();
    }

    private boolean isAccessibilityServiceEnabled() {
        final String service = getPackageName() + "/" + AutoClickAccessibilityService.class.getCanonicalName();
        // ... (此方法的内部实现与之前版本相同)
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) { e.printStackTrace(); }
        TextUtils.SimpleStringSplitter mStringColonSplitter = new TextUtils.SimpleStringSplitter(':');
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue);
                while (mStringColonSplitter.hasNext()) {
                    String accessibilityService = mStringColonSplitter.next();
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void updateAccessibilityButtonState() {
        if (isAccessibilityServiceEnabled()) {
            btnEnableAccessibility.setText("无障碍服务已开启 (✓)");
            btnEnableAccessibility.setEnabled(false);
        } else {
            btnEnableAccessibility.setText("第一步：开启无障碍服务");
            btnEnableAccessibility.setEnabled(true);
        }
    }

    /**
     * A native method that is implemented by the 'autoclick' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();

    @Override
    public void onClick(View view) {
        if(view.getId() == R.id.btn_enable_accessibility){
            //tv.sendAccessibilityEvent(555);
            openAccessibilitySettings();
        }

        else if(view.getId() == R.id.btn_start_capture){
            // 步骤1: 检查无障碍服务（“手指”）是否就绪
            if (!isAccessibilityServiceEnabled()) {
                // 如果未就绪，提示用户先开启
                Toast.makeText(this, "请先点击上方按钮，开启无障碍服务", Toast.LENGTH_SHORT).show();
                openAccessibilitySettings();
                return; // 阻止后续操作
            }

            // 步骤2: 如果无障碍服务已就绪，则请求屏幕捕捉权限（激活“眼睛”）
            startScreenCapturePermissionRequest();
        }
        else if(view.getId() == R.id.btn_stop_capture){
            // 创建一个指向 ScreenCaptureService 的 Intent
            Intent stopIntent = new Intent(this, ScreenCaptureService.class);
            // 调用 stopService 来停止服务
            stopService(stopIntent);
            Toast.makeText(this, "已发送停止服务指令", Toast.LENGTH_SHORT).show();
        }
    }

}