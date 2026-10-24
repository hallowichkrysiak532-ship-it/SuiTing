# 随听 SuiTing

把文字、文章和 PDF，变成自己的播客。Android 8.0 及以上可用。

- 粘贴文字、提取公众号或网页正文、导入文字型 PDF。
- 多种 Edge 音色和手机系统声音，按段生成并保存在本机。
- 暂停、跳段、倍速、后台播放，自动保存收听进度。
- 文稿清理、草稿保存、断点续生成和节目删除。

当前版本 **0.1.5**。每期最多 10 万字符，音频总量上限约 512 MB；PDF 最多 20 MiB、300 页，扫描件暂不支持 OCR。

## 使用

添加节目 → 导入正文 → 选择声音 → 创建并生成。

网页白屏时可在“更多”中选择“兼容重试”。已生成音频可离线播放，日常使用不需要电脑或服务器；卸载应用会删除节目。

Edge 生成需要联网，正文会发送至微软语音服务。此功能使用第三方客户端协议，可用性可能变化；系统声音的联网需求由手机引擎决定。

## 开发

使用 Android SDK 36、Build Tools 35.0.0、JDK 17 或更新版本、Gradle 8.13。设置 `JAVA_HOME` 和 `ANDROID_HOME`，或通过 Android Studio 配置 SDK。

Windows 推荐在项目目录运行：

```powershell
.\build.ps1
# 依赖齐全后离线构建
.\build.ps1 -Offline
```

脚本运行本地测试、静态检查并生成 APK，将缓存、临时文件和签名密钥放在项目的 `.tooling/` 中，安装包输出到 `dist/`。可通过不入库的 `.tooling/build-local.psd1` 配置 `JavaHome`、`AndroidSdk`、`GradleExecutable` 和 `ReadOnlyDependencyCache`，复用已有环境。

也可使用标准 Gradle Wrapper：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

标准构建输出为 `app/build/outputs/apk/debug/app-debug.apk`。首次构建会下载缺失的 Gradle 或依赖；本机辅助脚本可指定已有 Gradle，避免重复下载。

仓库不包含安装包、依赖缓存、本机路径或签名密钥。请在本地妥善保留 `.tooling/android-user-home/debug.keystore`；丢失密钥后，新生成的 APK 通常无法覆盖原安装。其他机器构建的 debug APK 也不会自动使用同一签名。

## 依赖与许可

使用 AndroidX、Media3、OkHttp、jsoup、PDFBox-Android 和 Bouncy Castle。Edge 令牌算法改编自 [rany2/edge-tts](https://github.com/rany2/edge-tts)，采用 LGPL-3.0，参考源码见 `tools/reference_edge_drm.py`。

第三方许可及 NOTICE 见 `app/src/main/assets/third-party/`，随安装包分发；根目录另保留 Edge 相关许可全文。
