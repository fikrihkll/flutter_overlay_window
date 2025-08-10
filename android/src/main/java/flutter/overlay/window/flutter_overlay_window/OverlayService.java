package flutter.overlay.window.flutter_overlay_window;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    private static OverlayService instance;
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");
        if (windowManager != null) {
            windowManager.removeView(flutterView);
            windowManager = null;
            flutterView.detachFromFlutterEngine();
            flutterView = null;
        }
        isRunning = false;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
        instance = null;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.e("OverlayService", "onStartCommand: Intent is null!");
            return START_STICKY;
        }

        mResources = getApplicationContext().getResources();
        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        if (isCloseWindow) {
            if (windowManager != null) {
                windowManager.removeView(flutterView);
                windowManager = null;
                flutterView.detachFromFlutterEngine();
                stopSelf();
            }
            isRunning = false;
            return START_STICKY;
        }
        if (windowManager != null) {
            windowManager.removeView(flutterView);
            windowManager = null;
            flutterView.detachFromFlutterEngine();
            stopSelf();
        }
        isRunning = true;
        Log.d("onStartCommand", "Service started");
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG));
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterChannel.setMethodCallHandler((call, result) -> {
            if (call.method.equals("updateFlag")) {
                String flag = call.argument("flag").toString();
                updateOverlayFlag(result, flag);
            } else if (call.method.equals("updateOverlayPosition")) {
                int x = call.<Integer>argument("x");
                int y = call.<Integer>argument("y");
                moveOverlay(x, y, result);
            } else if (call.method.equals("resizeOverlay")) {
                int width = call.argument("width");
                int height = call.argument("height");
                boolean enableDrag = call.argument("enableDrag");
                resizeOverlay(width, height, enableDrag, result);
            }
        });
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.messenger.send(message);
        });
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Get accurate screen dimensions
        szWindow = getCurrentScreenSize();
        
        // Calculate initial position based on gravity and screen dimensions
        int[] initialPosition = calculateInitialPosition(startX, startY);
        int dx = initialPosition[0];
        int dy = initialPosition[1];
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowSetup.width == -1999 ? -1 : dpToPx(WindowSetup.width),
                WindowSetup.height != -1999 ? dpToPx(WindowSetup.height) : screenHeight(),
                0,
                -statusBarHeightPx(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }
        params.gravity = WindowSetup.gravity;
        flutterView.setOnTouchListener(this);
        windowManager.addView(flutterView, params);
        
        // Wait for the view to be laid out before positioning
        flutterView.post(() -> {
            moveOverlay(dx, dy, null);
        });
        
        return START_STICKY;
    }

    /**
     * Calculate initial position based on gravity and screen dimensions
     */
    private int[] calculateInitialPosition(int startX, int startY) {
        int dx, dy;
        
        if (startX == OverlayConstants.DEFAULT_XY) {
            // Calculate X based on gravity
            switch (WindowSetup.gravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                case Gravity.LEFT:
                    dx = 0;
                    break;
                case Gravity.RIGHT:
                    // We'll calculate this after the view is laid out
                    dx = 0;
                    break;
                case Gravity.CENTER_HORIZONTAL:
                default:
                    dx = 0;
                    break;
            }
        } else {
            dx = dpToPx(startX);
        }
        
        if (startY == OverlayConstants.DEFAULT_XY) {
            // Calculate Y based on gravity
            switch (WindowSetup.gravity & Gravity.VERTICAL_GRAVITY_MASK) {
                case Gravity.TOP:
                    dy = -statusBarHeightPx();
                    break;
                case Gravity.BOTTOM:
                    dy = screenHeight() - navigationBarHeightPx();
                    break;
                case Gravity.CENTER_VERTICAL:
                default:
                    dy = -statusBarHeightPx();
                    break;
            }
        } else {
            dy = dpToPx(startY);
        }
        
        Log.d("OverlayService", String.format("Initial position: dx=%d, dy=%d, gravity=0x%x, screenSize=%dx%d", 
                dx, dy, WindowSetup.gravity, szWindow.x, szWindow.y));
        
        return new int[]{dx, dy};
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return inPortrait() ? dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                : dm.heightPixels + statusBarHeightPx();
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

            if (statusBarHeightId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
            } else {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }

        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

            if (navBarHeightId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
            } else {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }

        return mNavigationBarHeight;
    }

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1;
            }
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            
            // Handle width parameter correctly
            if (width == -1999 || width == -1) {
                params.width = -1; // MATCH_PARENT
            } else {
                params.width = dpToPx(width);
            }
            
            // Handle height parameter correctly
            if (height == -1999 || height == -1) {
                params.height = -1; // MATCH_PARENT
            } else {
                params.height = dpToPx(height);
            }
            
            WindowSetup.enableDrag = enableDrag;
            
            // Ensure the overlay stays within bounds after resize
            if (params.width > 0 && params.height > 0) {
                int[] boundedPosition = ensureBounds(params.x, params.y, params);
                params.x = boundedPosition[0];
                params.y = boundedPosition[1];
            }
            
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            
            // Convert coordinates to pixels if they're in dp
            int targetX = (x == -1999 || x == -1) ? params.x : dpToPx(x);
            int targetY = dpToPx(y);
            
            // Apply gravity-based positioning
            targetX = applyGravityPositioning(targetX, targetY, params);
            
            // Ensure the overlay stays within screen bounds
            int[] boundedPosition = ensureBounds(targetX, targetY, params);
            
            params.x = boundedPosition[0];
            params.y = boundedPosition[1];
            
            windowManager.updateViewLayout(flutterView, params);
            if (result != null)
                result.success(true);
        } else {
            if (result != null)
                result.success(false);
        }
    }

    /**
     * Apply gravity-based positioning adjustments
     */
    private int applyGravityPositioning(int x, int y, WindowManager.LayoutParams params) {
        int adjustedX = x;
        
        // Handle horizontal gravity
        switch (WindowSetup.gravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.LEFT:
                adjustedX = 0;
                break;
            case Gravity.RIGHT:
                if (flutterView != null && flutterView.getWidth() > 0) {
                    adjustedX = szWindow.x - flutterView.getWidth();
                }
                break;
            case Gravity.CENTER_HORIZONTAL:
                if (flutterView != null && flutterView.getWidth() > 0) {
                    adjustedX = (szWindow.x - flutterView.getWidth()) / 2;
                }
                break;
        }
        
        Log.d("OverlayService", String.format("Gravity positioning: originalX=%d, adjustedX=%d, gravity=0x%x, viewWidth=%d, screenWidth=%d", 
                x, adjustedX, WindowSetup.gravity, 
                (flutterView != null ? flutterView.getWidth() : -1), szWindow.x));
        
        return adjustedX;
    }

    /**
     * Ensure the overlay stays within screen bounds
     */
    private int[] ensureBounds(int x, int y, WindowManager.LayoutParams params) {
        int boundedX = x;
        int boundedY = y;
        
        if (flutterView != null && flutterView.getWidth() > 0 && flutterView.getHeight() > 0) {
            // Ensure X position keeps overlay within screen bounds
            if (boundedX < 0) {
                boundedX = 0;
            } else if (boundedX + flutterView.getWidth() > szWindow.x) {
                boundedX = szWindow.x - flutterView.getWidth();
            }
            
            // Ensure Y position keeps overlay within screen bounds
            if (boundedY < -statusBarHeightPx()) {
                boundedY = -statusBarHeightPx();
            } else if (boundedY + flutterView.getHeight() > szWindow.y) {
                boundedY = szWindow.y - flutterView.getHeight();
            }
        }
        
        return new int[]{boundedX, boundedY};
    }

    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            Map<String, Double> position = new HashMap<>();
            position.put("x", instance.pxToDp(params.x));
            position.put("y", instance.pxToDp(params.y));
            return position;
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        if (instance != null && instance.flutterView != null) {
            if (instance.windowManager != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                
                // Convert coordinates to pixels if they're in dp
                int targetX = (x == -1999 || x == -1) ? params.x : instance.dpToPx(x);
                int targetY = instance.dpToPx(y);
                
                // Apply gravity-based positioning
                targetX = instance.applyGravityPositioning(targetX, targetY, params);
                
                // Ensure the overlay stays within screen bounds
                int[] boundedPosition = instance.ensureBounds(targetX, targetY, params);
                
                params.x = boundedPosition[0];
                params.y = boundedPosition[1];
                
                instance.windowManager.updateViewLayout(instance.flutterView, params);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public void onCreate() {
        // Get the cached FlutterEngine
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);

        if (flutterEngine == null) {
            // Handle the error if engine is not found
            Log.e("OverlayService", "Flutter engine not found, hence creating new flutter engine");
            FlutterEngineGroup engineGroup = new FlutterEngineGroup(this);
            DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    "overlayMain"); // "overlayMain" is custom entry point

            flutterEngine = engineGroup.createAndRunEngine(this, entryPoint);

            // Cache the created FlutterEngine for future use
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, flutterEngine);
        }

        // Create the MethodChannel with the properly initialized FlutterEngine
        if (flutterEngine != null) {
            flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
            overlayMessageChannel = new BasicMessageChannel(flutterEngine.getDartExecutor(),
                    OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        }

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
        int pendingFlags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags = PendingIntent.FLAG_IMMUTABLE;
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .build();
        startForeground(OverlayConstants.NOTIFICATION_ID, notification);
        instance = this;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType,
                getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
    }

    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    /**
     * Get current screen dimensions in pixels
     */
    private Point getCurrentScreenSize() {
        Point screenSize = new Point();
        if (windowManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                windowManager.getDefaultDisplay().getRealSize(screenSize);
            } else {
                DisplayMetrics displaymetrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(displaymetrics);
                screenSize.set(displaymetrics.widthPixels, displaymetrics.heightPixels);
            }
        }
        return screenSize;
    }

    /**
     * Update screen dimensions (useful for orientation changes)
     */
    private void updateScreenDimensions() {
        szWindow = getCurrentScreenSize();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        // Update screen dimensions when configuration changes
        updateScreenDimensions();
        
        // Reposition overlay to stay within new screen bounds
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            int[] boundedPosition = ensureBounds(params.x, params.y, params);
            
            // Only update if position actually changed
            if (params.x != boundedPosition[0] || params.y != boundedPosition[1]) {
                params.x = boundedPosition[0];
                params.y = boundedPosition[1];
                windowManager.updateViewLayout(flutterView, params);
            }
        }
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager != null && WindowSetup.enableDrag) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;
                    if (!dragging && dx * dx + dy * dy < 25) {
                        return false;
                    }
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    boolean invertX = WindowSetup.gravity == (Gravity.TOP | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.CENTER | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    boolean invertY = WindowSetup.gravity == (Gravity.BOTTOM | Gravity.LEFT)
                            || WindowSetup.gravity == Gravity.BOTTOM
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    int xx = params.x + ((int) dx * (invertX ? -1 : 1));
                    int yy = params.y + ((int) dy * (invertY ? -1 : 1));
                    params.x = xx;
                    params.y = yy;
                    if (windowManager != null) {
                        windowManager.updateViewLayout(flutterView, params);
                    }
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastYPosition = params.y;
                    if (!WindowSetup.positionGravity.equals("none")) {
                        if (windowManager == null)
                            return false;
                        windowManager.updateViewLayout(flutterView, params);
                        mTrayTimerTask = new TrayAnimationTimerTask();
                        mTrayAnimationTimer = new Timer();
                        mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        }
        return false;
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            
            // Calculate destination X based on position gravity
            switch (WindowSetup.positionGravity) {
                case "auto":
                    // Auto-position based on current position relative to screen center
                    if (flutterView != null && flutterView.getWidth() > 0) {
                        mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0
                                : szWindow.x - flutterView.getWidth();
                    } else {
                        mDestX = params.x;
                    }
                    break;
                case "left":
                    mDestX = 0;
                    break;
                case "right":
                    if (flutterView != null && flutterView.getWidth() > 0) {
                        mDestX = szWindow.x - flutterView.getWidth();
                    } else {
                        mDestX = params.x;
                    }
                    break;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    break;
            }
            
            // Ensure destination position is within bounds
            if (flutterView != null && flutterView.getWidth() > 0 && flutterView.getHeight() > 0) {
                if (mDestX < 0) mDestX = 0;
                if (mDestX + flutterView.getWidth() > szWindow.x) {
                    mDestX = szWindow.x - flutterView.getWidth();
                }
                if (mDestY < -statusBarHeightPx()) mDestY = -statusBarHeightPx();
                if (mDestY + flutterView.getHeight() > szWindow.y) {
                    mDestY = szWindow.y - flutterView.getHeight();
                }
            }
        }

        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                // Smooth animation with easing
                params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                
                if (windowManager != null) {
                    windowManager.updateViewLayout(flutterView, params);
                }
                
                // Stop animation when close enough to destination
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    TrayAnimationTimerTask.this.cancel();
                    mTrayAnimationTimer.cancel();
                }
            });
        }
    }

}