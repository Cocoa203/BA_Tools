package com.example.autoclick;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ScreenCaptureService extends Service {

    static {
        System.loadLibrary("autoclick");
    }

    // --- 公共常量 ---
    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_DATA = "extra_data";
    public static final String ACTION_FRAME_CAPTURED = "com.example.autoclick.FRAME_CAPTURED";
    public static final String EXTRA_BITMAP = "extra_bitmap";

    // --- 私有常量 ---
    private static final String NOTIFICATION_CHANNEL_ID = "ScreenCapture";
    private static final int NOTIFICATION_ID = 1;
    private static final String HANDLER_THREAD_NAME = "ScreenCapture";
    private static final int CLICK_TIME = 4000;

    // --- 成员变量 ---
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;
    private long lastBroadcastTime = 0;
    private final String TAG = "JavaLog";
    private volatile boolean isSearching = true;

    // 状态机相关
    private enum MatchState { BA_ICON, ENTER_GAME, NOTICE, MAIN_MENU , COFFEE_HOUSE_TASK,
        SCHEDULE_TASK, SOCIAL_TASK, SHOP_TASK, BOUNTY_TASK, SPECIAL_TASK, COMMUTE_TASK,
        COMPETITION_TASK, DAILY_TASK, DONE, IDLE }

    private enum CoffeeState{
        COFFEE_HOUSE ,COFFEE_HOUSE_IN, COFFEE_HOUSE_GET, QUIT_TO_MAIN
    }

    private enum DailyState{
        DM_IN, DM_GET, DM_QUIT_TO_MAIN
    }
    private enum CompetitionState{
        CPT_IN, WORK_SPACE ,CPT_ICON, CPT_GET, CPT_CONTINUE, CPT_CHOOSE, CPT_FORM,
        CPT_ATTACK, CPT_CONFIRM, CPT_QUIT_TO_MAIN
    }
    private enum CommuteState{
        COMMUTE_IN, WORK_SPACE ,COMMUTE_ICON, CM_TRINITY, CM_LEVEL3, MAX_VALUE,
        START_QUICK_FIGHT, QUICK_FIGHT_CONFIRM ,QUICK_FIGHT_FINISH, BT_QUIT_TO_MAIN
    }
    private enum SpecialState{
        SP_IN, WORK_SPACE ,SP_ICON, SP_DEFENSE, SP_LEVEL, MAX_VALUE,
        START_QUICK_FIGHT, QUICK_FIGHT_CONFIRM ,QUICK_FIGHT_FINISH, SP_QUIT_TO_MAIN
    }
    private enum BountyState{
        BOUNTY_IN, WORK_SPACE ,BOUNTY_ICON, BT_HIGHWAY, BT_LEVEL9, MAX_VALUE,
        START_QUICK_FIGHT, QUICK_FIGHT_CONFIRM ,QUICK_FIGHT_FINISH, BT_QUIT_TO_MAIN
    }
    private enum ShopState{
        SHOP_IN, SHOP_SELECT, SHOP_BUY, SHOP_CONFIRM, SHOP_CPT, SHOP_PHYSIC ,SHOP_QUIT_TO_MAIN
    }
    private enum SocialState{
        SC_IN, SC_GROUP, SC_GROUP_CONFIRM, SC_QUIT_TO_MAIN
    }
    private enum ScheduleState{
        SH_IN, SH_LOC_SELECT,SH_ALL,SH_CHOOSE,SH_START,SH_CONFIRM, SH_QUIT_TO_MAIN
    }

    ShopState SHOP_State = ShopState.SHOP_IN;
    SocialState SC_State = SocialState.SC_IN;
    ScheduleState SH_State = ScheduleState.SH_IN;
    CommuteState CM_State = CommuteState.COMMUTE_IN;
    SpecialState SP_State = SpecialState.SP_IN;
    BountyState BT_State = BountyState.BOUNTY_IN;
    DailyState DM_State = DailyState.DM_IN;
    CompetitionState CPT_State = CompetitionState.CPT_IN;
    CoffeeState CH_State = CoffeeState.COFFEE_HOUSE;
    private MatchState state = MatchState.MAIN_MENU;
    List<MatchState> taskSequence = new ArrayList<>();
    private int taskIndex;
    private int chClickCount = 0;
    List<Point> points = new ArrayList<>();
    private int dmClickCount = 0;
    List<Point> CPT_Points = new ArrayList<>();
    private int cptClickCount = 0;
    private int btCountClick = 0;
    List<Point> SHOP_Points = new ArrayList<>();
    private int shopClickCount = 0;
    List<Point> SH_Points = new ArrayList<>();
    private int shClickCount = 0;
    private int btClickCount;
    private int cmClickCount;
    private boolean isSecondBuy = false;

    private Mat currentTemplateMat = new Mat();
    private Mat nextTemplateMat = new Mat();
    private Mat srcMat = new Mat();

    // --- 【新增】变量，用于“记住”授权信息和屏幕方向 ---
    private int projectionResultCode;
    private Intent projectionData;
    private int screenOrientation;

    private long lastTime;


    @Override
    public void onCreate() {
        super.onCreate();
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        handlerThread = new HandlerThread(HANDLER_THREAD_NAME);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        loadStateTemplate();
        taskSequence.add(MatchState.COFFEE_HOUSE_TASK);
        taskSequence.add(MatchState.SCHEDULE_TASK);
        taskSequence.add(MatchState.SOCIAL_TASK);
        taskSequence.add(MatchState.SHOP_TASK);
        taskSequence.add(MatchState.BOUNTY_TASK);
        taskSequence.add(MatchState.SPECIAL_TASK);
        taskSequence.add(MatchState.COMMUTE_TASK);
        taskSequence.add(MatchState.COMPETITION_TASK);
        taskSequence.add(MatchState.DAILY_TASK);
        taskIndex = 0;
        //Core.rotate(nextTemplateMat, nextTemplateMat, Core.ROTATE_180);
        Log.d(TAG, "nextTemplateMat w:" + nextTemplateMat.width() + "h:"+nextTemplateMat.height());

        // 【新增】记录初始屏幕方向
        screenOrientation = getResources().getConfiguration().orientation;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isSearching = true; // 正确重置状态
        createNotificationChannel();
        Notification notification = createNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (intent != null) {
            // --- 【修改】不再直接使用 intent 数据，而是先保存起来 ---
            this.projectionResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            this.projectionData = intent.getParcelableExtra(EXTRA_DATA);

            if (this.projectionData != null) {
                // 调用无参数的 startCapture
                startCapture();
            }
        }
        return START_NOT_STICKY;
    }

    // --- 【新增】监听屏幕旋转的方法 ---
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation != this.screenOrientation) {
            // 如果屏幕方向变了，更新记录的方向
            this.screenOrientation = newConfig.orientation;
            Log.d(TAG, "屏幕方向改变，正在重启捕捉会话...");
            // 在后台线程中重启捕捉
            handler.post(() -> {
                stopCapture();
                startCapture();
            });
        }
    }
    int count;
    // --- 【修改】startCapture 方法签名，不再需要参数 ---
    private void startCapture() {
        // 使用保存的授权信息
        if (this.projectionData == null) {
            return;
        }

        // 每次启动捕捉前，都重新获取最新的屏幕尺寸
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int screenDensity = getResources().getDisplayMetrics().densityDpi;
        Log.d(TAG, "创建捕捉会话，尺寸: " + screenWidth + "x" + screenHeight);

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            Bitmap srcBitmap = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    srcMat = imageToMat(image);
                    executeStateMachine();
                    count ++;
                    Log.d(TAG, "count:" +count);
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastBroadcastTime > 5000) {
                        lastBroadcastTime = currentTime;
                        Bitmap previewBitmap = Bitmap.createBitmap(srcMat.cols(), srcMat.rows(), Bitmap.Config.ARGB_8888);
                        Utils.matToBitmap(srcMat, previewBitmap);
                        broadcastBitmapForPreview(previewBitmap); // broadcastBitmapForPreview 内部会创建副本
                        previewBitmap.recycle();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) {
                    image.close();
                }
                if (srcBitmap != null && !srcBitmap.isRecycled()) {
                    srcBitmap.recycle();
                }
            }
        }, handler);

        // 使用保存的授权信息来获取 MediaProjection
        mediaProjection = mediaProjectionManager.getMediaProjection(this.projectionResultCode, this.projectionData);
        if (mediaProjection != null) {
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() { stopSelf(); }
            }, handler);
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture", screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, handler);
        }
    }

    private void loadTemplate(Mat template, int id) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false; // 禁止自动缩放
        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), id, options);
        if(bitmap != null) {
            Utils.bitmapToMat(bitmap, template);
            bitmap.recycle(); // 转换后及时回收
        }
    }
    private void loadStateTemplate(){
        switch (state) {
            case BA_ICON:
                loadTemplate(currentTemplateMat, R.drawable.ba_icon);
                loadTemplate(nextTemplateMat, R.drawable.enter_game);
                break;
            case ENTER_GAME:
                loadTemplate(currentTemplateMat, R.drawable.enter_game);
                loadTemplate(nextTemplateMat, R.drawable.notice);
                break;
            case NOTICE:
                loadTemplate(currentTemplateMat, R.drawable.notice);
                loadTemplate(nextTemplateMat, R.drawable.main_menu);
                break;
            case MAIN_MENU:
                loadTemplate(currentTemplateMat, R.drawable.main_menu);
                loadTemplate(nextTemplateMat, R.drawable.coffee_house);
                break;
            case COFFEE_HOUSE_TASK:
                loadTemplate(currentTemplateMat, R.drawable.coffee_house);
                loadTemplate(nextTemplateMat, R.drawable.coffee_house_in);
                break;
            case SCHEDULE_TASK:
                loadTemplate(currentTemplateMat, R.drawable.schedule_task);
                loadTemplate(nextTemplateMat, R.drawable.sh_loc_select);
                break;
            case SOCIAL_TASK:
                loadTemplate(currentTemplateMat, R.drawable.social_task);
                loadTemplate(nextTemplateMat, R.drawable.sc_group);
                break;
            case SHOP_TASK:
                loadTemplate(currentTemplateMat, R.drawable.shop_task);
                loadTemplate(nextTemplateMat, R.drawable.shop_select);
                break;
            case BOUNTY_TASK:
                loadTemplate(currentTemplateMat, R.drawable.work_space);
                loadTemplate(nextTemplateMat, R.drawable.bounty_icon);
                break;
            case SPECIAL_TASK:
                loadTemplate(currentTemplateMat, R.drawable.work_space);
                loadTemplate(nextTemplateMat, R.drawable.sp_icon);
                break;
            case COMMUTE_TASK:
                loadTemplate(currentTemplateMat, R.drawable.work_space);
                loadTemplate(nextTemplateMat, R.drawable.commute_icon);
                break;
            case COMPETITION_TASK:
                loadTemplate(currentTemplateMat, R.drawable.work_space);
                loadTemplate(nextTemplateMat, R.drawable.cpt_icon);
                break;
            case DAILY_TASK:
                loadTemplate(currentTemplateMat, R.drawable.dm_icon);
                loadTemplate(nextTemplateMat, R.drawable.dm_get);
                break;
            case DONE:
                break;
            case IDLE:
                break;
        }
    }
    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap tempBitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        tempBitmap.copyPixelsFromBuffer(buffer);
        Bitmap croppedBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, image.getWidth(), image.getHeight());
        if (!tempBitmap.isRecycled()) {
            tempBitmap.recycle();
        }
        return croppedBitmap;
    }
    private Mat imageToMat(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        // 创建一个 Mat 对象，其数据指针直接指向 Image 的 buffer
        // 注意：这里的高度是 image.getHeight()，宽度是 rowStride / pixelStride
        Mat tempMat = new Mat(image.getHeight(), rowStride / pixelStride, CvType.CV_8UC4, buffer);

        // 裁剪掉因内存对齐产生的右侧多余空白部分
        // 创建一个指向 tempMat 左上角，尺寸为 image.getWidth() x image.getHeight() 的新 Mat 头
        Mat mat = new Mat(tempMat, new org.opencv.core.Rect(0, 0, image.getWidth(), image.getHeight()));

        return mat;
    }

    private void broadcastBitmapForPreview(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        Bitmap bitmapCopy = bitmap.copy(bitmap.getConfig(), false);
        Intent intent = new Intent(ACTION_FRAME_CAPTURED);
        intent.putExtra(EXTRA_BITMAP, bitmapCopy);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public int getDayOfWeekAsInt() {
        // 1. 获取当前日期
        LocalDate today = LocalDate.now();

        // 2. 从当前日期中获取 DayOfWeek 枚举实例
        DayOfWeek dayOfWeekEnum = today.getDayOfWeek();

        // 3. DayOfWeek.getValue() 返回的范围是 1 (MONDAY) 到 7 (SUNDAY)
        // 这正好符合您的需求。
        return dayOfWeekEnum.getValue();
    }

    private void threadSleep(int time){
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private boolean isClickReady(){
        return  (System.currentTimeMillis() - lastTime > CLICK_TIME);
    }

    private void skipLikingDegreeUp(Mat srcMat){
        Mat linkingDegreeTemplate = new Mat();
        loadTemplate(linkingDegreeTemplate, R.drawable.liking_degree);
        MatchResult result = findTemplateWithJava(srcMat, linkingDegreeTemplate);
        if(result.found && isClickReady()){
            click(result.point);
            lastTime = System.currentTimeMillis();
        }
    }

    private void executeStateMachine() {

        MatchResult result = findTemplateWithJava(srcMat, currentTemplateMat);
        MatchResult nextResult = findTemplateWithJava(srcMat, nextTemplateMat);
        switch (state) {
            case BA_ICON:
                if (result.found) {
                    clickWithOffset(result.point);
                }
                if (nextResult.found) {
                    state = MatchState.ENTER_GAME;
                    loadStateTemplate();
                    lastTime = System.currentTimeMillis();
                }
                break;
            case ENTER_GAME:
                Point point = new Point(srcMat.width()/2, srcMat.height()/2);
                clickWithOffset(point);
                if (result.found) {

                }
                if (nextResult.found) {
                    state = MatchState.NOTICE;
                    loadStateTemplate();
                }
                break;
            case NOTICE:
                if (result.found) {
                    clickWithOffset(new Point(result.point.x - currentTemplateMat.width()*2,
                            result.point.y - currentTemplateMat.height()));
                }
                if (nextResult.found || (System.currentTimeMillis() - lastTime) > 100000) {
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case MAIN_MENU:
                if (result.found) {
                    if(taskIndex < taskSequence.size()){
                        state = taskSequence.get(taskIndex);
                        loadStateTemplate();
                        taskIndex ++;
                    } else {
                        state = MatchState.DONE;
                        loadStateTemplate();
                    }
                }
                if (nextResult.found) {

                }
                break;
            case COFFEE_HOUSE_TASK:
                if (coffeeTask(result, nextResult)) {
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case SCHEDULE_TASK:
                if(scheduleTask(result, nextResult)){
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case SOCIAL_TASK:
                if(socialTask(result, nextResult)){
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case SHOP_TASK:
                if(shopTask(result, nextResult)){
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case BOUNTY_TASK:
                if(bountyTask(result, nextResult)){
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case SPECIAL_TASK:
                if(specialTask(result, nextResult)){
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case COMMUTE_TASK:
                if(commuteTask(result, nextResult)){
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case COMPETITION_TASK:
                if(competitionTask(result, nextResult)){
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case DAILY_TASK:
                if(dailyTask(result, nextResult)){
                    state = MatchState.MAIN_MENU;
                    loadStateTemplate();
                }
                break;
            case DONE:
                break;
            case IDLE:
                break;

        }
        String allStatesLog = String.format(
                "主状态: %s | 咖啡: %s|日常: %s | 竞技: %s | 通缉: %s | | 特别: %s | | 交流: %s | 商店: %s | 社交: %s | 日程: %s ",
                state,
                CH_State,
                DM_State,
                CPT_State,
                BT_State,
                SP_State,
                CM_State,
                SHOP_State,
                SC_State,
                SH_State
        );
        Log.d(TAG, "--- 当前所有状态 --- " + allStatesLog);
        Log.d(TAG, "相似度: " + result.similarity+"  "+nextResult.similarity);
        Log.d(TAG, "srcMat 尺寸: "+srcMat.width()+"x"+srcMat.height());
        Log.d(TAG, "currentTemplateMat 尺寸: "+currentTemplateMat.width()+"x"+currentTemplateMat.height());
    }

    private boolean coffeeTask(MatchResult result, MatchResult nextResult){
        boolean returnValue = false;
        switch (CH_State){
            case COFFEE_HOUSE:
                if (result.found) {
                    clickWithOffset(result.point);
                }
                if (nextResult.found) {
                    CH_State = CoffeeState.COFFEE_HOUSE_IN;
                    loadTemplate(currentTemplateMat, R.drawable.coffee_house_in);
                    loadTemplate(nextTemplateMat, R.drawable.coffee_house_get);
                    points = createClickPointGrid(srcMat);
                }
                break;
            case COFFEE_HOUSE_IN:
                if(chClickCount < points.size()) {
                    click(points.get(chClickCount));
                    chClickCount++;
                }
                int time = 150;
                for(int i=0; i<9; i++){
                    if(chClickCount < points.size()) {
                        threadSleep(time);
                        click(points.get(chClickCount));
                        chClickCount++;
                    }
                }
                skipLikingDegreeUp(srcMat);
                if (result.found && isClickReady()) {
                    if(chClickCount >= points.size()){
                        result.point.x = srcMat.width() - 1.5 * result.point.x;
                        clickWithOffset(result.point);
                        lastTime = System.currentTimeMillis();
                    }
                }
                if (nextResult.found) {
                    CH_State = CoffeeState.COFFEE_HOUSE_GET;
                    chClickCount = 0;
                    points.clear();
                    loadTemplate(currentTemplateMat, R.drawable.coffee_house_get);
                    loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                }
                break;
            case COFFEE_HOUSE_GET:
                if (result.found && isClickReady()) {
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                    chClickCount ++;
                }
                if (nextResult.found && chClickCount !=0) {
                    CH_State = CoffeeState.QUIT_TO_MAIN;
                    chClickCount = 0;
                    loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                    loadTemplate(nextTemplateMat, R.drawable.main_menu);
                }
                break;
            case QUIT_TO_MAIN:
                if (result.found) {
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CH_State = CoffeeState.COFFEE_HOUSE;
                    chClickCount = 0;
                    returnValue = true;
                }
                break;
        }
        return returnValue;
    }

    private boolean dailyTask(MatchResult result, MatchResult nextResult){
        boolean returnValue = false;
        switch (DM_State){
            case DM_IN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    DM_State = DailyState.DM_GET;
                    loadTemplate(currentTemplateMat, R.drawable.dm_get);
                    loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                }
                break;
            case DM_GET:
                if(result.found){
                    clickWithOffset(result.point);
                    dmClickCount ++;
                    threadSleep(150);
                    result.point.x = result.point.x - currentTemplateMat.width();
                    clickWithOffset(result.point);
                }
                if(nextResult.found && dmClickCount > 4){
                    DM_State = DailyState.DM_QUIT_TO_MAIN;
                    loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                    loadTemplate(nextTemplateMat, R.drawable.main_menu);
                }
                break;
            case DM_QUIT_TO_MAIN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    DM_State = DailyState.DM_IN;
                    dmClickCount = 0;
                    returnValue = true;
                }
                break;
        }
        return returnValue;
    }

    private boolean competitionTask(MatchResult result, MatchResult nextResult){
        boolean returnValue = false;
        switch (CPT_State) {
            case CPT_IN:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    CPT_State = CompetitionState.WORK_SPACE;
                    loadTemplate(currentTemplateMat, R.drawable.work_space);
                    loadTemplate(nextTemplateMat, R.drawable.cpt_icon);
                }
                break;
            case WORK_SPACE:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CPT_State = CompetitionState.CPT_ICON;
                    loadTemplate(currentTemplateMat, R.drawable.cpt_icon);
                    loadTemplate(nextTemplateMat, R.drawable.cpt_get);
                }
                break;
            case CPT_ICON:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    CPT_State = CompetitionState.CPT_GET;
                    double x = nextResult.point.x + nextTemplateMat.width()/2;
                    double y = nextResult.point.y + nextTemplateMat.height()/2;
                    for (int i=0; i< 4; i++) {
                        CPT_Points.add(new Point(x, y));
                        CPT_Points.add(new Point(x, y + 3*nextTemplateMat.height()));
                        CPT_Points.add(new Point(x, 0.95 * srcMat.height()));
                    }
                    cptClickCount = 0;
                    loadTemplate(currentTemplateMat, R.drawable.cpt_get);
                    loadTemplate(nextTemplateMat, R.drawable.cpt_get);
                }
                break;
            case CPT_GET:
                if(result.found){
                }
                if(cptClickCount < CPT_Points.size()){
                    click(CPT_Points.get(cptClickCount));
                    cptClickCount ++;
                }else{
                    CPT_State = CompetitionState.CPT_CHOOSE;
                    loadTemplate(currentTemplateMat, R.drawable.cpt_get);
                    loadTemplate(nextTemplateMat, R.drawable.cpt_form);
                }
                break;
            case CPT_CONTINUE:
                break;
            case CPT_CHOOSE:
                click(new Point( srcMat.width()/2, srcMat.height()/2));
                if(nextResult.found){
                    CPT_State = CompetitionState.CPT_FORM;
                    loadTemplate(currentTemplateMat, R.drawable.cpt_form);
                    loadTemplate(nextTemplateMat, R.drawable.cpt_attack);
                }
                break;
            case CPT_FORM:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CPT_State = CompetitionState.CPT_ATTACK;
                    loadTemplate(currentTemplateMat, R.drawable.cpt_attack);
                    loadTemplate(nextTemplateMat, R.drawable.cpt_comfirm);
                }
                break;
            case CPT_ATTACK:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CPT_State = CompetitionState.CPT_CONFIRM;
                    loadTemplate(currentTemplateMat, R.drawable.cpt_comfirm);
                    loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                }
                break;
            case CPT_CONFIRM:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CPT_State = CompetitionState.CPT_QUIT_TO_MAIN;
                    loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                    loadTemplate(nextTemplateMat, R.drawable.main_menu);
                }
                break;
            case CPT_QUIT_TO_MAIN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CPT_State = CompetitionState.CPT_IN;
                    cptClickCount = 0;
                    returnValue = true;
                }
                break;
        }
        return returnValue;
    }

    private boolean commuteTask(MatchResult result, MatchResult nextResult){
        boolean returnValue = false;
        switch (CM_State) {
            case COMMUTE_IN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CM_State = CommuteState.WORK_SPACE;
                    loadTemplate(currentTemplateMat, R.drawable.work_space);
                    loadTemplate(nextTemplateMat, R.drawable.commute_icon);
                }
                break;
            case WORK_SPACE:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CM_State = CommuteState.COMMUTE_ICON;
                    loadTemplate(currentTemplateMat, R.drawable.commute_icon);
                    switch (getDayOfWeekAsInt() % 3){
                        case 0:
                            loadTemplate(nextTemplateMat, R.drawable.cm_trinity_0);
                            break;
                        case 1:
                            loadTemplate(nextTemplateMat, R.drawable.cm_trinity_1);
                            break;
                        case 2:
                            loadTemplate(nextTemplateMat, R.drawable.cm_trinity_2);
                            break;
                        default:
                            loadTemplate(nextTemplateMat, R.drawable.cm_trinity_1);
                            break;

                    }
                }
                break;
            case COMMUTE_ICON:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    CM_State = CommuteState.CM_TRINITY;
                    switch (getDayOfWeekAsInt() % 3){
                        case 0:
                            loadTemplate(currentTemplateMat, R.drawable.cm_trinity_0);
                            break;
                        case 1:
                            loadTemplate(currentTemplateMat, R.drawable.cm_trinity_1);
                            break;
                        case 2:
                            loadTemplate(currentTemplateMat, R.drawable.cm_trinity_2);
                            break;
                        default:
                            loadTemplate(currentTemplateMat, R.drawable.cm_trinity_1);
                            break;

                    }
                    //loadTemplate(currentTemplateMat, R.drawable.cm_trinity);
                    loadTemplate(nextTemplateMat, R.drawable.cm_level_3);
                }
                break;
            case CM_TRINITY:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    CM_State = CommuteState.CM_LEVEL3;
                    loadTemplate(currentTemplateMat, R.drawable.cm_level_3);
                    loadTemplate(nextTemplateMat, R.drawable.max_value);
                }
                break;
            case CM_LEVEL3:
                if(result.found && isClickReady()){
                    clickWithOffset(new Point(result.point.x + currentTemplateMat.width()*5,
                            result.point.y));
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    CM_State = CommuteState.MAX_VALUE;
                    loadTemplate(currentTemplateMat, R.drawable.max_value);
                    loadTemplate(nextTemplateMat, R.drawable.start_quick_fight);
                }
                break;
            case MAX_VALUE:
                if(result.found){
                    clickWithOffset(result.point);
                }
                cmClickCount ++;
                if(nextResult.found && cmClickCount>2){
                    CM_State = CommuteState.START_QUICK_FIGHT;
                    cmClickCount = 0;
                    loadTemplate(currentTemplateMat, R.drawable.start_quick_fight);
                    loadTemplate(nextTemplateMat, R.drawable.quick_fight_confirm);
                }
                break;
            case START_QUICK_FIGHT:
                if(result.found){
                    result.point.x = result.point.x - currentTemplateMat.width()*0.25;
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CM_State = CommuteState.QUICK_FIGHT_CONFIRM;
                    loadTemplate(currentTemplateMat, R.drawable.quick_fight_confirm);
                    loadTemplate(nextTemplateMat, R.drawable.quick_fight_finish);
                }
                break;
            case QUICK_FIGHT_CONFIRM:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CM_State = CommuteState.QUICK_FIGHT_FINISH;
                    loadTemplate(currentTemplateMat, R.drawable.quick_fight_finish);
                    loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                }
                break;
            case QUICK_FIGHT_FINISH:
                if(result.found){
                    click(new Point(0.95*srcMat.width(), 0.01*srcMat.height()));
                }
                if(nextResult.found){
                    CM_State = CommuteState.BT_QUIT_TO_MAIN;
                    loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                    loadTemplate(nextTemplateMat, R.drawable.main_menu);
                }
                break;
            case BT_QUIT_TO_MAIN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    CM_State = CommuteState.COMMUTE_IN;
                    returnValue = true;
                }
                break;
        }
        return returnValue;
    }

    private boolean specialTask(MatchResult result, MatchResult nextResult){
        boolean returnValue = false;
        switch (SP_State) {
            case SP_IN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SP_State = SpecialState.WORK_SPACE;
                    loadTemplate(currentTemplateMat, R.drawable.work_space);
                    loadTemplate(nextTemplateMat, R.drawable.sp_icon);
                }
                break;
            case WORK_SPACE:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SP_State = SpecialState.SP_ICON;
                    loadTemplate(currentTemplateMat, R.drawable.sp_icon);
                    loadTemplate(nextTemplateMat, R.drawable.sp_defense);
                }
                break;
            case SP_ICON:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    SP_State = SpecialState.SP_DEFENSE;
                    loadTemplate(currentTemplateMat, R.drawable.sp_defense);
                    loadTemplate(nextTemplateMat, R.drawable.sp_level);
                }
                break;
            case SP_DEFENSE:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    SP_State = SpecialState.SP_LEVEL;
                    loadTemplate(currentTemplateMat, R.drawable.sp_level);
                    loadTemplate(nextTemplateMat, R.drawable.start_quick_fight);
                }
                break;
            case SP_LEVEL:
                if(result.found){
                    clickWithOffset(new Point(result.point.x + currentTemplateMat.width()*5,
                            result.point.y));
                }
                if(nextResult.found){
                    SP_State = SpecialState.START_QUICK_FIGHT;
                    loadTemplate(currentTemplateMat, R.drawable.start_quick_fight);
                    loadTemplate(nextTemplateMat, R.drawable.quick_fight_confirm);
                }
                break;
            case MAX_VALUE:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SP_State = SpecialState.START_QUICK_FIGHT;
                    loadTemplate(currentTemplateMat, R.drawable.start_quick_fight);
                    loadTemplate(nextTemplateMat, R.drawable.quick_fight_confirm);
                }
                break;
            case START_QUICK_FIGHT:
                if(result.found){
                    result.point.x = result.point.x - currentTemplateMat.width()*0.25;
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SP_State = SpecialState.QUICK_FIGHT_CONFIRM;
                    loadTemplate(currentTemplateMat, R.drawable.quick_fight_confirm);
                    loadTemplate(nextTemplateMat, R.drawable.quick_fight_finish);
                }
                break;
            case QUICK_FIGHT_CONFIRM:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SP_State = SpecialState.QUICK_FIGHT_FINISH;
                    loadTemplate(currentTemplateMat, R.drawable.quick_fight_finish);
                    loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                }
                break;
            case QUICK_FIGHT_FINISH:
                if(result.found){
                    click(new Point(0.95*srcMat.width(), 0.01*srcMat.height()));
                }
                if(nextResult.found){
                    SP_State = SpecialState.SP_QUIT_TO_MAIN;
                    loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                    loadTemplate(nextTemplateMat, R.drawable.main_menu);
                }
                break;
            case SP_QUIT_TO_MAIN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SP_State = SpecialState.SP_IN;
                    returnValue = true;
                }
                break;
        }
        return returnValue;
    }
    
    private boolean bountyTask(MatchResult result, MatchResult nextResult){
        boolean returnValue = false;
        switch (BT_State) {
            case BOUNTY_IN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    BT_State = BountyState.WORK_SPACE;
                    loadTemplate(currentTemplateMat, R.drawable.work_space);
                    loadTemplate(nextTemplateMat, R.drawable.bounty_icon);
                }
                break;
            case WORK_SPACE:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    BT_State = BountyState.BOUNTY_ICON;
                    loadTemplate(currentTemplateMat, R.drawable.bounty_icon);
                    switch (getDayOfWeekAsInt() % 3){
                        case 0:
                            loadTemplate(nextTemplateMat, R.drawable.bt_highway_0);
                            break;
                        case 1:
                            loadTemplate(nextTemplateMat, R.drawable.bt_highway_1);
                            break;
                        case 2:
                            loadTemplate(nextTemplateMat, R.drawable.bt_highway_2);
                            break;
                        default:
                            loadTemplate(nextTemplateMat, R.drawable.bt_highway_1);
                            break;

                    }
                }
                break;
            case BOUNTY_ICON:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    BT_State = BountyState.BT_HIGHWAY;
                    switch (getDayOfWeekAsInt() % 3){
                        case 0:
                            loadTemplate(currentTemplateMat, R.drawable.bt_highway_0);
                            break;
                        case 1:
                            loadTemplate(currentTemplateMat, R.drawable.bt_highway_1);
                            break;
                        case 2:
                            loadTemplate(currentTemplateMat, R.drawable.bt_highway_2);
                            break;
                        default:
                            loadTemplate(currentTemplateMat, R.drawable.bt_highway_1);
                            break;

                    }
                    loadTemplate(nextTemplateMat, R.drawable.bt_level_9);
                }
                break;
            case BT_HIGHWAY:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    BT_State = BountyState.BT_LEVEL9;
                    loadTemplate(currentTemplateMat, R.drawable.bt_level_9);
                    loadTemplate(nextTemplateMat, R.drawable.max_value);
                }
                break;
            case BT_LEVEL9:
                if(result.found && isClickReady()){
                    clickWithOffset(new Point(result.point.x + currentTemplateMat.width()*5,
                            result.point.y));
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    BT_State = BountyState.MAX_VALUE;
                    loadTemplate(currentTemplateMat, R.drawable.max_value);
                    loadTemplate(nextTemplateMat, R.drawable.start_quick_fight);
                }
                break;
            case MAX_VALUE:
                if(result.found){
                    clickWithOffset(result.point);
                }
                btClickCount ++;
                if(nextResult.found && btClickCount > 2){
                    BT_State = BountyState.START_QUICK_FIGHT;
                    btClickCount = 0;
                    loadTemplate(currentTemplateMat, R.drawable.start_quick_fight);
                    loadTemplate(nextTemplateMat, R.drawable.quick_fight_confirm);
                }
                break;
            case START_QUICK_FIGHT:
                if(result.found){
                    result.point.x = result.point.x - currentTemplateMat.width()*0.25;
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    BT_State = BountyState.QUICK_FIGHT_CONFIRM;
                    loadTemplate(currentTemplateMat, R.drawable.quick_fight_confirm);
                    loadTemplate(nextTemplateMat, R.drawable.quick_fight_finish);
                }
                break;
            case QUICK_FIGHT_CONFIRM:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    BT_State = BountyState.QUICK_FIGHT_FINISH;
                    loadTemplate(currentTemplateMat, R.drawable.quick_fight_finish);
                    loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                }
                break;
            case QUICK_FIGHT_FINISH:
                if(result.found){
                    click(new Point(0.95*srcMat.width(), 0.01*srcMat.height()));
                }
                if(nextResult.found){
                    BT_State = BountyState.BT_QUIT_TO_MAIN;
                    loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                    loadTemplate(nextTemplateMat, R.drawable.main_menu);
                }
                break;
            case BT_QUIT_TO_MAIN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    BT_State = BountyState.BOUNTY_IN;
                    returnValue = true;
                }
                break;
        }
        return returnValue;
    }

    private boolean shopTask(MatchResult result, MatchResult nextResult){
        boolean returnValue = false;
        switch (SHOP_State) {
            case SHOP_IN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SHOP_State = ShopState.SHOP_SELECT;
                    double x = nextResult.point.x;
                    double y = nextResult.point.y;
                    SHOP_Points.add(new Point(x, y));
                    SHOP_Points.add(new Point(x+nextTemplateMat.width(), y));
                    SHOP_Points.add(new Point(x+2*nextTemplateMat.width(), y));
                    SHOP_Points.add(new Point(x+3*nextTemplateMat.width(), y));
                    loadTemplate(currentTemplateMat, R.drawable.shop_select);
                    loadTemplate(nextTemplateMat, R.drawable.shop_buy);
                }
                break;
            case SHOP_SELECT:
                if(result.found){
                    if(shopClickCount < SHOP_Points.size()) {
                        clickWithOffset(SHOP_Points.get(shopClickCount));
                        shopClickCount ++;
                    }
                }
                if(nextResult.found){
                    if(shopClickCount >= SHOP_Points.size()){
                        SHOP_State = ShopState.SHOP_BUY;
                        loadTemplate(currentTemplateMat, R.drawable.shop_buy);
                        loadTemplate(nextTemplateMat, R.drawable.shop_confirm);
                        shopClickCount = 0;
                    }
                }
                break;
            case SHOP_BUY:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    SHOP_State = ShopState.SHOP_CONFIRM;
                    loadTemplate(currentTemplateMat, R.drawable.shop_confirm);
                    if(isSecondBuy) {
                        loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                    } else{
                        loadTemplate(nextTemplateMat, R.drawable.shop_cpt);
                    }
                }
                break;
            case SHOP_CONFIRM:
                if(result.found){
                    clickWithOffset(result.point);
                }
                shopClickCount ++;
                if(!isSecondBuy)
                    click(new Point(srcMat.width()/2, srcMat.height()*0.9));
                if(nextResult.found && shopClickCount>= 4){
                    shopClickCount = 0;
                    if(isSecondBuy) {
                        SHOP_State = ShopState.SHOP_QUIT_TO_MAIN;
                        loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                        loadTemplate(nextTemplateMat, R.drawable.main_menu);
                    }else {
                        SHOP_State = ShopState.SHOP_CPT;
                        loadTemplate(currentTemplateMat, R.drawable.shop_cpt);
                        loadTemplate(nextTemplateMat, R.drawable.shop_physic);
                    }
                }
                break;
            case SHOP_CPT:
                if(result.found){
                    clickWithOffset(result.point);
                    isSecondBuy = true;
                }
                if(nextResult.found){
                    SHOP_State = ShopState.SHOP_PHYSIC;
                    loadTemplate(currentTemplateMat, R.drawable.shop_physic);
                    loadTemplate(nextTemplateMat, R.drawable.shop_buy);
                }
                break;
            case SHOP_PHYSIC:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    threadSleep(150);
                    result.point.x = result.point.x + currentTemplateMat.width()*3;
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    SHOP_State = ShopState.SHOP_BUY;
                    loadTemplate(currentTemplateMat, R.drawable.shop_buy);
                    loadTemplate(nextTemplateMat, R.drawable.shop_confirm);
                }
                break;
            case SHOP_QUIT_TO_MAIN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SHOP_State = ShopState.SHOP_IN;
                    SHOP_Points.clear();
                    shopClickCount = 0;
                    isSecondBuy = false;
                    returnValue = true;
                }
                break;
        }

        return returnValue;
    }

    private boolean socialTask(MatchResult result, MatchResult nextResult){
        boolean returnValue = false;
        switch (SC_State){
            case SC_IN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SC_State = SocialState.SC_GROUP;
                    loadTemplate(currentTemplateMat, R.drawable.sc_group);
                    loadTemplate(nextTemplateMat, R.drawable.sc_group_confirm);
                }
                break;
            case SC_GROUP:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SC_State = SocialState.SC_GROUP_CONFIRM;
                    loadTemplate(currentTemplateMat, R.drawable.sc_group_confirm);
                    loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                }
                break;
            case SC_GROUP_CONFIRM:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SC_State = SocialState.SC_QUIT_TO_MAIN;
                    loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                    loadTemplate(nextTemplateMat, R.drawable.main_menu);
                }
                break;
            case SC_QUIT_TO_MAIN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SC_State = SocialState.SC_IN;
                    returnValue = true;
                }
                break;
        }
        return returnValue;
    }

    private boolean scheduleTask(MatchResult result, MatchResult nextResult) {
        boolean returnValue = false;
        switch (SH_State){
            case SH_IN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SH_State = ScheduleState.SH_LOC_SELECT;
                    loadTemplate(currentTemplateMat, R.drawable.sh_loc_select);
                    loadTemplate(nextTemplateMat, R.drawable.sh_all);
                }
                break;
            case SH_LOC_SELECT:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SH_State = ScheduleState.SH_ALL;
                    loadTemplate(currentTemplateMat, R.drawable.sh_all);
                    loadTemplate(nextTemplateMat, R.drawable.sh_select);
                }
                break;
            case SH_ALL:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    SH_State = ScheduleState.SH_CHOOSE;
                    loadTemplate(currentTemplateMat, R.drawable.sh_select);
                    loadTemplate(nextTemplateMat, R.drawable.sh_start);
                    double m = srcMat.width()/2;
                    double h = srcMat.height();
                    shClickCount = 0;
                    SH_Points.add(new Point(m-0.375*h, 0.375*h));
                    SH_Points.add(new Point(m, 0.375*h));
                    SH_Points.add(new Point(m+0.375*h, 0.375*h));
                    SH_Points.add(new Point(m-0.375*h, 0.56*h));
                    SH_Points.add(new Point(m, 0.56*h));
                    SH_Points.add(new Point(m+0.375*h, 0.56*h));
                    SH_Points.add(new Point(m-0.375*h, 0.75*h));
                    Log.d(TAG, "SH_Points.size():" + SH_Points.size());
                }
                break;
            case SH_CHOOSE:
                if(result.found && isClickReady()){
                    if (shClickCount < SH_Points.size())
                        clickWithOffset(SH_Points.get(shClickCount));
                        lastTime = System.currentTimeMillis();
                        Log.d(TAG, "shClickCount:" + shClickCount);
                }
                if(nextResult.found){
                    if(shClickCount < SH_Points.size()) {
                        SH_State = ScheduleState.SH_START;
                        loadTemplate(currentTemplateMat, R.drawable.sh_start);
                        loadTemplate(nextTemplateMat, R.drawable.sh_confirm);
                    } else {
                        SH_State = ScheduleState.SH_QUIT_TO_MAIN;
                        loadTemplate(currentTemplateMat, R.drawable.quit_to_main);
                        loadTemplate(nextTemplateMat, R.drawable.main_menu);
                    }
                }
                break;
            case SH_START:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                skipLikingDegreeUp(srcMat);
                if(nextResult.found){
                    SH_State = ScheduleState.SH_CONFIRM;
                    loadTemplate(currentTemplateMat, R.drawable.sh_confirm);
                    loadTemplate(nextTemplateMat, R.drawable.sh_select);

                }
                break;
            case SH_CONFIRM:
                if(result.found && isClickReady()){
                    clickWithOffset(result.point);
                    lastTime = System.currentTimeMillis();
                }
                if(nextResult.found){
                    SH_State = ScheduleState.SH_CHOOSE;
                    shClickCount ++;
                    loadTemplate(currentTemplateMat, R.drawable.sh_select);
                    if(shClickCount < SH_Points.size()){
                        loadTemplate(nextTemplateMat, R.drawable.sh_start);
                    } else {
                        loadTemplate(nextTemplateMat, R.drawable.quit_to_main);
                    }
                }
                break;
            case SH_QUIT_TO_MAIN:
                if(result.found){
                    clickWithOffset(result.point);
                }
                if(nextResult.found){
                    SH_State = ScheduleState.SH_IN;
                    shClickCount = 0;
                    SH_Points.clear();
                    returnValue = true;
                }
                break;
        }
        return returnValue;
    }

    private List<Point> createClickPointGrid(Mat srcMat) {
        // --- 参数定义 ---
        final int gridWidthPixels = 1800;   // 网格覆盖的宽度（1000像素）
        final int gridHeightPixels = 1000;  // 网格覆盖的高度（1800像素）
        final int spacingX = 100;            // 每个点之间的间隔（100像素）
        final int spacingY = 200;

        // 创建一个列表来存储最终的坐标点
        List<Point> points = new ArrayList<>();

        // 1. 计算中心点坐标
        int centerX = srcMat.width() / 2;
        int centerY = srcMat.height() / 2;

        // 2. 根据区域尺寸和间隔，计算出X轴和Y轴上分别需要多少个点
        // 加1是为了确保包含起始点和结束点
        int numPointsX = gridWidthPixels / spacingX + 1;
        int numPointsY = gridHeightPixels / spacingY + 1;

        // 3. 计算网格的左上角起始坐标，以确保整体居中
        // 起始点 = 中心点 - (总尺寸的一半)
        int startX = centerX - (gridWidthPixels / 2);
        int startY = centerY - (gridHeightPixels / 2);

        Log.d(TAG, "正在生成 " + numPointsX + "x" + numPointsY + " 的网格...");
        Log.d(TAG, "中心点: (" + centerX + ", " + centerY + "), 网格左上角: (" + startX + ", " + startY + ")");

        // 4. 使用嵌套循环，根据计算出的点的数量来生成网格
        for (int j = 0; j < numPointsY; j++) {
            for (int i = 0; i < numPointsX; i++) {
                // 5. 计算每个点的最终像素坐标
                // 最终坐标 = 起始点坐标 + (点的索引 * 间隔)
                double pointX = startX + (double) i * spacingX;
                double pointY = startY + (double) j * spacingY;

                points.add(new Point(pointX, pointY));
            }
        }

        Log.d(TAG, "成功生成网格点，总数: " + points.size());
        if (!points.isEmpty()) {
            Log.d(TAG, "第一个点坐标: " + points.get(0).toString());
            Log.d(TAG, "最后一个点坐标: " + points.get(points.size() - 1).toString());
        }

        return points;
    }
    private void clickWithOffset(Point point) {
        int clickX = (int) (point.x + currentTemplateMat.cols() / 2);
        int clickY = (int) (point.y + currentTemplateMat.rows() / 2);
        Log.d(TAG, "点击:"+"("+clickX+","+clickY+")");
        AutoClickAccessibilityService.performClick(clickX, clickY);
    }
    private void click(Point point){
        AutoClickAccessibilityService.performClick((int)point.x, (int)point.y);
        Log.d(TAG, "点击:"+"("+point.x+","+point.y+")");
    }

    private MatchResult findTemplateWithJava(Mat srcMat, Mat templateMat) {
        MatchResult result = new MatchResult();
        result.found = false; // 默认是没找到

        Mat srcGray = new Mat();
        Mat templateGray = new Mat();
        Mat resultMat = new Mat();
        try {
            if (templateMat == null || templateMat.empty() || srcMat == null || srcMat.empty() || templateMat.rows() > srcMat.rows() || templateMat.cols() > srcMat.cols()) {
                return result;
            }

            Imgproc.cvtColor(srcMat, srcGray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.matchTemplate(srcGray, templateGray, resultMat, Imgproc.TM_CCOEFF_NORMED);
            //Imgproc.matchTemplate(srcMat, templateMat, resultMat, Imgproc.TM_CCOEFF_NORMED);

            Core.MinMaxLocResult mmr = Core.minMaxLoc(resultMat);
            result.point = mmr.maxLoc;
            result.similarity = mmr.maxVal;
            if (mmr.maxVal >= 0.75f) { // 80% 相似度阈值
                result.found = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            srcGray.release();
            templateGray.release();
            resultMat.release();
        }
        return result;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 彻底停止捕捉并释放所有资源
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        stopCapture();
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }

    private void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Screen Capture Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("屏幕采集中")
                .setContentText("应用正在获取您的屏幕内容。")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static class MatchResult {
        public boolean found;
        public Point point;
        public double similarity;
    }

    public native String stringFromJNI();
}