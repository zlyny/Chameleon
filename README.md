# Chameleon Android

ChameleonUltra 的 Android 客户端，通过 BLE（Nordic UART Service）与设备通信。
目标：实现 Mifare Classic 密钥恢复（NDK实现）、扇区数据管理、读卡 / 破解 / 写卡。

- 最低支持：Android 7.0（minSdk 24）
- 构建链：AGP 9.3.0 / Gradle 9.5.0 / JDK 25 / CMake 3.22.1；NDK 版本未在
  `build.gradle.kts` 锁定（用本地默认安装版，开发机为 28.2）
- UI：**全部 Compose**（`MainActivity` 的 `setContent` → `ChameleonApp` 的 Scaffold）
  ；已无 XML 布局、无 Fragment、无 ViewBinding。`res/` 只剩 `colors` / `strings` /
  `themes`——`themes.xml` 仅供 Manifest 里 Activity 的**窗口主题**使用（决定窗口
  背景与状态栏/导航栏配色），页面内容本身全部由 Compose 渲染

## 当前功能（v5 mfkey32 离线破解版）

| 功能         | 说明                                                                 |
| ---------- | ------------------------------------------------------------------ |
| BLE 扫描/连接 | 扫描页进入即自动扫描，列表展示名称 / 地址 / 信号强度；点击设备连接，断开后可重新扫描；**离开本页立即停止扫描**        |
| NUS 通信     | 写 RX 特征（6E400002）发数据，订阅 TX 特征（6E400003）收 notify                    |
| 协议收发       | 完整帧编解码、LRC 校验、BLE 分片 / 粘包重组；请求-响应配对（`request()` suspend API）      |
| 设备模式管理     | 连接就绪后读取工作模式（读卡器 / 模拟卡）并缓存，主界面工具栏实时显示模式图标；**点击图标即切换模式** |
| 读卡         | 读 UID / SAK / ATQA / ATS，检测 Mifare Classic 支持与 PRNG 类型（Static 卡进一步判 GEN1/GEN2 代次）；模拟卡模式自动切换为读卡器模式 |
| 字典攻击       | `MF1_CHECK_KEYS_OF_SECTORS` 批量尝试 13 个内置弱密钥（已恢复位自动跳过）；16 扇区 × A/B 密钥矩阵展示（绿勾=已恢复 / 蓝勾=Dump 验证 / 红叉 / 灰圈，单元格宽度自适应屏宽） |
| Static Nested | 点击红叉对该密钥位发起攻击：`MF1_STATIC_NESTED_ACQUIRE` 采集 NT → NDK 移植的 staticnested 算法求解 → `MF1_AUTH_ONE_KEY_BLOCK` 逐候选验证，日志格式对齐 CLI；KeyB 目标先走捷径——KeyA 已恢复时直接读 trailer（非全 0 即命中，多数卡访问位允许 KeyA 读 KeyB） |
| Nested (Weak) | Weak PRNG 卡自动适配：`MF1_DETECT_NT_DIST` 测 dist → `MF1_NESTED_ACQUIRE` 采集 (nt/nt_enc/par) → NDK 移植的 nested 算法在 dist±14 内枚举求解；攻击成功率有限，未命中属正常，再点重试 |
| mfkey32 离线破解 | 「写入槽」自动开启认证日志（`SET_DETECTION_ENABLE`），模拟卡被读卡器认证时固件记录 (uid, nt, nr, ar)；点「mfkey32」下载日志（`GET_DETECTION_COUNT/LOG`，过滤嵌套认证）→ 按 (uid, block, key) 分组两两组合求解（NDK 移植的 mfkey32v2）→ 全组复核去误报 → 命中密钥回填矩阵；日志格式对齐根目录 `dump_mf1_elog.py` |
| 密钥复用       | Nested / mfkey32 命中后立即用该密钥对未恢复位再查一轮（对齐 CLI autopwn 的 try_key）——全卡共用密钥的卡一次命中即可顺带恢复多个扇区；模拟卡模式下自动跳过（无真实卡可试探） |
| Dump 卡片库   | 用已恢复密钥逐扇区读块，未读取成功的字节记为 **XX**（未知，eml 中保留，不与真实数据 00 混淆）；trailer 的密钥区不以读出值为准（KeyA 恒读出 0、KeyB 依访问位可能不可读，协议安全设计），按密钥矩阵回填已破解密钥、未破解区域记 XX，访问位区 [6:10] 读出全零视为读取失败；**某扇区 4 块全部读取成功时所用密钥位立即升级 VERIFIED（绿勾→蓝色对号，逐扇区生效，矩阵本身即 dump 进度）**；以 `<UID>_<SAK>_<ATQA>_<PRNG>.eml`（如 `29919F13_08_0400_1.eml`，末段为 PRNG 编码 1=Static/2=Weak/3=Hard）存入 app 专属卡片库（免权限、可枚举可删除，旧格式兼容读取） |
| 卡片管理       | 卡片页列出卡片库：**写入槽**（切模拟卡模式 → 反碰撞数据 → 分帧写 64 块 + 开认证日志，对齐 CLI `hf mf eload`；进行中全部按钮禁用，完成 Snackbar 提示）、**加载**（元数据 + trailer 密钥回填读卡页矩阵，无需重新读卡即可继续破解）、**查看**（按扇区 hex 展示：绿色 = 已恢复密钥、琥珀 = 访问控制位、红色 XX = 未读取；未破解 / 部分未读取扇区标注，附图例）、**导出**（二进制 .bin 到系统 Download 目录，文件名同卡片库新格式，同名自动加 " (n)" 序号；未破解 trailer 密钥区填 FF×6、访问位区填 FF 07 80 69、其余未知填 00）、**删除**（确认后移除） |
| 通信日志       | 独立日志页，十六进制 TX / RX / 错误分色；停在底部时跟随新日志、上翻查历史时停止跟随；一键复制 / 清空                         |
| 权限适配       | Android 12+（BLUETOOTH\_SCAN / CONNECT）与旧版（位置权限）双路径；卡片库为 app 专属目录；导出 Download：Android 10+ 走 MediaStore 免权限，Android 9 及以下运行时申请写存储权限 |
| JNI 链路     | `ChameleonNative.staticnestedRecover` / `nestedRecover` / `mfkey32Recover` / `mfkey32Verify`：NDK 移植的 Crypto1 求解（crapto1 + nested_util 单线程化 + mfkey32v2），darkside 预留 |

破解流程（对齐 CLI `hf 14a info` → `hf mf nested` → `hf mf autopwn` 工作流）：

1. **Read** — 读卡号并测 PRNG（Weak / Static / Hard），Static 卡经
   `HF14A_RAW`（60 00 取 NT）进一步判定 GEN1（NT=01200145）/ GEN2（NT=009080A2）
2. **Recover keys** — 字典攻击（13 个内置弱密钥，已恢复位跳过，可重复点击增量破解）
3. 点击红叉 — Nested 攻击（按 PRNG 自动分派）：Static 卡走 StaticNested，
   Weak 卡走 Nested（测 dist → 采 NT 三元组 → dist±14 枚举求解）；
   KeyB 目标先走 KeyA 读 trailer 捷径；命中后自动密钥复用检查其余扇区；
   单次未命中属正常现象，再次点击即可重试
4. **Dump** — 读取全卡数据存入卡片库（未读取成功的字节记 XX，PRNG 随
   文件名保存）；某扇区 4 块全部读成功时该密钥位立即升级 VERIFIED（蓝勾），
   逐扇区生效，可据此实时看到 dump 进度；卡片页可
   查看（密钥 / 控制位 / XX 着色）、写入槽（切模拟卡模式 + 反碰撞数据 +
   64 块分帧写入 + 开认证日志）、加载（回填读卡页矩阵继续破解）、
   导出 .bin（Download 目录）、删除
5. **mfkey32** — 写入槽模拟该卡后交给目标读卡器认证（门禁等），回来点
   「mfkey32」下载认证日志离线破解：同 (uid, block, key) 分组 ≥2 条记录
   即可恢复密钥，命中后回填矩阵；设备在读卡器模式且原卡在场时自动用
   新密钥试探其余扇区

BLE 能力基于 [Nordic Kotlin-BLE-Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library)
（`no.nordicsemi.kotlin.ble:client-android:2.0.0-alpha19`，与 nRF Toolbox 4.4.1 同代 API），
扫描 / 连接 / 读写均为协程与 Flow API，无回调式样板代码。

## 页面结构

```
MainActivity.setContent → ChameleonApp（Scaffold：TopAppBar + NavigationBar）
├── 扫描页 ScanScreen       自动扫描 / 设备列表 / 连接 / 断开 / 电池查询
├── 读卡页 ReaderScreen     标签信息卡片 + 密钥矩阵（16 扇区 × A/B）+ Read / mfkey32 / Recover / Dump
├── 日志页 LogScreen        通信日志（TX/RX/错误分色）+ 复制 / 清空
└── 卡片页 CardsScreen      dump 卡片库：写入槽 / 加载 / 查看 / 导出 / 删除

顶部工具栏：连接状态副标题 + 设备工作模式图标（读卡器 / 模拟卡，与缓存的模式变量挂钩）
```

页面状态由 Activity 作用域的 ViewModel 持有，当前 tab 经 `rememberSaveable` 保留；
离开组合即丢弃、切回重新组合（扫描页据此自动重扫，卡片页由 `ChameleonApp` 显式重扫）。

## 项目架构

```
com.example.chameleon/
├── ble/                       物理传输（BLE 实现）
│   ├── BleConstants.kt           NUS UUID、扫描时长等常量
│   └── ChameleonBleClient.kt     NUS 客户端，实现 ChameleonTransport
├── protocol/                  协议层：不依赖任何物理传输
│   ├── ChameleonTransport.kt     传输通道接口（BLE / 未来 USB / Fake 的统一抽象）
│   ├── ChameleonFrame.kt         帧编解码 + LRC 校验
│   ├── ChameleonCommand.kt       命令码定义（设备模式 / HF14A / MF1 系列命令）
│   ├── ChameleonStatus.kt        状态码表（注意：成功不一定是 0，设备级命令为 0x0068）
│   ├── FrameDecoder.kt           流式分帧器（分片 / 粘包 / 坏帧重同步）
│   └── HexUtils.kt               十六进制 + 大端整数字节序工具
├── data/                      数据层：卡片库存储（eml 文件）与内容模型
│   ├── DumpRepository.kt         卡片库：扫描 / 保存 / 读取 / 删除（app 专属目录，预留 Room 扩展）；DumpCard 条目定义在文件顶部
│   └── DumpContent.kt            dump 内容模型（块数据 + 未知掩码 XX，导出填充规则）
├── device/                    设备层：命令编排，协议帧 → 类型化 Kotlin API
│   ├── Mf1Models.kt              DeviceMode / PrngType / StaticNestedGen / TagInfo / SectorKeys 等数据模型
│   ├── ChameleonSession.kt       suspend 命令集（只依赖 ChameleonTransport 接口）
│   ├── DeviceModeStore.kt        工作模式的唯一持有者（消除原先的双写入方）
│   └── KeyDictionary.kt          字典密钥列表（13 个内置弱密钥，单帧上限 83 个）
├── jni/
│   └── ChameleonNative.kt        NDK 桥接（staticnestedRecover / nestedRecover / mfkey32Recover / mfkey32Verify）
├── scan/                      扫描页
│   ├── ScanScreen.kt             设备 LazyColumn + 已连接卡片 + 权限申请
│   └── ScanError.kt             扫描失败的结构化原因 + 文案映射
├── reader/
│   ├── ReaderScreen.kt          标签信息卡 + 四个操作按钮 + 密钥矩阵卡
│   ├── KeyMatrix.kt              密钥矩阵：16 扇区 × A/B，单元格宽度自适应
│   ├── ReaderError.kt            读卡流程的结构化错误 + 文案映射
│   └── ReaderFlowController.kt   读卡流程控制器（读卡 / 破解 / Dump / 写模拟卡 / 加载 / mfkey32）
├── log/
│   ├── LogScreen.kt              日志 LazyColumn 分色 + 跟随尾部滚动；文件头有 Compose 入门导读
│   └── LogStore.kt               日志模型（LogKind / LogEntry）+ 环形缓冲（容量 200，序号分配）
├── ui/theme/
│   └── ChameleonTheme.kt         Compose 主题（对齐 XML 的 Theme.Chameleon，不启用动态取色）
├── cards/
│   ├── CardsScreen.kt           卡片 LazyColumn + 写入槽/加载/查看/导出/删除
│   ├── DumpViewerContent.kt      查看对话框（Compose AlertDialog）+ 内容区：64 块 LazyColumn + 图例常驻底部
│   └── CardsViewModel.kt         卡片库列表状态流 + 导出 Download（MediaStore / 旧版双路径）
├── di/
│   └── AppContainer.kt           依赖容器（谁来创建依赖，含 CentralManager）+ ViewModel 工厂 + 组合树入口 LocalAppContainer
├── ChameleonApp.kt            主界面 Scaffold：顶部工具栏 + 底部导航 + 四页切换
├── MainViewModel.kt           应用级共享 ViewModel（含 DiscoveredDevice / UiMessage）：扫描 + 连接生命周期 / 模式 / 日志 / 提示通道；读卡流程委托 ReaderFlowController
├── ChameleonApplication.kt    Application：初始化 AppContainer
└── MainActivity.kt            唯一 Activity：`setContent` 渲染 ChameleonApp
```

> **不熟悉 Compose 的话，建议从 `log/LogScreen.kt` 开始读**——它规模适中，
> 文件头部有一份「Compose 入门导读」列出本文件用到的概念对照表（状态提升、
> `remember` / `derivedStateOf` / `LaunchedEffect` / `LazyColumn` 等），
> 正文中每个概念出现处都有行内说明。`reader/KeyMatrix.kt` 适合接着看布局
> （`Row` / `Column` / `Box` 与 `weight` + `aspectRatio` 的自适应单元格）。
>
> 全项目共 8 处 `@Preview`，可在 Android Studio 里直接预览不改代码：
> `ChameleonTopBar` / `TabBar`（ChameleonApp）、`ScanPanel` / `ConnectedPanel`（ScanScreen）、
> `KeyMatrix`、`DumpViewerContent`、`DumpCardItem`（CardsScreen）、`LogScreen`。
> 整页组件（`ReaderScreen` / `CardsScreen`）因直接接收 ViewModel 无法直接预览。

分层原则：UI（Compose 页面 / ViewModel）→ 设备层（device）→ 协议层（protocol）→
物理传输（ble / 未来 usb），层间单向依赖。

- **协议层不认识任何物理传输**：`ChameleonSession` 只依赖 `ChameleonTransport`
  接口，因此新增 USB（CDC 串口）实现时，设备层与协议层零改动。
  目前 `MainViewModel.connectDevice()` 里固定构造 `ChameleonBleClient`，
  那一处就是未来做「USB / BLE 自适应」的唯一位置（已就地标注）。
- **依赖经 `di/AppContainer` 注入**：两个 ViewModel 都不再持有 `Application`，
  可直接在 JVM 单测里实例化。没有引入 Hilt——当前 2 个 ViewModel、32 个 Kotlin 文件，
  KSP 带来的构建成本高于收益；真需要按作用域注入时再切换不迟。
  `AppContainer` 同时负责创建**进程内唯一**的 `CentralManager`（原先是独立的
  `BleCenter` 单例）：它是有状态的重量级对象，扫描与连接必须共用同一实例。
- **只有两个 ViewModel**：`MainViewModel`（扫描 + 连接 + 模式 + 日志，读卡流程
  委托 `ReaderFlowController`）与 `CardsViewModel`（卡片库 + 导出）。扫描状态
  原先在独立的 `ScanViewModel` 里，已并入 `MainViewModel`——扫描与连接是同一
  领域，且 `ScanScreen` 本来就同时需要两者（去掉了一个工厂分支与一个构造参数）。
  反向拆回去没有收益，别再拆。
- UI 层已全部 Compose 化（无 XML 布局、无 Fragment、无 ViewBinding），
  下方四层（含 NDK 算法）自始至终未受影响——分层解耦的直接收益。

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

跑单元测试（无需真机，经 FakeChameleonTransport 回放固件响应），当前 13 个用例：
`ChameleonSessionTest`（readCard / 字典攻击 / dump 全链路）、`DeviceModeStoreTest`、
`LogStoreTest`（容量裁剪与序号分配）。

```powershell
$env:JAVA_HOME = "D:\binx\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest
```

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
   → `ChameleonSession` 加 suspend 方法（含响应解析与校验）→ `ReaderFlowController`
   编排业务流程（连接 / 模式 / 日志仍在 MainViewModel）。
5. **字典扩展**：`KeyDictionary.keys` 追加密钥即可，单帧上限 83 个（与 CLI 一致）；
   超出需分批调用（固件 `MF1_CHECK_KEYS_OF_SECTORS` 语义支持，尚未用到）。
   **字典攻击结果必须合并**：固件对跳过（已恢复）位返回未命中，直接覆盖会
   清掉已有结果——`ReaderFlowController.mergeSectorKeys` 保证 FOUND 优先保留；
   `checkKeysOfSectors` 的 `shouldCheck` 参数用于跳过已恢复位。
   **HF14A_RAW 的 options 是 MSB 优先位域**（对齐 CLI ctypes.BigEndianStructure
   的字段序）：bit7 激活场 / bit6 等响应 / bit5 发送附 CRC / bit4 自动选卡 /
   bit3 保持场 / bit2 校验响应 CRC，DATA 布局 `options[1]+timeout[2]+bitlen[2]+data[N]`
   （大端）——对齐 CLI `hf 14a raw`（StaticNested 代次检测即 `-s -c -d 6000`
   取 NT，options=0x70）。曾因把位域当 LSB 优先写成 0x0E（等响应位未置位），
   设备不等卡应答导致代次检测恒失败——位域方向务必以 CLI 抓包帧为准。
6. **Nested 算法（已移植，`app/src/main/cpp/crypto1/`）**：源码来自上位机
   `software/src`（crypto1.c / crapto1.c / bucketsort.c / parity.c 原样，
   nested_util.c 由 pthread 四线程改为单线程，并修复原版两处缺陷：
   `uniqsort` 读 `possibleKeys[i + 1]` 的末元素越界——PC 堆 padding 掩盖，
   Android scudo 页对齐分配下 SIGSEGV（真机 staticnested 闪退根因）；
   空候选段 `--kcount` 的 uint32 下溢）。两个 JNI 入口：
   `staticnestedRecover(uid, targetType, ntPairs)`（staticnested.c 移植，
   `ntPairs` 每元素打包 `(nt shl 32) or ntEnc`）与
   `nestedRecover(uid, dist, ntPairs, parities)`（nested.c 移植，先由固件
   `MF1_DETECT_NT_DIST` 测 dist，再在 dist±14 内枚举 NT 并按奇偶位筛选，
   复用 nested() 求解）。staticnested 已用 CLI 实测向量回归验证：
   `staticnested 510649254 96 18874693 3867850225 18874693 1779684639` →
   首候选即真密钥 `9d5000000410`（注意 CLI 只显示 4 个候选，是因其输出正则
   漏掉前导 0 的 11 位 hex 密钥；本实现返回全部 5 个并逐个验证）。
   Weak 卡的 nested 攻击成功率有限（依赖随机数碰撞），未命中时提示用户
   再次点击重试；darkside 攻击为后续版本预留（命令码已收录）。
7. **实机排障**：连接失败优先看日志页错误信息；GATT 133 通常是距离 / 设备休眠 /
   连接风暴，重试即可；若持续，检查设备是否已被其他主机占用。
8. **dump 卡片库与写入模拟卡**：卡片库位于 app 专属外部目录
   `Android/data/<pkg>/files/dumps/`（免权限、可枚举、卸载即清理），文件名
   `<UID>_<SAK>_<ATQA>_<PRNG>.eml` 即元数据（PRNG 编码 1=Static/2=Weak/
   3=Hard/0=未知，读卡时检测的 PRNG 随 dump 保存，「加载」回读卡页时
   无需重新检测；v4 前旧格式 `UID_x_SAK_x_ATQA_x.eml` 兼容读取，PRNG
   视为未知），无需索引文件；eml 中 **XX 表示未知字节**
   （dump 时未读取成功 / 未破解，`DumpContent` 以 known 掩码建模，旧版全 0 行
   兼容读取），后续要标记"破解失败 / 读写失败扇区"时引入 Room（以文件名为主键，
   每扇区状态一列），`DumpRepository` 的 API 保持不变即可平滑切换。写入模拟卡 =
   `CHANGE_DEVICE_MODE` 切模拟卡 → `HF14A_SET_ANTI_COLL_DATA`
   （uidLen+uid+atqa[2]+sak[1]+atsLen[1]+ats）→ `MF1_SET_DETECTION_ENABLE`
   开 mfkey32 认证日志 → `MF1_WRITE_EMU_BLOCK_DATA`
   （blockStart[1]+data[N*16]，单帧上限 31 块，实际按 16 块/帧 × 4 帧写入），
   两命令成功状态均为 `SUCCESS(0x0068)`；未知字节（XX）按 0x00 写入设备。
   CLI `hf mf eload` 只写块数据不设反碰撞数据，App 侧补设 UID/ATQA/SAK
   以保证模拟卡卡号与原卡一致（写入当前激活卡槽，卡槽选择为后续扩展）。
   导出 .bin：未知字节按区域填充（trailer 密钥区 FF×6、访问位区 FF 07 80 69、
   其余 00，见 `DumpContent.toExportBinary`）；Android 10+ 经 MediaStore 写
   Download 免权限，Android 9 及以下需运行时 WRITE\_EXTERNAL\_STORAGE。
9. **mfkey32 离线破解**：认证日志条目 18 字节
   `block[1]+bitfield[1]+uid[4]+nt[4]+nr[4]+ar[4]`（bitfield bit0=KeyB、
   bit1=nested；`MF1_GET_DETECTION_LOG` 响应 data 上限约 512B ≈ 28 条/帧，
   按返回条数推进索引分批下载）。算法移植自 `software/src/mfkey32v2.c`
   （native-lib.cpp：记录 a 的 keystream 恢复候选状态回滚出密钥、记录 b
   前向验证），编排对齐根目录 `dump_mf1_elog.py`——按 (uid, block, key)
   分组、组内两两组合、命中后全组复核（`mfkey32Verify`）去误报并统计
   "复核通过 n/m 条"。注意：嵌套认证记录（isNested，NT 为密文）不满足
   mfkey32 的明文 NT 假设，破解时过滤；同组 ≥2 条记录才有足够信息；
   密钥复用试探仅在读卡器模式下执行（模拟卡模式下场中无真实卡）。
10. **Snackbar 锚定（历史）**：原 View 版的 Snackbar 统一经 `util/Snackbars.kt` 的
   `Fragment.showSnackbar` 弹出——`Snackbar.make` 默认贴 android.R.id.content
   底部会盖住底部导航栏，须 `setAnchorView(R.id.bottomNav)` 锚定到导航栏上方。
   **该文件已随四个页面迁移 Compose 删除**：现在**全 app 只有一个 `SnackbarHost`**
   （`ChameleonApp` 的 `Scaffold.snackbarHost`），它落在底部导航栏之上，天然不
   遮挡，无需锚定。页面一律经 `MainViewModel.showMessage` 上报，不自带 Host。
10b. **提示消息只有一条通道，且是「事件」不是「状态」**：`MainViewModel.messages`
   是一条 `SharedFlow<UiMessage>`，`ChameleonApp` 里一个 `LaunchedEffect` 收集后
   弹 Snackbar；页面级反馈（复制 / 导出 / 删除）与读卡流程的成功 / 失败都走它。
   - 早期实现把 `lastError` / `lastSuccess` 存在 `ReaderState`（StateFlow）里，
     配 `consumeLastError()` / `consumeLastSuccess()` 由 UI 用完清除。那是为了
     「消息不丢」付出的代价：StateFlow 值相同不发射，所以还得「先消费再显示」
     来让连续两条相同文案都能弹出。**已改回事件流，这套补丁全部删除。**
   - 取舍：`showSnackbar` 是**挂起函数**，且 M3 的 `SnackbarHostState` 内部持有
     `Mutex`，所以多条消息是**排队依次显示**（第 2 条在第 1 条消失后才出现），
     不会顶掉上一条——这与 View 版 `Snackbar.show()` 的「覆盖」语义不同。
     能排几队由 `extraBufferCapacity` 决定：容量 N 时「正在显示 1 条 + 排队 N 条」，
     第 N+2 条起被 `tryEmit` 丢弃（实测：容量 1 → 前 2 条显示、第 3 条起丢；
     容量 2 → 前 3 条；依次类推）。本项目一个流程通常只有一条失败提示，1 够用。
   - `UiMessage.Error` 包的是 `ReaderError` 而非字符串：文案要留给 UI 调
     `toText(context)`，ViewModel 里不能出现 `Context`。
11. **Compose 版本约束（勿随手升）**：Compose 编译器 plugin 版本必须等于 AGP 9.3
   内置 Kotlin（2.2.10），对应 Compose UI/Material3 的 **1.9.x** 线。因此
   `gradle/libs.versions.toml` 中显式锁版本而**不用 BOM**——BOM 会把 runtime/material3
   拉到需要 Kotlin 2.3+ 的新线，届时编译报 "Compose Compiler requires Kotlin X"。
   这与 `README` 里 Kotlin-BLE-Library 只能用 alpha19 是同一类约束。
   新增 Compose 页面：套一层 `ChameleonTheme`，颜色沿用 `values*/colors.xml`
   （`colorResource` 会跟随深浅模式），不要另起一套 Compose 色值。
12. **（历史）ComposeView 不能塞进 View 对话框**：渐进迁移期踩过——View 的
   `MaterialAlertDialogBuilder` 窗口不带 `ViewTreeLifecycleOwner` /
   `ViewTreeSavedStateRegistryOwner`，`setView(ComposeView)` 建立组合时必崩。
   **现已无 View / 无 Fragment / 无 ComposeView，此坑不再存在**，保留条目仅供回溯。
13. **线程约定（勿在 UI 线程做重活）**：
   - NDK 求解（`staticnestedRecover` / `nestedRecover` / `mfkey32Recover`）统一经
     `ReaderFlowController.solveWithTiming` 切到 `Dispatchers.Default`；
     `mfkey32Verify` 逐条复核同样必须包 `withContext(Dispatchers.Default)`
     （它按命中密钥循环调用，漏一处就是整段跑在 UI 线程上）。
   - 卡片库文件读写（`DumpRepository`）必须切 `Dispatchers.IO`：
     `dumpCard` 的 `save`、`writeDumpToEmulator` / `loadDumpToReader` 的 `read`、
     以及卡片页 `readBlocks`（在 `CardsScreen` 侧切，不改 ViewModel API）。
14. **edge-to-edge**：`targetSdk 37`，Android 15+ 对 targetSdk ≥ 35 强制 edge-to-edge，
    `MainActivity.onCreate` 显式调用 `enableEdgeToEdge()` 以统一各系统版本行为；
    状态栏 / 导航栏避让依赖 `Scaffold` 默认的 `contentWindowInsets`
    （`WindowInsets.systemBars`），新增全屏页面时需自行处理 insets。
15. **扫描跟随页面生命周期**：`ScanScreen` 用 `DisposableEffect` 在离开组合时
    调用 `viewModel.stopScan()`——BLE 扫描耗电，切到其它 tab 不该继续扫。
    回到本页时 `LaunchedEffect(Unit)` 会重新触发扫描，体感不变。
16. **Compose 图标要不要 tint**：`ic_key_*` / `ic_mode_*` 等 drawable 自带 `fillColor`
   配色，用 Compose `Icon` 渲染时必须传 `tint = Color.Unspecified`，否则会被
   `LocalContentColor` 统一上色，四种密钥状态会全变成同一个颜色。
   **level-list 图标不适用**：原 View 版信号强度用 `ic_signal`（level-list）配合
   `ImageView.setImageLevel()`，Compose 的 `painterResource` 不支持 level，
   改为按等级直接选 `ic_signal_level_1..4`（见 `ScanScreen.rssiDrawable`），
   原 `ic_signal.xml` 已随之删除。
17. **传输层走接口、错误走类型、依赖走容器**：
   - `ChameleonSession` 只依赖 `protocol/ChameleonTransport`（`request` / `close` /
     `isReady`），不认识 BLE。**测试时注入 `FakeChameleonTransport`**
     （app/src/test），无需真机与实体卡即可跑通「读卡 → 破解 → dump」——
     这是本项目降低验证成本的关键设施。
   - 读卡流程的错误一律用 `ReaderError` 密封接口的分支表达，**不要直接上报
     拼好的中文字符串**；扫描失败同理用 `ScanError`。展示文案集中在
     `ReaderError.toText(context)` / `ScanError.toText(context)`，新增分支时
     `when` 会编译报错提醒你补文案。
   - **ViewModel 不要再引入 `Application`**：需要什么依赖就往 `di/AppContainer`
     里加，并在 `ChameleonViewModelFactory` 里传入构造函数。持有 `Application`
     会让整个类无法在 JVM 单测里实例化。
   - **工作模式只经 `DeviceModeStore.update()` 修改**。原先 MainViewModel 把
     `MutableStateFlow` 的可写版直接传给 ReaderFlowController，导致同一份状态
     有两个写入方；复位（`reset()`）漏掉会让模式图标在断开后残留上一次的值。
18. **大端整数字节序统一走 `HexUtils`**：`readU16` / `readU32` / `writeU16` /
   `u16be` / `u32be`。协议多字节字段都是大端，这类位运算历史上出过两次
   方向性错误（HF14A_RAW 位域、字典攻击掩码），不要再在别处手写一份副本。
19. **新增读卡流程一律走 `ReaderFlowController.launchExclusive`**：它依次完成
    「取 session（拿不到报 `NotConnected`）→ `phase != Idle` 拒绝重入 →
    `precondition` 前置校验 → 置 phase → 启动协程」，且 `try/catch/finally`
    **只在这一个地方写**。
    **不要再手写 `scope.launch { try { ... } finally { phase = Idle } }`**——漏掉
    `finally` 会让界面永久停在「进行中」、按钮全灰。这条过去靠人工约定（KDoc 里
    写「照抄骨架」），现在由骨架本身保证。`loadDumpToReader` 是唯一例外：它
    不需要设备在场、也不设 phase（加载时按钮不该禁用）。
    前置条件不满足时用 `notify(UiMessage.Error(ReaderError.Xxx))` 上报具体原因，
    **调用方不要再补一条笼统提示**（见 10b）。

## 参考项目(在上一级文件夹内)
* `../Android-nRF-Toolbox-4.4.1`:`Nordic Kotlin-BLE-Library`例子,用于参考ble实现
* `../ChameleonUltra-main/firmware`:ChameleonUltra固件源码
* `../ChameleonUltra-main/software/script`:ChameleonUltra cli源码
* `../ChameleonUltra-main/software/src`:ChameleonUltra Mifare Classic 密钥恢复相关代码
* `../Kotlin-BLE-Library-version-2.0`:用于参考ble例子实现


