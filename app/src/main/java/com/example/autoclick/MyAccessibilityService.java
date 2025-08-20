package com.example.autoclick;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class MyAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 处理无障碍事件
        //if(event.getEventType())
        {
            clickScreen(500,500);//这里根本点击坐标
            Toast.makeText(getApplicationContext(), "开始点击", Toast.LENGTH_SHORT).show();
        }

    }

    @Override
    public void onInterrupt() {
        // 处理服务中断
    }

    /*功能
     * 1.坐标点击clickScreen()
     * 2.截屏及保存
     * */

    // 模拟点击屏幕500，500的坐标
    public void clickScreen(int x, int y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            Path path = new Path();
            path.moveTo(x, y);
            path.lineTo(x, y);
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 1));
            GestureDescription gesture = builder.build();
            dispatchGesture(gesture, null, null);
            Toast.makeText(getApplicationContext(), "点击成功可能", Toast.LENGTH_SHORT).show();
        } else {
            // 对于低于Android 5.0的版本，可以使用其他方法实现点击功能
        }
    }
    //截屏

}