//package com.example.autoclick;
//
//import android.accessibilityservice.AccessibilityService;
//import android.accessibilityservice.GestureDescription;
//import android.graphics.Path;
//import android.os.Build;
//import android.view.accessibility.AccessibilityEvent;
//
//public class AutoClickAccessibilityService extends AccessibilityService {
//
//    // 用于从外部访问服务实例的静态变量
//    private static AutoClickAccessibilityService instance;
//
//    @Override
//    public void onAccessibilityEvent(AccessibilityEvent event) {
//        // 我们不需要处理接收到的事件，只负责执行点击
//    }
//
//    @Override
//    public void onInterrupt() {
//        // 服务中断时调用
//    }
//
//    @Override
//    protected void onServiceConnected() {
//        super.onServiceConnected();
//        // 当服务连接成功后，将实例保存到静态变量中
//        instance = this;
//    }
//
//    @Override
//    public boolean onUnbind(android.content.Intent intent) {
//        // 当服务断开时，清空静态变量
//        instance = null;
//        return super.onUnbind(intent);
//    }
//
//    /**
//     * 外部调用的静态方法，用于执行点击操作
//     * @param x 点击的 x 坐标
//     * @param y 点击的 y 坐标
//     */
//    public static void performClick(int x, int y) {
//        if (instance == null) {
//            return; // 服务未连接，无法执行点击
//        }
//
//        Path path = new Path();
//        path.moveTo(x, y); // 移动到目标坐标
//
//        // 创建一个手势描述
//        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
//        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100)); // 持续100毫秒
//
//        // 发送手势，让系统执行点击
//        instance.dispatchGesture(gestureBuilder.build(), null, null);
//    }
//}
package com.example.autoclick;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.atomic.AtomicBoolean; // 引入 AtomicBoolean

public class AutoClickAccessibilityService extends AccessibilityService {

    private static final String TAG = "AutoClickService";
    private static AutoClickAccessibilityService instance;

    // --- 【新增】使用线程安全的 AtomicBoolean 来跟踪手势是否正在进行中 ---
    private static final AtomicBoolean isGestureInProgress = new AtomicBoolean(false);

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        isGestureInProgress.set(false); // 确保服务连接时状态是空闲的
        Log.d(TAG, "无障碍服务已连接");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        isGestureInProgress.set(false);
        Log.d(TAG, "无障碍服务已断开");
        return super.onUnbind(intent);
    }

    /**
     * --- 【修改】核心的点击方法，增加了状态检查和回调 ---
     * @param x 点击的 x 坐标
     * @param y 点击的 y 坐标
     */
    public static void performClick(int x, int y) {
        // 如果服务未连接，或者上一个手势还在进行中，则直接返回，不提交新请求
//        if (instance == null || isGestureInProgress.get()) {
//            if (isGestureInProgress.get()) {
//                Log.w(TAG, "手势正在进行中，本次点击请求被忽略");
//            }
//            return;
//        }

        // 准备手势
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));

        // 将繁忙状态设为 true
        isGestureInProgress.set(true);
        Log.d(TAG, "发送点击指令，坐标: (" + x + ", " + y + ")，服务进入繁忙状态");

        // 发送手势，并附带一个回调
        instance.dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                // 手势完成，将繁忙状态设为 false
                isGestureInProgress.set(false);
                Log.d(TAG, "手势已完成，服务恢复空闲状态");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                // 手势被取消，也将繁忙状态设为 false
                isGestureInProgress.set(false);
                Log.e(TAG, "手势被取消，服务恢复空闲状态");
            }
        }, null); // Handler 可以为 null，回调会在主线程执行
    }

    /**
     * --- 【新增】公开的静态方法，用于查询服务是否空闲 ---
     * @return 如果没有正在执行的手势，返回 true
     */
    public static boolean isIdle() {
        return !isGestureInProgress.get();
    }
}