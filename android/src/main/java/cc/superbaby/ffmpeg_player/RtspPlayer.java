package cc.superbaby.ffmpeg_player;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.view.TextureRegistry;

/**
 * 基于 FFmpeg 的 RTSP 播放器实现
 */
public class RtspPlayer {
    private static final String TAG = "RtspPlayer";
    
    // JNI 相关的原生方法
    private native long nativeInit();
    private native void nativeSetSurface(long nativePtr, Surface surface);
    private native int nativeSetDataSource(long nativePtr, String url);
    private native void nativePlay(long nativePtr);
    private native void nativeStop(long nativePtr);
    private native void nativeRelease(long nativePtr);
    private native Map<String, Object> nativeGetStreamInfo(long nativePtr);
    
    private final long textureId;
    private final Surface surface;
    private final TextureRegistry.SurfaceTextureEntry textureEntry;
    private final Handler mainHandler;
    private final AtomicBoolean released = new AtomicBoolean(false);

    private long nativePtr;
//    private boolean isPlaying = false;
    private String currentUrl;
    
    // 视频播放器状态回调接口
    public interface Callback {
        void onSuccess();
        void onError(String errorCode, String errorMsg);
    }
    
    public RtspPlayer( long textureId, Surface surface, TextureRegistry.SurfaceTextureEntry textureEntry, Handler mainHandler) {
        this.textureId = textureId;
        this.surface = surface;
        this.textureEntry = textureEntry;
        this.mainHandler = mainHandler;

        // 初始化原生播放器
        this.nativePtr = nativeInit();
        nativeSetSurface(nativePtr, surface);
        
        Log.d(TAG, "RtspPlayer 创建: textureId=" + textureId + ", nativePtr=" + nativePtr);
    }
    
    public void setDataSource(String url, Callback callback) {
        Log.d(TAG, "请求设置数据源: " + url);
        if (released.get()) {
            callback.onError("PLAYER_RELEASED", "播放器已释放");
            return;
        }
        
        // 先停止任何正在进行的播放
        stop();
        
        currentUrl = url;
        Log.d(TAG, "设置数据源: " + url);
        
        new Thread(() -> {
            try {
                int result = nativeSetDataSource(nativePtr, url);
                if (result < 0) {
                    mainHandler.post(() -> callback.onError("SET_SOURCE_ERROR", "设置数据源失败: " + result));
                    return;
                }
                
                // 只设置数据源，不自动播放
                mainHandler.post(callback::onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "设置数据源异常", e);
                mainHandler.post(() -> callback.onError("EXCEPTION", "播放器异常: " + e.getMessage()));
            }
        }).start();
    }
    
    public void stop() {
//        if (released.get() || !isPlaying) {
//            return;
//        }
//
        Log.d(TAG, "停止播放");
        nativeStop(nativePtr);
//        isPlaying = false;
    }
    
    public void release() {
        if (released.compareAndSet(false, true)) {
            Log.d(TAG, "释放资源");
            stop();
            nativeRelease(nativePtr);
            surface.release();
            textureEntry.release();
        }
    }
    
    public Map<String, Object> getInfo() {
        if (released.get()) {
            Map<String, Object> info = new HashMap<>();
            info.put("isPlaying", false);
            info.put("url", currentUrl);
            info.put("error", "播放器已释放");
            return info;
        }
        
        Map<String, Object> info = nativeGetStreamInfo(nativePtr);
        if (info == null) {
            info = new HashMap<>();
        }
        
        info.put("textureId", textureId);
//        info.put("isPlaying", isPlaying);
        info.put("url", currentUrl);
        
        return info;
    }

    // 新增专门的播放方法
    public void play(Callback callback) {
        if (released.get()) {
            callback.onError("PLAYER_RELEASED", "播放器已释放");
            return;
        }
        
        if (currentUrl == null || currentUrl.isEmpty()) {
            callback.onError("NO_DATA_SOURCE", "未设置数据源");
            return;
        }
        
        Log.d(TAG, "开始播放: " + currentUrl);
        
        new Thread(() -> {
            try {
                nativePlay(nativePtr);
//                isPlaying = true;
                mainHandler.post(callback::onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "播放异常", e);
                mainHandler.post(() -> callback.onError("EXCEPTION", "播放异常: " + e.getMessage()));
            }
        }).start();
    }

    static {
        try {
            System.loadLibrary("ffmpeg_player");
            Log.i(TAG, "FFmpeg 播放器库加载成功");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "无法加载 FFmpeg 播放器库: " + e.getMessage());
        }
    }
} 