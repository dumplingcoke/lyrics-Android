# lyrics-Android

一个 Android 桌面歌词应用（应用名：**歌词**）。核心能力是把当前播放的音乐信息接管过来，在桌面/悬浮窗上逐字显示歌词。

## 功能

- **桌面悬浮歌词**：常驻悬浮窗，逐字高亮、描边、蒙版、渐隐等效果可分别调节
- **播放状态接听**：通过通知监听服务读取当前媒体会话，自动同步曲名、歌手、进度
- **逐字歌词格式**：支持 KRC（酷狗）、QRC（QQ 音乐）、WRC 等逐字歌词解析与解密
- **在线歌词搜索**：按歌曲信息在线匹配并缓存歌词
- **外观自定义**：字体、颜色、描边、背景图、行距、动画曲线、播放偏移等
- **快捷开关**：通知栏与快捷设置磁贴可一键开关桌面歌词
- **歌词文件管理**：本地导入、编辑、另存、最近目录与最近搜索记录

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

## 仓库内容说明

测试用的第三方音乐/歌词样本（`歌词和歌曲_仅用于测试功能是否实现/`）、构建产物、日志与截图均不入库，克隆后按上面的步骤自行构建即可。
