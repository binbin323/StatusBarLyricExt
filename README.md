# StatusBarLyricExt

这个工具可以为使用 [MediaSession](https://developer.android.google.cn/reference/android/media/session/MediaSession) 的音乐播放器添加状态栏歌词功能

支持的ROM:
- 所有Notification标志拥有 FLAG_ALWAYS_SHOW_TICKER 和 FLAG_ONLY_UPDATE_TICKER 的ROM
- AviumUI 16.2.0+

## 原理
- 通过 [MediaController](https://developer.android.google.cn/reference/android/media/session/MediaController) 取得当前播放的媒体信息
- 联网获取歌词后显示在状态栏上

## 使用的开源项目
- [LyricView](https://github.com/markzhai/LyricView)
