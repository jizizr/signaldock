# SignalDock（信岛）

SignalDock 是面向 HyperOS 超级岛的 Android 截图识别工具。点击控制中心磁贴后，应用会截取当前页面，并行完成 AI 结构化识别、二维码解析和来源应用图标提取，再将取餐码、商品、规格、商家等信息投递到超级岛。

## 功能

- 控制中心磁贴一键截图识别
- 自定义 OpenAI 兼容接口、小米取餐码和超级小爱（快速 / 专家）
- AI、二维码和来源图标并行处理
- 超级岛摘要态、展开态、拖拽分享和二维码小窗
- 微信小程序来源任务与图标恢复
- 企业微信二维码 URL 过滤
- Shizuku shell/root 能力自动检测
- API Key 与小米会话使用 Android Keystore 加密保存

## 环境要求

- Android 16 / API 36 及以上
- arm64-v8a 设备
- 支持超级岛的 HyperOS 设备可使用完整体验；其他设备回退到标准实时通知
- Shizuku 用于截图服务授权和可选系统能力
- 超级小爱快速模式通过 HTTPS / WebSocket 识别，识别过程不会自动读取系统账号
- 快速模式支持小米官方网页登录，无需 Root；新会话通过实际识别验证后才保存，退出登录页不会取消已开始的验证。网页登录适配目前支持超级小爱 8.2.62.3916；版本不匹配时会提示适配检查失败。账号登录过期后可重新连接
- 系统小爱账号导入仍需要 Root 模式的 Shizuku/Sui；专家接口还要求每次请求的硬件设备签名，网页登录不会绕过这一要求
- 小米取餐码渠道使用同一独立网页登录会话，通过网络回复小米的取图请求，再将原生上岛结果交给信岛展示，无需 Root。仅回复当前识别任务已上传的截图，不执行其他设备操作；没有可上岛凭证时返回未识别到凭证。协议与自动化验证见 [取餐码协议说明](docs/xiaomi-pickup-protocol.md)

## 构建

准备以下环境：

- JDK 21 或更新版本
- Android SDK Platform 37
- Android NDK `27.2.12479018`
- Rust stable
- `cargo-ndk`

先通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 或未提交的 `local.properties` 配置 Android SDK 路径。

```bash
cargo install cargo-ndk
./gradlew :app:assembleDebug
```

Rust 原生库会由 Gradle 自动构建，无需手动复制 `.so` 文件。
已有其他版本 NDK 时可通过 `-PndkVersion=28.2.13676358` 指定本机安装版本。

## Release 签名

仓库不包含任何签名材料。没有 `keystore.properties` 时，`assembleRelease` 会生成 unsigned APK；需要本地签名时：

```bash
cp keystore.properties.example keystore.properties
keytool -genkeypair \
  -keystore keystore/signaldock-release.p12 \
  -storetype PKCS12 \
  -alias signaldock \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
./gradlew :app:assembleRelease
```

在 `keystore.properties` 中填写真实密码。`keystore.properties`、`keystore/`、本机 SDK 配置和构建产物均已加入 `.gitignore`，不得提交到版本库。

## 验证

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
cd rust
cargo test
cargo clippy --all-targets --all-features -- -D warnings
```

网页登录页面的真机回归使用已签名、已压缩的 Release 构建，检查冷启动、Activity 重建和再次打开时的可见输入框。测试只读取页面尺寸与输入框数量，不填写账号、密码或验证码，也不修改已保存的识别会话。需要已连接的手机、受支持的小爱版本及网络：

```bash
./gradlew -PandroidTestBuildType=release \
  -Pandroid.testInstrumentationRunnerArguments.class=com.jizizr.signaldock.XiaomiWebLoginTest \
  :app:connectedReleaseAndroidTest
```

## 隐私说明

截图会发送到用户选择的 AI 服务商。应用不会把 API Key 或小米会话写入普通明文偏好；识别结果和模型原始响应也不会写入应用日志。发布前仍应检查暂存文件，确保没有本机配置、账号信息、截图、日志或签名文件。

## 许可证

本项目以 [GNU General Public License v3.0](LICENSE) 发布。项目与 Xiaomi、HyperOS、Miclaw、WeChat 或相关商标所有者无隶属或背书关系。
