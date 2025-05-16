import 'package:flutter/material.dart';
import 'package:ffmpeg_player/ffmpeg_player.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final TextEditingController _urlController = TextEditingController(
    text: 'rtsp://192.168.1.1:554/livertsp',
  );
  String? _currentUrl;
  RtspPlayer? _player;
  Map<String, dynamic>? _streamInfo;
  String? _errorMessage;
  bool _isPlaying = false;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('RTSP 播放器演示'),
        ),
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextField(
                  controller: _urlController,
                  decoration: const InputDecoration(
                    labelText: 'RTSP URL',
                    hintText: '输入RTSP流地址',
                  ),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: ElevatedButton(
                        onPressed: _isPlaying ? null : _playStream,
                        child: const Text('播放'),
                      ),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: ElevatedButton(
                        onPressed: _isPlaying ? _stopStream : null,
                        child: const Text('停止'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 24),
                Expanded(
                  child: Container(
                    decoration: BoxDecoration(
                      border: Border.all(color: Colors.grey),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: _currentUrl == null
                        ? const Center(child: Text('请输入RTSP URL并点击播放'))
                        : (_errorMessage != null
                            ? Center(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(Icons.error, color: Colors.red.shade800, size: 48),
                                    const SizedBox(height: 16),
                                    Text(
                                      _errorMessage!,
                                      style: TextStyle(color: Colors.red.shade800),
                                      textAlign: TextAlign.center,
                                    ),
                                  ],
                                ),
                              )
                            : RtspPlayerView(
                                url: _currentUrl,
                                onPlayerCreated: (player) {
                                  print('播放器已创建，textureId: ${player.textureId}');

                                  setState(() {
                                    _player = player;
                                  });

                                  // 延迟获取信息，允许播放器初始化
                                  Future.delayed(const Duration(seconds: 3), () {
                                    _updateStreamInfo();
                                  });
                                },
                                onError: (error) {
                                  print('播放器错误: $error');
                                  setState(() {
                                    _errorMessage = error;
                                    _isPlaying = false;
                                  });
                                },
                              )),
                  ),
                ),

                // 视频信息区域
                if (_streamInfo != null && _streamInfo!.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  const Text('视频信息：', style: TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      _buildInfoItem('宽度', '${_streamInfo!['width'] ?? '未知'} px'),
                      _buildInfoItem('高度', '${_streamInfo!['height'] ?? '未知'} px'),
                    ],
                  ),
                  Row(
                    children: [
                      _buildInfoItem('编解码器', _streamInfo!['codec'] ?? '未知'),
                      _buildInfoItem('帧率', '${_streamInfo!['fps']?.toStringAsFixed(1) ?? '未知'} fps'),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildInfoItem(String label, String value) {
    return Expanded(
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(8.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: const TextStyle(fontSize: 12, color: Colors.grey)),
              Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
            ],
          ),
        ),
      ),
    );
  }

  void _playStream() async {
    final url = _urlController.text.trim();
    if (url.isEmpty) {
      setState(() {
        _errorMessage = '请输入有效的RTSP URL';
      });
      return;
    }
    setState(() {
      _currentUrl = url;
      _errorMessage = null;
      _isPlaying = true;
    });

    try {
      if (_player != null) {
        Future.delayed(const Duration(seconds: 2), () {
          _player?.startPlay();
          _updateStreamInfo();
        });
      }
    } catch (e) {
      setState(() {
        _errorMessage = '播放失败: $e';
        _isPlaying = false;
      });
    }
  }

  void _stopStream() async {
    try {
      await _player?.stop();
      setState(() {
        _isPlaying = false;
      });
    } catch (e) {
      setState(() {
        _errorMessage = '停止播放失败: $e';
      });
    }
  }

  Future<void> _updateStreamInfo() async {
    if (_player != null) {
      try {
        print('请求播放器信息...');
        final info = await _player!.getInfo();
        print('获取到流信息: $info');
        setState(() {
          _streamInfo = info;
        });
      } catch (e) {
        print('获取流信息失败: $e');
      }
    }
  }

  @override
  void dispose() {
    _urlController.dispose();
    _player?.dispose();
    super.dispose();
  }
}
