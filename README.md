# 歌词

一个 Android 的歌词应用。

主要功能是识别其他应用当前播放的音乐歌曲，在应用主页或者悬浮窗上显示歌词。

## 项目说明

本人看不懂代码，修bug和新增功能全靠AI，所以怕有风险请不要使用。

前期由Trae的auto模式（自动使用模型）创建此项目，后续由glm 5.3、kimi-k3.1-pro、kimi-k3、DeepSeek-v4.1-flash模型持续优化和修bug；参与工具还有workbuddy和豆包工作，期间还使用了workbuddy的hy4模型，豆包工作参与的模型和Trae一致。
过程中chatgpt也参与到了bug分析。

最后查找如何实现状态栏歌词时，发现[Capsulyric](https://github.com/FrancoGiudans/Capsulyric)和[桌面歌词](https://github.com/LuoH-AN/lyrics-android)的优秀项目，根据两个项目新增了在线搜索歌词的功能，并根据[桌面歌词](https://github.com/LuoH-AN/lyrics-android)重构了主页歌词页面。

修改代码时，请遵循使用项目的各自许可。

### [TG频道](https://t.me/RwkkJgbyl)

## 常见问题
参考 [Tcrrrry/desktop-lyrics](https://github.com/tcrrry/desktop-lyrics)的README编写而来。

### 为什么会让AI搞这个软件？

喜欢在Telegram中听音乐，但Telegram无法显示歌词。

### 为什么Telegram中歌词会概率发生偏移？

Telegram播放音频时，会创建多个会话窗口，并且只支持识别整秒跳转进度，发生偏移只能暂停并重新播放歌曲，应用会重新对齐歌曲和歌词的进度，对齐进度时会概率发生回退到上一秒，这是正常行为。
此bug以我的认知无法修复，经常听的音乐建议下载到本地使用专业播放器播放。

### 它会录制或分析手机正在播放的声音吗？

不会。应用不申请麦克风权限，也不录制系统音频；歌曲信息来自播放器公开的 MediaSession。

### 为什么需要“通知使用权”？

这是 Android 提供跨应用访问 MediaSession 的系统入口。桌面歌词只使用媒体会话数据，不读取聊天或普通通知正文。

### 所有播放器都能使用吗？

只要播放器正确提供标准 MediaSession，通常就能读取。个别播放器、系统定制或省电策略可能导致兼容性差异。

### 歌词为什么偶尔需要几秒才能出现？

需要根据歌曲信息来匹配歌词，如果是在线搜索歌词，则需要并行查询多个公开来源、验证候选质量，必要时再补充跨语言别名重试。

## 构建

环境要求：JDK 17、Android SDK（compileSdk 34）。

```bash
# 1. 配置 SDK 路径
#    在项目根目录创建 local.properties，写入：
#    sdk.dir=C:/Users/<你的用户名>/AppData/Local/Android/Sdk

# 2. 构建
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 签名说明

仓库**不包含任何签名文件或口令**。如需使用自己的 keystore 签名，在 `local.properties` 中加入（该文件已被 `.gitignore` 排除）：

```properties
signing.storeFile=C:/path/to/your.keystore
signing.storePassword=your_password
signing.keyAlias=your_alias
signing.keyPassword=your_key_password
```

未配置这些项时，构建会自动跳过自定义签名，改用默认的调试签名，不影响正常编译。
