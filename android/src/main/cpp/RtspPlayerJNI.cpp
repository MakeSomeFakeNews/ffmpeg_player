#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <memory>
#include <thread>
#include <atomic>
#include <mutex>

extern "C" {
#include <libavformat/avformat.h>
#include <libavformat/avio.h>
#include <libavutil/time.h>
}

#include "Experiment360/FFMpegVideoReceiver.h"
#include "Decoder/VideoDecoder.h"
#include "Parser/H26XParser.h"

#include <AndroidThreadPrioValues.hpp>

#define LOG_TAG "RtspPlayerJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 播放模式枚举
typedef enum {
    MODE_REALTIME, // 实时模式（低延迟）
    MODE_FLUENT    // 流畅模式（低卡顿）
} PlaybackMode;

// RTSP播放器类
class RtspPlayer {
public:
    RtspPlayer(JNIEnv *env);

    ~RtspPlayer();

    // 设置Surface
    void setSurface(JNIEnv *env, jobject surface);

    // 设置RTSP URL
    int setDataSource(JNIEnv *env, const char *url);

    // 开始播放
    void play();

    // 停止播放
    void stop();

    // 设置播放模式
    void setPlaybackMode(int mode);

    // 获取流信息
    jobject getStreamInfo(JNIEnv *env);

    // 释放资源
    void release();

private:
    // 处理从FFmpeg接收到的NALU
    void onNewNALU(const NALU &nalu);

    // 直接处理原始视频数据的回调（可用于其他输入源）
    void onNewVideoData(const uint8_t *data, const std::size_t data_length);

    // RTSP相关
    std::string mUrl;
    PlaybackMode mPlaybackMode = MODE_REALTIME;
    std::unique_ptr<FFMpegVideoReceiver> mVideoReceiver;

    // 解析器和解码器
    std::unique_ptr<H26XParser> mParser;
    std::unique_ptr<VideoDecoder> mDecoder;

    // 解码信息
    VideoRatio mVideoRatio;
    bool mVideoRatioChanged = false;
    DecodingInfo mDecodingInfo;
    bool mDecodingInfoChanged = false;

    // 是否启用SPS VUI修复
    bool mEnableSpsVuiFix = true;

    // JVM引用，用于在线程中附加JNI环境
    JavaVM *mJavaVM = nullptr;
};

RtspPlayer::RtspPlayer(JNIEnv *env) {
    // 获取JavaVM实例
    env->GetJavaVM(&mJavaVM);
    // 创建解析器，并设置NALU回调
    mParser = std::make_unique<H26XParser>(
            std::bind(&RtspPlayer::onNewNALU, this, std::placeholders::_1)
    );

    // 创建解码器
    mDecoder = std::make_unique<VideoDecoder>(env);

    // 注册回调，用于接收解码器状态变化
    mDecoder->registerOnDecoderRatioChangedCallback(
            [this](const VideoRatio ratio) {
                mVideoRatio = ratio;
                mVideoRatioChanged = true;
            }
    );

    mDecoder->registerOnDecodingInfoChangedCallback(
            [this](const DecodingInfo info) {
                mDecodingInfo = info;
                mDecodingInfoChanged = true;
            }
    );

    LOGI("RtspPlayer created");
}

RtspPlayer::~RtspPlayer() {
    release();
    LOGI("RtspPlayer destroyed");
}

void RtspPlayer::setSurface(JNIEnv *env, jobject surface) {
    // VideoDecoder的setOutputSurface需要额外的idx参数，设置为0表示主解码器
    mDecoder->setOutputSurface(env, surface, 0);
    LOGI("Surface %s", surface ? "set" : "cleared");
}

int RtspPlayer::setDataSource(JNIEnv *env, const char *url) {
    stop();

    mUrl = url;
    LOGI("Data source set to: %s", url);
    return 0;
}

void RtspPlayer::onNewNALU(const NALU &nalu) {
    // 检查是否需要对SPS进行特殊处理
    if (mEnableSpsVuiFix && nalu.isSPS()) {
        if (nalu.IS_H265_PACKET) {
            // 对于H265，暂时不做特殊处理
            mDecoder->interpretNALU(nalu);
        } else {
            // 对于H264 SPS，添加VUI信息
            auto sps = H264::SPS(nalu.getData(), nalu.getSize());
            sps.addVUI();
            sps.experiment();
            auto tmp = sps.asNALU();
            NALU nalu1(tmp.data(), tmp.size());
            mDecoder->interpretNALU(nalu1);
        }
    } else {
        // 其他类型的NALU直接送到解码器
        mDecoder->interpretNALU(nalu);
    }
}

void RtspPlayer::onNewVideoData(const uint8_t *data, const std::size_t data_length) {
    mParser->parse_raw_h264_stream(data, data_length);

}

void RtspPlayer::play() {
    // 如果已经有接收器且正在播放，先停止
    if (mVideoReceiver) {
        mVideoReceiver->shutdown_callback();
        mVideoReceiver->stop_playing();
        mVideoReceiver.reset();
        LOGI("Stopped existing RTSP playback");
    }

    // 创建并启动FFMpegVideoReceiver
    mVideoReceiver = std::make_unique<FFMpegVideoReceiver>(
            mUrl,
            0,
            [this](uint8_t *data, int data_length) {
                // 处理原始H264数据
                this->onNewVideoData(data, data_length);
            },
            [this](const NALU &nalu) {
                // 直接处理NALU
                this->onNewNALU(nalu);
            }
    );

    // 开始播放
    mVideoReceiver->start_playing();
    LOGI("Started RTSP playback using FFMpegVideoReceiver for url: %s", mUrl.c_str());
}

void RtspPlayer::stop() {
    if (mVideoReceiver) {
        mVideoReceiver->shutdown_callback();
        mVideoReceiver->stop_playing();
        mVideoReceiver.reset();
        LOGI("Stopped RTSP playback");
    }
}

void RtspPlayer::setPlaybackMode(int mode) {
    mPlaybackMode = mode == 0 ? MODE_REALTIME : MODE_FLUENT;
    LOGI("Set playback mode to %s", mPlaybackMode == MODE_REALTIME ? "REALTIME" : "FLUENT");

    // 根据播放模式调整解析器参数
    mParser->setLimitFPS(-1);

//    if (mPlaybackMode == MODE_REALTIME) {
//        // 实时模式不限制FPS，降低延迟
//        mParser->setLimitFPS(-1);
//    } else {
//        // 流畅模式可以限制FPS，提高流畅度
//        mParser->setLimitFPS(30);
//    }
}

jobject RtspPlayer::getStreamInfo(JNIEnv *env) {
    // 创建 HashMap 对象
    jclass mapClass = env->FindClass("java/util/HashMap");
    jmethodID mapInit = env->GetMethodID(mapClass, "<init>", "()V");
    jmethodID mapPut = env->GetMethodID(mapClass, "put",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(mapClass, mapInit);

    // 添加视频宽高信息
    if (mVideoRatio.width > 0 && mVideoRatio.height > 0) {
        // 添加宽高
        jstring keyWidth = env->NewStringUTF("width");
        jstring keyHeight = env->NewStringUTF("height");
        jobject valueWidth = env->NewObject(
                env->FindClass("java/lang/Integer"),
                env->GetMethodID(env->FindClass("java/lang/Integer"), "<init>", "(I)V"),
                mVideoRatio.width
        );
        jobject valueHeight = env->NewObject(
                env->FindClass("java/lang/Integer"),
                env->GetMethodID(env->FindClass("java/lang/Integer"), "<init>", "(I)V"),
                mVideoRatio.height
        );
        env->CallObjectMethod(hashMap, mapPut, keyWidth, valueWidth);
        env->CallObjectMethod(hashMap, mapPut, keyHeight, valueHeight);
    }

    // 添加解码器信息（如果有）
    if (mVideoReceiver) {
        // 添加已接收数据大小
        jstring keyDataSize = env->NewStringUTF("receivedData");
        jobject valueDataSize = env->NewObject(
                env->FindClass("java/lang/Long"),
                env->GetMethodID(env->FindClass("java/lang/Long"), "<init>", "(J)V"),
                (jlong) mVideoReceiver->currentlyReceivedVideoData
        );
        env->CallObjectMethod(hashMap, mapPut, keyDataSize, valueDataSize);

        // 添加错误信息（如果有）
        if (!mVideoReceiver->currentErrorMessage.empty()) {
            jstring keyError = env->NewStringUTF("error");
            jstring valueError = env->NewStringUTF(mVideoReceiver->currentErrorMessage.c_str());
            env->CallObjectMethod(hashMap, mapPut, keyError, valueError);
        }
    }

    // 添加解析器状态
    jstring keyParsedNalus = env->NewStringUTF("parsedNalus");
    jobject valueParsedNalus = env->NewObject(
            env->FindClass("java/lang/Long"),
            env->GetMethodID(env->FindClass("java/lang/Long"), "<init>", "(J)V"),
            (jlong) mParser->nParsedNALUs
    );
    env->CallObjectMethod(hashMap, mapPut, keyParsedNalus, valueParsedNalus);

    jstring keyKeyFrames = env->NewStringUTF("keyFrames");
    jobject valueKeyFrames = env->NewObject(
            env->FindClass("java/lang/Long"),
            env->GetMethodID(env->FindClass("java/lang/Long"), "<init>", "(J)V"),
            (jlong) mParser->nParsedKonfigurationFrames
    );
    env->CallObjectMethod(hashMap, mapPut, keyKeyFrames, valueKeyFrames);

    // 添加解码信息
    if (mDecodingInfoChanged) {
        // 添加帧率
        jstring keyFps = env->NewStringUTF("fps");
        jobject valueFps = env->NewObject(
                env->FindClass("java/lang/Float"),
                env->GetMethodID(env->FindClass("java/lang/Float"), "<init>", "(F)V"),
                mDecodingInfo.currentFPS
        );
        env->CallObjectMethod(hashMap, mapPut, keyFps, valueFps);

        // 添加比特率
        jstring keyBitrate = env->NewStringUTF("bitrate");
        jobject valueBitrate = env->NewObject(
                env->FindClass("java/lang/Float"),
                env->GetMethodID(env->FindClass("java/lang/Float"), "<init>", "(F)V"),
                mDecodingInfo.currentKiloBitsPerSecond
        );
        env->CallObjectMethod(hashMap, mapPut, keyBitrate, valueBitrate);
    }

    return hashMap;
}

void RtspPlayer::release() {
    stop();
    // 解析器和解码器由智能指针自动释放
    LOGI("Resources released");
}

// JNI方法实现
extern "C" {

JNIEXPORT jlong JNICALL
Java_cc_superbaby_ffmpeg_1player_RtspPlayer_nativeInit(JNIEnv *env, jobject thiz) {
    // 初始化FFmpeg网络
    avformat_network_init();

    // 创建RtspPlayer对象
    RtspPlayer *player = new RtspPlayer(env);
    return reinterpret_cast<jlong>(player);
}

JNIEXPORT void JNICALL
Java_cc_superbaby_ffmpeg_1player_RtspPlayer_nativeSetSurface(JNIEnv *env, jobject thiz,
                                                             jlong native_ptr, jobject surface) {
    RtspPlayer *player = reinterpret_cast<RtspPlayer *>(native_ptr);
    if (player) {
        player->setSurface(env, surface);
    }
}

JNIEXPORT jint JNICALL
Java_cc_superbaby_ffmpeg_1player_RtspPlayer_nativeSetDataSource(JNIEnv *env, jobject thiz,
                                                                jlong native_ptr, jstring url) {
    RtspPlayer *player = reinterpret_cast<RtspPlayer *>(native_ptr);
    if (!player) return -1;

    const char *nativeUrl = env->GetStringUTFChars(url, 0);
    int result = player->setDataSource(env, nativeUrl);
    env->ReleaseStringUTFChars(url, nativeUrl);

    return result;
}

JNIEXPORT void JNICALL
Java_cc_superbaby_ffmpeg_1player_RtspPlayer_nativePlay(JNIEnv *env, jobject thiz,
                                                       jlong native_ptr) {
    RtspPlayer *player = reinterpret_cast<RtspPlayer *>(native_ptr);
    if (player) {
        player->play();
    }
}

JNIEXPORT void JNICALL
Java_cc_superbaby_ffmpeg_1player_RtspPlayer_nativeStop(JNIEnv *env, jobject thiz,
                                                       jlong native_ptr) {
    RtspPlayer *player = reinterpret_cast<RtspPlayer *>(native_ptr);
    if (player) {
        player->stop();
    }
}

JNIEXPORT void JNICALL
Java_cc_superbaby_ffmpeg_1player_RtspPlayer_nativeRelease(JNIEnv *env, jobject thiz,
                                                          jlong native_ptr) {
    RtspPlayer *player = reinterpret_cast<RtspPlayer *>(native_ptr);
    if (player) {
        player->release();
        delete player;
    }
}

JNIEXPORT jobject JNICALL
Java_cc_superbaby_ffmpeg_1player_RtspPlayer_nativeGetStreamInfo(JNIEnv *env, jobject thiz,
                                                                jlong native_ptr) {
    RtspPlayer *player = reinterpret_cast<RtspPlayer *>(native_ptr);
    if (player) {
        return player->getStreamInfo(env);
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_cc_superbaby_ffmpeg_1player_RtspPlayer_nativeSetPlaybackMode(JNIEnv *env, jobject thiz,
                                                                  jlong native_ptr, jint mode) {
    RtspPlayer *player = reinterpret_cast<RtspPlayer *>(native_ptr);
    if (player) {
        player->setPlaybackMode(mode);
    }
}

} // extern "C"