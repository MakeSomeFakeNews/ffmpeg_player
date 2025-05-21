import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class FfmpegPlayer {
  static const MethodChannel _channel = MethodChannel('ffmpeg_player');

  static RtspPlayer? _singletonPlayer;

  static Future<String?> getPlatformVersion() async {
    final String? version = await _channel.invokeMethod('getPlatformVersion');
    return version;
  }

  static Future<RtspPlayer> createRtspPlayer() async {
    if (_singletonPlayer != null) {
      return _singletonPlayer!;
    }

    final result = await _channel.invokeMethod('createRtspPlayer');

    final Map<String, dynamic> response = {};
    if (result is Map) {
      result.forEach((key, value) {
        if (key is String) {
          response[key] = value;
        }
      });
    }

    final textureId = response['textureId'];
    if (textureId == null) {
      throw Exception('创建播放器失败：未返回有效的 textureId');
    }

    final player = RtspPlayer(textureId: textureId);
    _singletonPlayer = player;
    return player;
  }

  static Future<void> resetPlayer() async {
    if (_singletonPlayer != null) {
      await _singletonPlayer!.dispose();
      _singletonPlayer = null;
    }
  }
}

class RtspPlayer {
  final int textureId;
  bool _isDisposed = false;
  String? _currentUrl;
  bool _isPlaying = false;
  Map<String, dynamic>? _streamInfo;

  static const MethodChannel _channel = MethodChannel('ffmpeg_player');

  RtspPlayer({required this.textureId});

  String? get currentUrl => _currentUrl;

  bool get isPlaying => _isPlaying;

  // 获取流信息
  Map<String, dynamic>? get streamInfo => _streamInfo;

  // 播放RTSP流
  Future<void> play(String url) async {
    if (_isDisposed) {
      throw Exception('播放器已释放');
    }

    try {
      print('开始播放RTSP流: $url, textureId: $textureId');

      if (_isPlaying) {
        await stop();
      }

      await _channel.invokeMethod('playRtsp', {
        'textureId': textureId,
        'url': url,
      });

      _currentUrl = url;

      await _channel.invokeMethod('startPlay', {
        'textureId': textureId,
      });

      _isPlaying = true;
      print('播放指令发送成功');

      // 获取流信息
      await getInfo();
    } catch (e) {
      print('播放失败: $e');
      _isPlaying = false;
      rethrow;
    }
  }

  // 设置数据源
  Future<void> setDataSource(String url) async {
    if (_isDisposed) {
      throw Exception('播放器已释放');
    }

    // 停止之前的播放（如果有）
    if (_isPlaying) {
      await stop();
    }

    await _channel.invokeMethod('playRtsp', {
      'textureId': textureId,
      'url': url,
    });

    _currentUrl = url;
  }

  Future<void> startPlay() async {
    if (_isDisposed) {
      throw Exception('播放器已释放');
    }

    if (_currentUrl == null) {
      throw Exception('播放失败：未设置数据源');
    }

    await _channel.invokeMethod('startPlay', {
      'textureId': textureId,
    });

    _isPlaying = true;

    await getInfo();
  }

  // 停止播放
  Future<void> stop() async {
    if (_isDisposed || !_isPlaying) return;

    await _channel.invokeMethod('stopRtsp', {
      'textureId': textureId,
    });

    _isPlaying = false;
  }

  Future<Map<String, dynamic>> getInfo() async {
    if (_isDisposed) {
      return {'error': '播放器已释放'};
    }

    final result = await _channel.invokeMethod('getRtspInfo', {
      'textureId': textureId,
    });

    final Map<String, dynamic> info = {};
    if (result is Map) {
      result.forEach((key, value) {
        if (key is String) {
          info[key] = value;
        }
      });
    }

    _streamInfo = info;
    return info;
  }

  Future<void> dispose() async {
    if (_isDisposed) return;

    await stop();
    if (this == FfmpegPlayer._singletonPlayer) {
      _isDisposed = true;
      _currentUrl = null;
      _isPlaying = false;
      _streamInfo = null;
      return;
    }

    await _channel.invokeMethod('disposeRtspPlayer', {
      'textureId': textureId,
    });

    _isDisposed = true;
  }
}

class RtspPlayerView extends StatefulWidget {
  final String? url;
  final BoxFit fit;
  final bool autoPlay;
  final Function(RtspPlayer)? onPlayerCreated;
  final Function(String)? onError;

  const RtspPlayerView({
    Key? key,
    this.url,
    this.fit = BoxFit.contain,
    this.autoPlay = false,
    this.onPlayerCreated,
    this.onError,
  }) : super(key: key);

  @override
  State<RtspPlayerView> createState() => _RtspPlayerViewState();
}

class _RtspPlayerViewState extends State<RtspPlayerView> {
  RtspPlayer? _player;
  bool _isInitialized = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _initPlayer();
  }

  Future<void> _initPlayer() async {
    try {
      final player = await FfmpegPlayer.createRtspPlayer();
      _player = player;

      if (_player!.isPlaying) {
        await _player!.stop();
      }

      if (widget.onPlayerCreated != null) {
        widget.onPlayerCreated!(player);
      }

      setState(() {
        _isInitialized = true;
      });

      if (widget.url != null) {
        setDataSource(widget.url!);
        if (widget.autoPlay) {
          await _player?.startPlay();
        }
      }
    } catch (e) {
      print(e.toString());
      _handleError('初始化播放器失败: $e');
    }
  }

  Future<void> setDataSource(String url) async {
    try {
      setState(() {
        _errorMessage = null;
      });

      await _player?.setDataSource(url);
    } catch (e) {
      _handleError('播放失败: $e');
    }
  }

  void _handleError(String error) {
    setState(() {
      _errorMessage = error;
    });

    if (widget.onError != null) {
      widget.onError!(error);
    }
  }

  @override
  void didUpdateWidget(RtspPlayerView oldWidget) {
    super.didUpdateWidget(oldWidget);

    if (widget.url != oldWidget.url && widget.url != null && _isInitialized) {
      setDataSource(widget.url!);
    }
  }

  @override
  void dispose() {
    _player?.stop();
    _player = null;
    super.dispose();
  }

  num? _safeGetNum(String key) {
    final value = _player?.streamInfo?[key];
    if (value is num) {
      return value;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    if (!_isInitialized) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_errorMessage != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error, color: Colors.red, size: 48),
            const SizedBox(height: 16),
            Text(
              _errorMessage!,
              style: const TextStyle(color: Colors.red),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      );
    }

    final width = _safeGetNum('width')?.toDouble() ?? 1000.0;
    final height = _safeGetNum('height')?.toDouble() ?? 600.0;

    return ClipRect(
      child: FittedBox(
        fit: widget.fit,
        child: SizedBox(
          width: width,
          height: height,
          child: Texture(textureId: _player!.textureId),
        ),
      ),
    );
  }
}
