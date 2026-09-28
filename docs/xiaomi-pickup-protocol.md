# 小米取餐码协议验证

环境：2026-09-28，超级小爱 8.2.62.3916（508002062），Android 17 / HyperOS 4。
网页登录通过小米官方账号页，凭据属于信岛独立设备会话。识别代码不导入系统账号、不调用 Root 服务。

## 失败原因与对照

原实现能够上传图片并建立 AIVS 连接，但没有声明 `Agent.ActionState.support_ddf`。
小米因此返回“当前版本暂不支持收藏内容，敬请期待升级”；部分早期请求没有收到取图推送，最终提示获取图片超时。

无屏幕操作的对照实验：显式发送空 `support_ddf` 时失败；仅声明 `65548` 后成功。
不发送该字段的后续请求可能复用服务端状态，因此不能用“省略字段后成功”证明字段不需要。
正式实现每次请求都发送这个已验证的声明，不依赖以前探测请求的缓存。

## 已验证的调用链

1. `file.ai.xiaomi.com/file/image` 上传当前任务图片。
2. AIVS WebSocket 配置 Java SDK 版本 `1076007`、启用推送。
3. 声明 `MEMORY`、`LLM` 开关及 `support_ddf: [65548]`，发出 `Nlp.Request`。
4. 收到 `Application.UploadResource` 后，以原会话编号回复 `MultiModal.ImageUnderstand`。
5. 收到 `Agent.Action` 的 `get_screen_content` 请求后，回复 `Agent.UploadScreenEvent`，引用同一张已上传图片。
6. 解析 `Agent.SuperIsland`，保留号码字符串和商品、门店信息，由信岛展示。

被动回复保留能力声明和应用状态，移除 `RenewSession`，使用 `System.EventRoute` 关联原请求。
只接受当前根会话及其数字后缀 `MemoryPush` 子会话的取图请求；其他设备操作不会被执行。
只有确认提供了当前图片的会话才能返回上岛结果。子会话结束不会提前终止根会话。

## 回归入口

`MiclawSelfTestService` 由 `android.permission.DUMP` 保护，在前台服务中调用与正式识别相同的 `RustBridge.analyzeScreenshot`。
它不需要打开 Activity，不需要点击屏幕，也不写入用户的服务商选择。

```sh
ADB_SERVER_SOCKET=tcp:127.0.0.1:5040 adb shell am start-foreground-service \
  -n com.jizizr.signaldock/.MiclawSelfTestService \
  --es transport XIAOMI_PICKUP --es mode FAST \
  --es image_path /data/user/0/com.jizizr.signaldock/cache/fixture-zero.png \
  --es expected_code 0076 --es expected_item 茉莉奶茶
```

图片必须预先位于信岛私有缓存目录。结果写入同目录的 `miclaw-self-test-result.json`。
诊断装包、布置测试图片和读取私有测试结果使用 ADB 测试环境权限；这些权限不参与账号登录或网络识别。
负例使用 `--ez expect_no_voucher true`，只将“未识别到可上岛凭证”判定为预期结果，不将网络错误算作通过。

JVM 回归覆盖协议封包、嵌套推送、会话路由、上下文保留、无关操作拒绝、前导零和配置状态。
本轮不安装 debug 或测试 APK，只覆盖安装 `com.jizizr.signaldock` 正式包。

## 正式包实测结果

上述流程接入正式入口、移除所有探测参数后，以下 6 项均通过：

| 用例 | 结果 |
| --- | --- |
| 5312 / 芭乐奶绿 | 号码、商品、门店匹配 |
| 0076 / 茉莉奶茶 | 前导零保留，商品匹配 |
| B028 / 冰美式 | 字母编号保留，商品匹配 |
| 不含订单的说明图片 | 返回未识别到凭证，没有返回之前的订单 |
| 超级小爱快速模式对照 | 5312 / 芭乐奶绿匹配 |
| 强制停止并重启应用后 | 独立网页登录会话仍可识别 0076 / 茉莉奶茶 |

全部使用已保存的独立网页登录会话，未导入系统账号；没有屏幕点击。
61 项 JVM 单元测试通过，`assembleRelease` 和 `lintDebug` 成功（Lint 无错误，存量警告保留）。
专家模式仍需要其原有设备签名，本轮不将专家模式标记为免 Root。
