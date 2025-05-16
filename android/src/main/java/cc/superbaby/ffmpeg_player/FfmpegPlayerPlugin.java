package cc.superbaby.ffmpeg_player;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;

/**
 * FfmpegPlayerPlugin
 */
public class FfmpegPlayerPlugin implements FlutterPlugin, MethodCallHandler {
    private static final String TAG = "FfmpegPlayerPlugin";
    private MethodChannel channel;
    private TextureRegistry textureRegistry;

    // 用于存储创建的播放器实例
    private final Map<Long, RtspPlayer> players = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 单例 texture 相关变量
    private TextureRegistry.SurfaceTextureEntry singleTextureEntry;
    private Surface singleSurface;
    private long singleTextureId = -1;
    private RtspPlayer singlePlayer;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "ffmpeg_player");
        channel.setMethodCallHandler(this);
        textureRegistry = flutterPluginBinding.getTextureRegistry();
        
        // 初始化单例 texture
        initSingleTexture();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        // 清理所有未释放的播放器
        for (RtspPlayer player : players.values()) {
            player.release();
        }
        players.clear();
        
        // 释放单例 texture
        if (singlePlayer != null) {
            singlePlayer.release();
            singlePlayer = null;
        }
        if (singleSurface != null) {
            singleSurface.release();
            singleSurface = null;
        }
        if (singleTextureEntry != null) {
            singleTextureEntry.release();
            singleTextureEntry = null;
        }
        singleTextureId = -1;
    }
    
    // 初始化单例 texture
    private void initSingleTexture() {
        if (singleTextureEntry == null) {
            singleTextureEntry = textureRegistry.createSurfaceTexture();
            singleTextureId = singleTextureEntry.id();
            singleSurface = new Surface(singleTextureEntry.surfaceTexture());
            singlePlayer = new RtspPlayer(
                    singleTextureId,
                    singleSurface,
                    singleTextureEntry,
                    mainHandler
            );
            players.put(singleTextureId, singlePlayer);
            Log.d(TAG, "初始化单例 RTSP 播放器，纹理ID: " + singleTextureId);
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        Log.d(TAG, "收到方法调用: " + call.method + ", 参数: " + call.arguments);

        switch (call.method) {
            case "getPlatformVersion":
                result.success("Android " + android.os.Build.VERSION.RELEASE);
                break;
            case "createRtspPlayer":
                createRtspPlayer(result);
                break;
            case "playRtsp":
                setDataSource(call, result);
                break;
            case "startPlay":
                startPlaying(call, result);
                break;
            case "stopRtsp":
                stop(call, result);
                break;
            case "disposeRtspPlayer":
                disposePlayer(call, result);
                break;
            case "getRtspInfo":
                getPlayerInfo(call, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void createRtspPlayer(@NonNull Result result) {
        // 直接返回单例 texture ID
        if (singleTextureId != -1) {
            Map<String, Object> response = new HashMap<>();
            response.put("textureId", singleTextureId);
            result.success(response);
            Log.d(TAG, "返回已创建的RTSP播放器单例，纹理ID: " + singleTextureId);
            return;
        }
        
        // 如果单例初始化失败，则尝试新建一个
        initSingleTexture();
        
        if (singleTextureId != -1) {
            Map<String, Object> response = new HashMap<>();
            response.put("textureId", singleTextureId);
            result.success(response);
            Log.d(TAG, "新创建了RTSP播放器单例，纹理ID: " + singleTextureId);
        } else {
            result.error("TEXTURE_ERROR", "无法创建纹理", null);
        }
    }

    private void setDataSource(@NonNull MethodCall call, @NonNull Result result) {
        Object textureIdObj = call.argument("textureId");
        long textureId;

        // 安全地处理不同类型的转换
        if (textureIdObj instanceof Integer) {
            textureId = ((Integer) textureIdObj).longValue();
        } else if (textureIdObj instanceof Long) {
            textureId = (Long) textureIdObj;
        } else {
            result.error("INVALID_ARGS", "textureId 必须是数字类型", null);
            return;
        }

        String url = call.argument("url");
        if (url == null) {
            result.error("INVALID_ARGS", "缺少必要参数：url", null);
            return;
        }

        RtspPlayer player = players.get(textureId);
        if (player == null) {
            result.error("PLAYER_NOT_FOUND", "未找到指定ID的播放器: " + textureId, null);
            return;
        }

        player.setDataSource(url, new RtspPlayer.Callback() {
            @Override
            public void onSuccess() {
                result.success(null);
            }

            @Override
            public void onError(String errorCode, String errorMsg) {
                result.error(errorCode, errorMsg, null);
            }
        });
    }

    private void startPlaying(@NonNull MethodCall call, @NonNull Result result) {
        Object textureIdObj = call.argument("textureId");
        long textureId;

        // 安全地处理不同类型的转换
        if (textureIdObj instanceof Integer) {
            textureId = ((Integer) textureIdObj).longValue();
        } else if (textureIdObj instanceof Long) {
            textureId = (Long) textureIdObj;
        } else {
            Log.d(TAG, "startPlaying: textureId 必须是数字类型");
            result.error("INVALID_ARGS", "textureId 必须是数字类型", null);
            return;
        }

        RtspPlayer player = players.get(textureId);
        if (player == null) {
            Log.d(TAG, "startPlaying:  未找到指定ID的播放器");
            result.error("PLAYER_NOT_FOUND", "未找到指定ID的播放器: " + textureId, null);
            return;
        }

        player.play(new RtspPlayer.Callback() {
            @Override
            public void onSuccess() {
                result.success(null);
            }

            @Override
            public void onError(String errorCode, String errorMsg) {
                result.error(errorCode, errorMsg, null);
            }
        });
    }

    private void stop(@NonNull MethodCall call, @NonNull Result result) {
        Object textureIdObj = call.argument("textureId");
        long textureId;
        // 安全地处理不同类型的转换
        if (textureIdObj instanceof Integer) {
            textureId = ((Integer) textureIdObj).longValue();
        } else if (textureIdObj instanceof Long) {
            textureId = (Long) textureIdObj;
        } else {
            Log.d(TAG, "startPlaying: textureId 必须是数字类型");
            result.error("INVALID_ARGS", "textureId 必须是数字类型", null);
            return;
        }

        RtspPlayer player = players.get(textureId);
        if (player == null) {
            Log.d(TAG, "startPlaying:  未找到指定ID的播放器");
            result.error("PLAYER_NOT_FOUND", "未找到指定ID的播放器: " + textureId, null);
            return;
        }

        player.stop();
        result.success(null);
    }

    private void disposePlayer(@NonNull MethodCall call, @NonNull Result result) {
        Object textureIdObj = call.argument("textureId");
        long textureId;

        // 安全地处理不同类型的转换
        if (textureIdObj instanceof Integer) {
            textureId = ((Integer) textureIdObj).longValue();
        } else if (textureIdObj instanceof Long) {
            textureId = (Long) textureIdObj;
        } else {
            result.error("INVALID_ARGS", "textureId 必须是数字类型", null);
            return;
        }

        RtspPlayer player = players.remove(textureId);
        if (player == null) {
            result.error("PLAYER_NOT_FOUND", "未找到指定ID的播放器: " + textureId, null);
            return;
        }

        player.release();
        result.success(null);
    }

    private void getPlayerInfo(@NonNull MethodCall call, @NonNull Result result) {
        Object textureIdObj = call.argument("textureId");
        long textureId;

        // 安全地处理不同类型的转换
        if (textureIdObj instanceof Integer) {
            textureId = ((Integer) textureIdObj).longValue();
        } else if (textureIdObj instanceof Long) {
            textureId = (Long) textureIdObj;
        } else {
            result.error("INVALID_ARGS", "textureId 必须是数字类型", null);
            return;
        }

        RtspPlayer player = players.get(textureId);
        if (player == null) {
            result.error("PLAYER_NOT_FOUND", "未找到指定ID的播放器: " + textureId, null);
            return;
        }

        result.success(player.getInfo());
    }
}
