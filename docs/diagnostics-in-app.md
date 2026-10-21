# 应用内诊断日志通道

## 为什么需要新通道

诊断产生于 **Discord 进程**（模块 Hook 运行处），而用户要在**模块 App** 的设置页查看。
两个进程之间能用的通道只有三条，其中两条已被证伪：

| 通道 | 结论 | 依据 |
| --- | --- | --- |
| 远程配置回写（`getRemotePreferences`） | **不可行** | libxposed 文档："those are read-only in hooked apps"；LSPosed 实现 `LSPosedRemotePreferences.edit()` 直接 `throw new UnsupportedOperationException("Read only implementation")` |
| 模块私有目录文件 | **不可行（已实测失败）** | 模块运行在 `com.discord` 进程，写 `/data/user/0/<模块包>` 被 SELinux/权限拒绝，异常被 `catch` 吞掉 |
| 导出型 ContentProvider | **本通道采用** | 模块 App 提供接收端，Discord 进程按授权名写入 |

`XLog` 仍然保留：logcat 与框架日志是权威记录，应用内通道只是把内容变成用户可复制的文本。

## 结构

```
Discord 进程                                   模块 App 进程
─────────────                                  ─────────────
Diagnostics.record(line)
  ├─ XLog.i(line)              → logcat / LSPosed 日志
  └─ 入内存队列（有界）
        ↓ 合并，最多每 1s 一次，后台线程
     ContentResolver.openOutputStream(
       content://…diagnostics/log)   ────────→  DiagnosticsProvider
                                                  ├─ UID 校验（仅自身 + com.discord）
                                                  └─ 追加写入 files/diagnostics.log
                                                          ↓
                                                    SettingsActivity
                                                      日志框 + 刷新/复制全部/清空
```

## 关键设计

### 推送必须批量化、异步化

`Diagnostics.record` 的调用点在 `HookBridge` 的渲染/布局路径上，**运行在 Discord 主线程**。
逐条做 binder 调用会直接拖慢宿主，因此：

- 先入有界队列（release 200 行、debug 400 行），溢出丢弃最旧的并计数；
- 由主线程 `Handler.postDelayed` 合并，最多每 1 秒一次；
- 真正的 `openOutputStream` + 写文件放在单线程 `diagnostics-push`（daemon）上执行。

### 只在 Hook 进程推送

用 `XLog.hasFramework()`（即 `sXposed != null`）区分进程：
模块 App 进程里没有框架引用，因此不会把日志推送给自己。

### 宿主 Context 来自视图

Discord 进程里模块自身没有 `Context`。`HookBridge.onMessageBound` 在拿到宿主
`MessageContentView` 时调用一次 `Diagnostics.captureHostContext(view.getContext())`，
只缓存 `ContentResolver`（进程级单例，不固定 Activity）与宿主 `dataDir`（仅用于日志标识）。

### 关键前提：`android:forceQueryable="true"`

Android 11+ 的包可见性**按调用方 UID 过滤**。AOSP 证据链：

1. `ContentProviderHelper.getContentProviderImpl` 在返回已发布 provider 前执行
   `AppGlobals.getPackageManager().resolveContentProvider(name, 0, userId)`，
   返回 `null` 就直接 `return null`（注释原文：*enforce limited package visibility
   (introduced in Android 11)*）。客户端表现为 `IllegalArgumentException: Unknown URL`。
2. `IPackageManagerBase.resolveContentProvider` → `Binder.getCallingUid()` →
   `ComputerEngine.resolveContentProvider(..., callingUid)` →
   `shouldFilterApplication(packageState, callingUid, component, TYPE_PROVIDER, userId)`。
3. `AppsFilterBase.shouldFilterApplicationInternal` 中，
   `isForceQueryable(targetAppId)` 为真时直接 `return false`（不参与过滤）。

调用方是 Discord，**无法给它加 `<queries>`**，因此必须由本模块声明
`<application android:forceQueryable="true">`。该属性 API 30+ 才有，
低于 30 的设备忽略；本项目 `minSdk 26` / `targetSdk 37`，编译期由
`compileSdk 37` 提供，不影响安装。

> 事实边界：以上均来自 AOSP 源码；**尚未在真机验证**该通道是否实际打通。
> 若在某个 ROM 上仍失败，日志里会出现
> `diagnostic push failed: IllegalArgumentException`，并带 “Unknown URL” 字样。
> 此时可在设置页关掉「把布局诊断回传到本应用」，彻底停止跨进程尝试。

### Provider 权限

导出的 Provider 必须自我校验，否则任意应用都能读写该文件：

- **写**（`openFile` 写模式、`insert`）：仅模块自身 UID 与 `com.discord` 的 UID
  （通过 `PackageManager.getApplicationInfo("com.discord", 0).uid` 解析）。
- **读**（`query`、只读 `openFile`）：仅模块自身 UID。
- 只接受 `content://<authority>/log` 这一个 URI，不做通用文件代理。
- 写入一律 `MODE_APPEND`：目标进程无法截断或覆盖已有诊断；「清空」只由设置页
  在本进程内用 `DiagnosticsLog.clear()` 完成。

### 体积控制

Provider 在每次写入前检查大小，超过 256 KB 就轮转为 `diagnostics.log.1`（覆盖旧备份）；
设置页读取时上限 512 KB 且只保留尾部，展示上限 400 行。

## 文件格式

```
T|ts=1730000000000 n=12 dropped=0 host=/data/user/0/com.discord at=120s
D|layout-diag: msg=… height=549 outcome=…
I|row-refresh-after-layout(notified=true,hostBind=false,before=549,after=391,…)
```

- `T|` 批次头：时间戳、条数、丢弃数、写入方 dataDir、开机秒数。
- `I|` / `D|` 行前缀：info / debug，展示时剥离。

`DiagnosticsLog.format(raw, maxLines)` 是纯函数（不依赖 Android API），
由 `DiagnosticsLogTest` 覆盖：前缀剥离、批头解析、尾部截断、旧格式兼容、异常批头容错。

## 隐私

诊断行只包含消息 ID、视图类名、高度/行数、布尔状态与结论标签。
**不含消息正文、译文、API 密钥、请求头**。`DiagnosticsLog` 与 Provider 都只按字节搬运，
不做内容解析，也不额外记录任何内容。

## 排障

| 现象 | 含义 |
| --- | --- |
| 设置页「暂无记录」 | Provider 没收到任何写入：模块未在 Discord 进程生效、或 `captureHostContext` 未被调用（渲染漏斗没走） |
| 有批次头但没有 layout-diag 行 | 推送通了，但重渲染/行刷新路径没产生诊断 |
| 批次头 `dropped>0` | 诊断产生速度快于推送，早期行被丢弃；调小诊断范围或缩短 `FLUSH_INTERVAL_MS` |
| `XLog` 里有 `diagnostic push failed` | Provider 不可用。若异常是 `IllegalArgumentException` 且含 “Unknown URL”，说明包可见性仍未放行；其他情况看异常类型（`SecurityException` = UID 校验拒绝，`FileNotFoundException` = 路径不符） |

## 设置页开关

「把布局诊断回传到本应用」对应 `Config.KEY_DIAGNOSTICS_FORWARD`（默认开启）。
关闭后 `Diagnostics.record` 只写日志，不再排队、不再跨进程写入。
