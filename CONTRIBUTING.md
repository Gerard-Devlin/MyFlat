# 参与 MyFlat

使用 Android Studio 或 JDK 17+ 与 Android SDK 36。提交前运行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

涉及扫描、文件保存、页序或导出时，还应在 ARM64 设备或支持 ARM64 转译的模拟器运行设备集成测试，命令见 README。

保持处理与 UI 分离；新效果应保留原图，失败应保留可重试状态。不要提交 SDK、模拟器状态、签名密钥、个人路径、分析工具缓存或构建产物。公共功能不设置会员与付费限制。

自有 Java 源码使用 Google Java Format。模型更新必须同时更新校验清单，不能用 UI 截图替代实际扫描输出验证。
