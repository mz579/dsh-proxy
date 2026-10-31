# DSH Proxy

一个跑在 Android 上的**本地 API 中转代理**，为 [DSH-Android](https://github.com/jackwener/DeepseekHarness-Android) 之类的端侧 AI 助手提供稳定的 OpenAI 兼容接口。

## 它解决什么问题

某些「OpenAI 兼容」供应商在 SSE 流式响应里会发送 `"usage": null`。部分客户端（包括 DSH）在解析时只判断了 `undefined` 而没判 `null`，于是抛出类型错误，表现为：

```
provider transport failed / TRANSPORT
```

本 App 在本机起一个 HTTP 代理（默认 `127.0.0.1:8787`），把上游流里的 `usage: null` 剥掉，再把干净的流返回给客户端。客户端只需把 Base URL 指向 `http://127.0.0.1:8787/v1` 即可。

## 功能

- **本地代理**：原生 `ServerSocket` 实现 HTTP/1.1，支持
  - `Content-Length` 与 `Transfer-Encoding: chunked` 两种请求体
  - `Expect: 100-continue`（不回 100 会与客户端互等死锁）
  - SSE 流式逐事件转发，不缓冲
- **供应商预设**：阿里百炼 / DeepSeek / OpenAI / 智谱 / ModelScope / 自定义
- **网络自检**：DNS → TCP → TLS → `/models` → 直连对话 → **经本地代理端到端**（并校验 `usage:null` 是否已清除）
- **前台服务常驻**，支持开机自启（需系统白名单，见下）
- **打开即自启**：打开 App 时若检测到已开启自启且代理未运行，自动拉起

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 35 / build-tools 35.0.0）。

```bash
# 1) 指定 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 构建 debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3) 跑单元测试
./gradlew :app:testDebugUnitTest
```

Windows 用 `gradlew.bat`。

## 使用

1. 安装 APK，打开 App。
2. 选择供应商预设，填入自己的 API Key，勾选「开机自动启动代理」，点**保存配置**。
3. 点**启动**，状态栏变绿即 `127.0.0.1:8787` 已就绪。
4. 在 DSH 里把模型服务的 Base URL 填成 `http://127.0.0.1:8787/v1`。

### 开机自启（荣耀 / 华为必做）

荣耀、华为等 ROM 默认拦截第三方 App 的 `BOOT_COMPLETED` 广播，App 自身无法绕过。
点 App 里的**「去开启自启白名单」**按钮，在系统「应用启动管理」中把本 App 改为**手动管理**，并打开：

- 允许自启动
- 允许关联启动
- 允许后台活动

不设置也不影响使用 —— 打开 App 时会自动启动代理，只是重启手机后需要手动打开一次。

## 技术要点

### 为什么不用 NanoHTTPD

早期版本用过 NanoHTTPD，但它的 `parseBody()` 有三个硬伤：

1. 不支持 `Transfer-Encoding: chunked`（读到空 body）
2. 不处理 `Expect: 100-continue`（客户端等 100、服务端等 body，互等死锁）
3. 会对 body 做 `.trim()`，且 Content-Type 缺失时按 US-ASCII 解码

因此改为原生 `ServerSocket` 自行实现 HTTP/1.1。

### 其他实现细节

- OkHttp dispatcher 上调至 128/64，避免慢响应并发超过默认 `maxRequestsPerHost=5` 后阻塞排队
- 工作线程池有界（4~16 线程、队列 128），满则回 503 且不阻塞 accept 循环
- 读请求阶段 `soTimeout` 30s，防止半开连接长期占用线程
- 响应体上限 16MB，超限回 413
- `network_security_config.xml` 仅对 `127.0.0.1` / `localhost` 放行明文，其他域名仍强制 HTTPS

## 已知限制

- 普通 App 无法「强制停止」其他 App（`FORCE_STOP_PACKAGES` 是 signature 级权限），因此未提供此类功能。
- 推理型模型（如 `deepseek-v4.1-flash`）思考时间较长，界面显示等待属正常现象。

## License

MIT，见 [LICENSE](LICENSE)。
