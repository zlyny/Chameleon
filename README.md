# Chameleon Android

ChameleonUltra 的 Android 客户端，通过 BLE（Nordic UART Service）与设备通信。
目标：实现 Mifare Classic 密钥恢复（NDK实现）、扇区数据管理、读卡 / 破解 / 写卡。

- 最低支持：Android 7.0（minSdk 24）
- 构建链：AGP 9.3.0 / Gradle 9.5.0 / JDK 25 / NDK 28.2 / CMake 3.22.1

## 当前功能（v2 字典攻击版）

| 功能         | 说明                                                                 |
| ---------- | ------------------------------------------------------------------ |
| BLE 扫描/连接 | 扫描页进入即自动扫描，列表展示名称 / 地址 / 信号强度；点击设备连接，断开后可重新扫描        |
| NUS 通信     | 写 RX 特征（6E400002）发数据，订阅 TX 特征（6E400003）收 notify                    |
| 协议收发       | 完整帧编解码、LRC 校验、BLE 分片 / 粘包重组；请求-响应配对（`request()` suspend API）      |
| 设备模式管理     | 连接就绪后读取工作模式（读卡器 / 模拟卡）并缓存，主界面工具栏实时显示模式图标                    |
| 读卡         | 读 UID / SAK / ATQA / ATS，检测 Mifare Classic 支持与 PRNG 类型；模拟卡模式自动切换为读卡器模式 |
| 字典攻击       | `MF1_CHECK_KEYS_OF_SECTORS` 批量尝试字典密钥，16 扇区 × A/B 密钥矩阵展示（绿勾 / 红叉 / 灰圈） |
| Static Nested | 点击红叉对该密钥位发起攻击：`MF1_STATIC_NESTED_ACQUIRE` 采集 NT → NDK 移植的 staticnested 算法求解 → `MF1_AUTH_ONE_KEY_BLOCK` 逐候选验证，日志格式对齐 CLI |
| Dump 导出     | 用已恢复密钥逐扇区读块，未破解扇区置 0；trailer 块的 KeyA/KeyB 区域读出恒为 0（协议安全设计），按已恢复密钥对称回填（KeyA 命中且 KeyB 已知时两者都覆盖，反之亦然）；以 `UID_SAK_ATQA.eml` 保存到公共下载目录（Download/Chameleon） |
| 通信日志       | 独立日志页，十六进制 TX / RX / 错误分色，自动滚动，可清空                               |
| 权限适配       | Android 12+（BLUETOOTH\_SCAN / CONNECT）与旧版（位置/存储权限）双路径                     |
| JNI 链路     | `ChameleonNative.staticnestedRecover`：NDK 移植的 Crypto1 求解（crapto1 + nested_util 单线程化），darkside 预留        |

破解流程（对齐 CLI `hf 14a info` → `hf mf nested` 工作流）：

1. **Read** — 读卡号并测 PRNG（Weak / Static / Hard）
2. **Recover keys** — 字典攻击（当前字典仅 `FFFFFFFFFFFF`，见 `KeyDictionary`，可扩展）
3. 点击红叉 — Nested 攻击：Static PRNG 卡即刻执行 Static Nested；
   Weak PRNG 卡的 nested 攻击为后续版本预留（入口见 `MainViewModel.recoverKeyByNested`）
4. **Dump** — 忽略未破解扇区，读取全卡数据保存

BLE 能力基于 [Nordic Kotlin-BLE-Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library)
（`no.nordicsemi.kotlin.ble:client-android:2.0.0-alpha19`，与 nRF Toolbox 4.4.1 同代 API），
扫描 / 连接 / 读写均为协程与 Flow API，无回调式样板代码。

## 页面结构

```
MainActivity（launcher，单 Activity + 底部导航，Fragment 以 show/hide 切换保留状态）
├── 扫描页 ScanFragment    自动扫描 / 设备列表 / 连接 / 断开 / 电池查询
├── 读卡页 ReaderFragment   标签信息卡片 + 密钥矩阵（16 扇区 × A/B）+ Read / Recover / Dump
├── 日志页 LogFragment     通信日志（TX/RX/错误分色）+ 清空
└── 卡片页 CardsFragment   占位（后续：卡槽管理 / 加载 dump / 卡型设置）

顶部工具栏：连接状态副标题 + 设备工作模式图标（读卡器 / 模拟卡，与缓存的模式变量挂钩）
```

## 项目架构

```
com.example.chameleon/
├── ble/                       传输层：不依赖协议细节，可整体复用
│   ├── BleConstants.kt           NUS UUID、扫描时长等常量
│   ├── BleCenter.kt              进程级单例：持有 CentralManager（Application 初始化）
│   └── ChameleonBleClient.kt     NUS 客户端（连接流程 + suspend request + Listener 回调）
├── protocol/                  协议层：不依赖蓝牙，未来可复用于 USB
│   ├── ChameleonFrame.kt         帧编解码 + LRC 校验
│   ├── ChameleonCommand.kt       命令码定义（设备模式 / HF14A / MF1 系列命令）
│   ├── ChameleonStatus.kt        状态码表（注意：成功不一定是 0，设备级命令为 0x0068）
│   ├── FrameDecoder.kt           流式分帧器（分片 / 粘包 / 坏帧重同步）
│   └── HexUtils.kt               十六进制工具
├── device/                    设备层：命令编排，协议帧 → 类型化 Kotlin API
│   ├── Mf1Models.kt              DeviceMode / PrngType / TagInfo / SectorKeys 等数据模型
│   ├── ChameleonSession.kt       suspend 命令集：模式 / 读卡 / PRNG / 字典攻击 / 读块
│   ├── KeyDictionary.kt          字典密钥列表（当前仅全 FF，可扩展，单帧上限 83 个）
│   └── DumpExporter.kt           dump 保存（MediaStore / 传统文件系统双路径）
├── jni/
│   └── ChameleonNative.kt        NDK 桥接（staticnestedRecover / nativeVersion）
├── scan/                      扫描页
│   ├── ScanFragment.kt           扫描界面（权限请求 + 自动扫描 + 已连接卡片）
│   ├── ScanViewModel.kt          CentralManager.scan() Flow → 设备列表状态流
│   └── DeviceAdapter.kt          设备列表适配器（ListAdapter + RSSI 信号分级）
├── reader/
│   └── ReaderFragment.kt         读卡页（标签信息 + 动态密钥矩阵 + 按钮状态机）
├── log/
│   └── LogFragment.kt            日志页（分色渲染 + 自动滚动）
├── cards/
│   └── CardsFragment.kt          卡片管理占位页
├── MainViewModel.kt           应用级共享 ViewModel：连接 / 模式缓存 / 读卡流程 / 日志
└── MainActivity.kt            主界面：底部导航 + 工具栏状态渲染
```

分层原则：UI（Fragment/ViewModel）→ 设备层（device）→ 协议层（protocol）→ 传输层（ble），
层间单向依赖，改协议不动蓝牙，换传输（如 USB）不动协议。

## ChameleonUltra 协议帧格式

```
| SOF(1B) | LRC1(1B) | CMD(2B) | STATUS(2B) | LEN(2B) | LRC2(1B) | DATA(LEN B) | LRC3(1B) |
|  0x11   |   0xEF   |  大端   |    大端     |  大端   |          |    ≤512     |          |
```

- 多字节字段均为大端（网络字节序）
- LRC = 覆盖字节求和（mod 256）取补码；LRC1 覆盖 SOF，LRC2 覆盖 CMD|STATUS|LEN，LRC3 覆盖 DATA
- 客户端发送时 STATUS 固定 0x0000
- 帧总长 = LEN + 10，范围 10 \~ 522 字节

协议参考：RfidResearchGroup/ChameleonUltraDocs 仓库 `protocol.md`

## 构建说明

Android Studio 内直接编译即可。

命令行编译需先指定 JDK 25（foojay 在线下载在网络受限环境不可用）：

```powershell
$env:JAVA_HOME = "D:\binx\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（含 4 个 ABI 的 libchameleon.so）

### 构建排障：AS 构建失败但命令行成功

症状：AS 中构建报 `AAPT2 Daemon startup failed` + 全部 `buildCMakeDebug` 失败，
但命令行构建正常。

根因：Gradle 守护进程在每次构建时会把自身环境替换为**发起客户端的环境**。
若守护进程是由 TRAE/沙箱终端启动的（环境含 118 个沙箱变量），AS 触发构建时
环境被替换为 AS 的精简环境，此后守护进程 spawn 的所有子进程（ninja/aapt2/
cmake）会启动后立即静默退出。Gradle 官方明确警告不要让环境差异大的客户端
共享守护进程。

解决：终端执行 `.\gradlew.bat --stop` 后，直接在 AS 中重新构建
（AS 会生成自己的守护进程）。日常开发中，命令行构建与 AS 构建交替后如遇此症，
同样先 `--stop` 再从 AS 构建。

## 开发提醒

1. **线程模型**：`ChameleonBleClient` 的连接作用域运行在 `Dispatchers.Main.immediate`，
   Listener 回调均在主线程，可直接更新 UI。`MainViewModel` 的状态流同样在主线程更新。
   新增 BLE 代码时保持此约定。
2. **状态码语义**：命令成功状态因命令而异——设备级命令（模式 / 电池）成功为 `SUCCESS(0x0068)`，
   HF 卡操作成功为 `HF_TAG_OK(0x0000)`。`ChameleonSession` 的各命令已按固件语义校验，
   新增命令时先查 `ChameleonStatus` 与固件源码再定期望值。
   **Material3 字体 token 必须配 Material3 主题**：`?attr/textAppearanceTitleLarge`、
   `textAppearanceBodyLarge` 等 M3 token 只在 `Theme.Material3` 下定义；在
   `Theme.MaterialComponents` 下 `?attr` 解析会静默失败，TextView 落回默认小字号
   （表现为标题与正文一样大且都偏小）。主题与布局 token 体系必须配套。
   **trailer 块读出无密钥**：Mifare Classic 的 trailer（每扇区块 3）中 KeyA 对
   读卡器永远返回全 0，KeyB 在常规访问位下同样返回 0（协议安全设计，非故障）。
   dump 后必须用已恢复的密钥回填 KeyA（byte 0-5）/ KeyB（byte 10-15）区域。
3. **响应帧重组已就绪**：字典攻击响应（490 字节）会跨多个 BLE 包，`FrameDecoder`
   已处理分片 / 粘包，勿在上层再攒包。
   **字典攻击的请求 mask 是「跳过掩码」**：位=1 跳过该密钥位、位=0 才检查
   （固件 `mf1_toolbox_check_keys_of_sectors`，CLI autopwn 传全 0 = 检查全部扇区）。
   曾因把 mask 当"选中掩码"构造，设备跳过了全部 16 个扇区导致命中 0/32——
   掩码方向务必以固件源码为准。
4. **新增命令四步走**：`ChameleonCommand` 加常量 → （必要时）`ChameleonStatus` 加状态
   → `ChameleonSession` 加 suspend 方法（含响应解析与校验）→ `MainViewModel` 编排业务流程。
5. **字典扩展**：`KeyDictionary.keys` 追加密钥即可，单帧上限 83 个（与 CLI 一致）；
   超出需分批调用（固件 `MF1_CHECK_KEYS_OF_SECTORS` 语义支持，尚未用到）。
6. **Static Nested 算法（已移植，`app/src/main/cpp/crypto1/`）**：源码来自上位机
   `software/src`（crypto1.c / crapto1.c / bucketsort.c / parity.c 原样，
   nested_util.c 由 pthread 四线程改为单线程，并修复原版两处缺陷：
   `uniqsort` 读 `possibleKeys[i + 1]` 的末元素越界——PC 堆 padding 掩盖，
   Android scudo 页对齐分配下 SIGSEGV（真机 staticnested 闪退根因）；
   空候选段 `--kcount` 的 uint32 下溢）。JNI 入口
   `ChameleonNative.staticnestedRecover(uid, targetType, ntPairs)`，
   `ntPairs` 每元素打包 `(nt shl 32) or ntEnc`。已用 CLI 实测向量回归验证：
   `staticnested 510649254 96 18874693 3867850225 18874693 1779684639` →
   首候选即真密钥 `9d5000000410`（注意 CLI 只显示 4 个候选，是因其输出正则
   漏掉前导 0 的 11 位 hex 密钥；本实现返回全部 5 个并逐个验证）。
   Weak PRNG 卡的 nested / darkside 攻击为后续版本预留：命令码已收录
   （`MF1_NESTED_ACQUIRE` / `MF1_DETECT_NT_DIST`），算法入口在
   `MainViewModel.recoverKeyByNested` 的 PRNG 分派处扩展。
7. **实机排障**：连接失败优先看日志页错误信息；GATT 133 通常是距离 / 设备休眠 /
   连接风暴，重试即可；若持续，检查设备是否已被其他主机占用。

## 参考项目(在上一级文件夹内)
* `../Android-nRF-Toolbox-4.4.1`:`Nordic Kotlin-BLE-Library`例子,用于参考ble实现
* `../ChameleonUltra-main/firmware`:ChameleonUltra固件源码
* `../ChameleonUltra-main/software/script`:ChameleonUltra cli源码
* `../ChameleonUltra-main/software/src`:ChameleonUltra Mifare Classic 密钥恢复相关代码
* `../Kotlin-BLE-Library-version-2.0`:用于参考ble例子实现


