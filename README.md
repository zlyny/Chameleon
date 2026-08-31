# Chameleon Android

ChameleonUltra 的 Android 客户端，通过 BLE（Nordic UART Service）与设备通信。
目标：实现 Mifare Classic 密钥恢复（NDK实现）、扇区数据管理、读卡 / 破解 / 写卡。

- 最低支持：Android 7.0（minSdk 24）
- 构建链：AGP 9.3.0 / Gradle 9.5.0 / JDK 25 / NDK 28.2 / CMake 3.22.1

## 当前功能（v1 基础版）

| 功能     | 说明                                                             |
| ------ | -------------------------------------------------------------- |
| BLE 扫描 | 开屏扫描页，进入即自动扫描全部 BLE 设备，列表展示名称 / 地址 / 信号强度，10 秒超时自停 |
| BLE 连接 | 点击设备即连接并进入主界面：连接 → 服务发现 → MTU 协商 → 订阅 notify → 就绪 |
| NUS 通信 | 写 RX 特征（6E400002）发数据，订阅 TX 特征（6E400003）收 notify                |
| 协议收发   | 完整帧编解码、LRC 校验、BLE 分片 / 粘包重组                                    |
| 电池查询   | 发送 `11 EF 04 01 00 00 00 00 FB 00`（GET\_BATTERY\_INFO），回复显示在界面 |
| 通信日志   | 十六进制显示 TX / RX / 错误分色，自动滚动，自动解析电池 / 版本                         |
| 权限适配   | Android 12+（BLUETOOTH\_SCAN / CONNECT）与旧版（位置权限）双路径             |
| JNI 链路 | `ChameleonNative.nativeVersion()` 已验证，NDK 编译打包正常               |

BLE 能力基于 [Nordic Kotlin-BLE-Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library)
（`no.nordicsemi.kotlin.ble:client-android:2.0.0-alpha19`，与 nRF Toolbox 4.4.1 同代 API），
扫描 / 连接 / 读写均为协程与 Flow API，无回调式样板代码。

## 页面结构

```
ScanActivity（开屏，launcher）
  自动扫描 → 设备列表（名称 + 地址 + RSSI 信号图标）
  点击设备 ──携带地址/名称──▶ MainActivity
                                连接 → 状态卡片 / 协议收发 / 通信日志
                                「重新扫描设备」──▶ 返回 ScanActivity
```

## 项目架构

```
com.example.chameleon/
├── ble/                       传输层：不依赖协议细节，可整体复用
│   ├── BleConstants.kt           NUS UUID、扫描时长等常量
│   ├── BleCenter.kt              进程级单例：持有 CentralManager（Application 初始化）
│   └── ChameleonBleClient.kt     NUS 客户端（连接流程 + suspend API + Listener 回调）
├── protocol/                  协议层：不依赖蓝牙，未来可复用于 USB
│   ├── ChameleonFrame.kt         帧编解码 + LRC 校验
│   ├── ChameleonCommand.kt       命令码定义（含 Mifare Classic 预留）
│   ├── FrameDecoder.kt           流式分帧器（分片 / 粘包 / 坏帧重同步）
│   └── HexUtils.kt               十六进制工具
├── jni/
│   └── ChameleonNative.kt        NDK 桥接（密钥恢复算法入口）
├── scan/                      扫描页
│   ├── ScanActivity.kt           开屏扫描界面（权限请求 + 自动扫描）
│   ├── ScanViewModel.kt          CentralManager.scan() Flow → 设备列表状态流
│   └── DeviceAdapter.kt          设备列表适配器（ListAdapter + RSSI 信号分级）
├── MainViewModel.kt           业务编排：连接 / 收发状态流 + 日志
└── MainActivity.kt            主界面：状态展示、协议命令、通信日志
```

分层原则：UI（ViewModel/Activity）→ 协议层（protocol）→ 传输层（ble），
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
   Listener 回调均在主线程，可直接更新 UI。库内部（Binder 线程的 GATT 回调）已由
   Kotlin-BLE-Library 统一调度。新增 BLE 代码时保持此约定。
2. **写特征用 write\_request**：`send()` 逐片用有响应写（WRITE\_TYPE\_DEFAULT）保证可靠
   有序。未来大数据量场景（如密钥字典爆破）可评估切换 write\_command 提升吞吐。
3. **响应帧重组已就绪**：读卡扇区数据（490 字节的 MF1\_CHECK\_KEYS 响应等）会跨多个
   BLE 包，`FrameDecoder` 已处理，直接调用即可，勿在上层再攒包。
4. **新增命令三步走**：`ChameleonCommand` 加常量 → ViewModel 加发送方法 →
   `interpretFrame` 加响应解析分支。
5. **NDK 算法扩展**：darkside / nested 攻击的 C 源码放 `app/src/main/cpp/`，
   在 `CMakeLists.txt` 的 `add_library` 登记，JNI 入口写在 `native-lib.cpp`，
   Kotlin 侧在 `ChameleonNative` 加 external 函数。
6. **实机排障**：连接失败优先看日志区错误信息；GATT 133 通常是距离 / 设备休眠 /
   连接风暴，重试即可；若持续，检查设备是否已被其他主机占用。

