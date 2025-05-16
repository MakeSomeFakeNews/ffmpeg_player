package cc.superbaby.ffmpeg_player;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 封装 VideoPlayer.cpp 中使用的视频设置
 */
public class VideoSettings {
    private final SharedPreferences preferences;
    
    // 视频源类型常量
    public static final int SOURCE_UDP = 0;
    public static final int SOURCE_FILE = 1;
    public static final int SOURCE_ASSETS = 2;
    public static final int SOURCE_FFMPEG_URL = 3; // RTSP 等流
    public static final int SOURCE_EXTERNAL = 4;
    
    // 协议类型常量
    public static final int PROTOCOL_RTP_H264 = 0;
    public static final int PROTOCOL_RAW_H264 = 1;
    public static final int PROTOCOL_RTP_H265 = 2;
    public static final int PROTOCOL_RAW_H265 = 3;
    
    // 键值常量
    private static final String KEY_SOURCE = "vs_source";
    private static final String KEY_FFMPEG_URL = "vs_ffmpeg_url";
    
    public VideoSettings(Context context) {
        preferences = context.getSharedPreferences("pref_video", Context.MODE_PRIVATE);
    }
    
    public void setupForRtsp(String rtspUrl) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(KEY_SOURCE, SOURCE_FFMPEG_URL);
        editor.putString(KEY_FFMPEG_URL, rtspUrl);
        editor.apply();
    }
} 