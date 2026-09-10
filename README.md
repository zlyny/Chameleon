# Chameleon Android

ChameleonUltra 的 Android 客户端，通过 BLE（Nordic UART Service）与设备通信。
目标：实现 Mifare Classic 密钥恢复（NDK实现）、扇区数据管理、读卡 / 破解 / 写卡。

- 最低支持：Android 7.0（minSdk 24）
- 构建链：AGP 9.3.0 / Gradle 9.5.0 / JDK 25 / NDK 28.2 / CMake 3.22.1

## 当前功能（v4 卡片管理版）

| 功能         | 说明                                                                 |
| ---------- | ------------------------------------------------------------------ |
| BLE 扫描/连接 | 扫描页进入即自动扫描，列表展示名称 / 地址 / 信号强度；点击设备连接，断开后可重新扫描        |
| NUS 通信     | 写 RX 特征（6E400002）发数据，订阅 TX 特征（6E400003）收 notify                    |
| 协议收发       | 完整帧编解码、LRC 校验、BLE 分片 / 粘包重组；请求-响应配对（`request()` suspend API）      |
| 设备模式管理     | 连接就绪后读取工作模式（读卡器 / 模拟卡）并缓存，主界面工具栏实时显示模式图标；**点击图标即切换模式** |
| 读卡         | 读 UID / SAK / ATQA / ATS，检测 Mifare Classic 支持与 PRNG 类型（Static 卡进一步判 GEN1/GEN2 代次）；模拟卡模式自动切换为读卡器模式 |
| 字典攻击       | `MF1_CHECK_KEYS_OF_SECTORS` 批量尝试 13 个内置弱密钥（已恢复位自动跳过）；16 扇区 × A/B 密钥矩阵展示（绿勾 / 红叉 / 灰圈） |
| Static Nested | 点击红叉对该密钥位发起攻击：`MF1_STATIC_NESTED_ACQUIRE` 采集 NT → NDK 移植的 staticnested 算法求解 → `MF1_AUTH_ONE_KEY_BLOCK` 逐候选验证，日志格式对齐 CLI |
| Nested (Weak) | Weak PRNG 卡自动适配：`MF1_DETECT_NT_DIST` 测 dist → `MF1_NESTED_ACQUIRE` 采集 (nt/nt_enc/par) → NDK 移植的 nested 算法在 dist±14 内枚举求解；攻击成功率有限，未命中属正常，再点重试 |
| 密钥复用       | Nested 命中后立即用该密钥对未恢复位再查一轮（对齐 CLI autopwn 的 try_key）——全卡共用密钥的卡一次命中即可顺带恢复多个扇区 |
| Dump 卡片库   | 用已恢复密钥逐扇区读块，未读取成功的字节记为 **XX**（未知，eml 中保留，不与真实数据 00 混淆）；trailer 的密钥区不以读出值为准（KeyA 恒读出 0、KeyB 依访问位可能不可读，协议安全设计），按密钥矩阵回填已破解密钥、未破解区域记 XX，访问位区 [6:10] 读出全零视为读取失败；以 `UID<UID>_SAK<SAK>_ATQA<ATQA>.eml`（如 `UID1E6FE3A6_SAK08_ATQA0400.eml`）存入 app 专属卡片库（免权限、可枚举可删除） |
| 卡片管理       | 卡片页列出卡片库：**写入槽**（切模拟卡模式 → 反碰撞数据 → 分帧写 64 块，对齐 CLI `hf mf eload`；进行中全部按钮禁用，完成 Snackbar 提示）、**查看**（按扇区 hex 展示：绿色 = 已恢复密钥、琥珀 = 访问控制位、红色 XX = 未读取；未破解 / 部分未读取扇区标注，附图例）、**导出**（二进制 .bin 到系统 Download 目录，同名自动加 " (n)" 序号；未破解 trailer 密钥区填 FF×6、访问位区填 FF 07 80 69、其余未知填 00）、**删除**（确认后移除） |
| 通信日志       | 独立日志页，十六进制 TX / RX / 错误分色，自动滚动，一键复制 / 清空                         |
| 权限适配       | Android 12+（BLUETOOTH\_SCAN / CONNECT）与旧版（位置权限）双路径；卡片库为 app 专属目录；导出 Download：Android 10+ 走 MediaStore 免权限，Android 9 及以下运行时申请写存储权限 |
| JNI 链路     | `ChameleonNative.staticnestedRecover` / `nestedRecover`：NDK 移植的 Crypto1 求解（crapto1 + nested_util 单线程化），darkside 预留 |

破解流程（对齐 CLI `hf 14a info` → `hf mf nested` → `hf mf autopwn` 工作流）：

1. **Read** — 读卡号并测 PRNG（Weak / Static / Hard），Static 卡经
   `HF14A_RAW`（60 00 取 NT）进一步判定 GEN1（NT=01200145）/ GEN2（NT=009080A2）
2. **Recover keys** — 字典攻击（13 个内置弱密钥，已恢复位跳过，可重复点击增量破解）
3. 点击红叉 — Nested 攻击（按 PRNG 自动分派）：Static 卡走 StaticNested，
   Weak 卡走 Nested（测 dist → 采 NT 三元组 → dist±14 枚举求解）；
   命中后自动密钥复用检查其余扇区；单次未命中属正常现象，再次点击即可重试
4. **Dump** — 读取全卡数据存入卡片库（未读取成功的字节记 XX）；卡片页可
   查看（密钥 / 控制位 / XX 着色）、写入槽（切模拟卡模式 + 反碰撞数据 +
   64 块分帧写入）、导出 .bin（Download 目录）、删除

BLE 能力基于 [Nordic Kotlin-BLE-Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library)
（`no.nordicsemi.kotlin.ble:client-android:2.0.0-alpha19`，与 nRF Toolbox 4.4.1 同代 API），
扫描 / 连接 / 读写均为协程与 Flow API，无回调式样板代码。

## 页面结构

```
MainActivity（launcher，单 Activity + 底部导航，Fragment 以 show/hide 切换保留状态）
├── 扫描页 ScanFragment    自动扫描 / 设备列表 / 连接 / 断开 / 电池查询
├── 读卡页 ReaderFragment   标签信息卡片 + 密钥矩阵（16 扇区 × A/B）+ Read / Recover / Dump
├── 日志页 LogFragment     通信日志（TX/RX/错误分色）+ 复制 / 清空
└── 卡片页 CardsFragment   dump 卡片库：写入槽 / 查看 / 导出 / 删除

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
│   ├── Mf1Models.kt              DeviceMode / PrngType / StaticNestedGen / TagInfo / SectorKeys 等数据模型
│   ├── ChameleonSession.kt       suspend 命令集：模式 / 读卡 / PRNG / 代次检测 / 字典攻击 / 读写块 / 写模拟卡
│   ├── KeyDictionary.kt          字典密钥列表（13 个内置弱密钥，单帧上限 83 个）
│   ├── DumpContent.kt            dump 内容模型（块数据 + 未知掩码 XX，导出填充规则）
│   └── DumpRepository.kt         dump 卡片库（app 专属目录：扫描 / 保存 / 读取 / 删除，预留 Room 扩展）
├── jni/
│   └── ChameleonNative.kt        NDK 桥接（staticnestedRecover / nestedRecover / nativeVersion）
├── scan/                      扫描页
│   ├── ScanFragment.kt           扫描界面（权限请求 + 自动扫描 + 已连接卡片）
│   ├── ScanViewModel.kt          CentralManager.scan() Flow → 设备列表状态流
│   └── DeviceAdapter.kt          设备列表适配器（ListAdapter + RSSI 信号分级）
├── reader/
│   └── ReaderFragment.kt         读卡页（标签信息 + 动态密钥矩阵 + 按钮状态机）
├── log/
│   └── LogFragment.kt            日志页（分色渲染 + 自动滚动）
├── cards/
│   ├── CardsFragment.kt          卡片管理页（写入槽 / 查看着色 / 导出 / 删除确认）
│   ├── CardsViewModel.kt         卡片库列表状态流 + 导出 Download（MediaStore / 旧版双路径）
│   └── DumpCardAdapter.kt        卡片列表适配器（ListAdapter + DiffUtil + 流程忙碌状态）
├── util/
│   └── Snackbars.kt              Snackbar 扩展（锚定底部导航上方，避免遮挡导航栏）
├── MainViewModel.kt           应用级共享 ViewModel：连接 / 模式缓存 / 读卡流程 / 写入模拟卡 / 日志
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
   **字典攻击结果必须合并**：固件对跳过（已恢复）位返回未命中，直接覆盖会
   清掉已有结果——`MainViewModel.mergeSectorKeys` 保证 FOUND 优先保留；
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
   `UID_x_SAK_x_ATQA_x.eml` 即元数据，无需索引文件；eml 中 **XX 表示未知字节**
   （dump 时未读取成功 / 未破解，`DumpContent` 以 known 掩码建模，旧版全 0 行
   兼容读取），后续要标记"破解失败 / 读写失败扇区"时引入 Room（以文件名为主键，
   每扇区状态一列），`DumpRepository` 的 API 保持不变即可平滑切换。写入模拟卡 =
   `CHANGE_DEVICE_MODE` 切模拟卡 → `HF14A_SET_ANTI_COLL_DATA`
   （uidLen+uid+atqa[2]+sak[1]+atsLen+ats）→ `MF1_WRITE_EMU_BLOCK_DATA`
   （blockStart[1]+data[N*16]，单帧上限 31 块，实际按 16 块/帧 × 4 帧写入），
   两命令成功状态均为 `SUCCESS(0x0068)`；未知字节（XX）按 0x00 写入设备。
   CLI `hf mf eload` 只写块数据不设反碰撞数据，App 侧补设 UID/ATQA/SAK
   以保证模拟卡卡号与原卡一致（写入当前激活卡槽，卡槽选择为后续扩展）。
   导出 .bin：未知字节按区域填充（trailer 密钥区 FF×6、访问位区 FF 07 80 69、
   其余 00，见 `DumpContent.toExportBinary`）；Android 10+ 经 MediaStore 写
   Download 免权限，Android 9 及以下需运行时 WRITE\_EXTERNAL\_STORAGE。
9. **Snackbar 锚定**：全 app 的 Snackbar 经 `util/Snackbars.kt` 的
   `Fragment.showSnackbar` 弹出——`Snackbar.make` 默认贴 android.R.id.content
   底部会盖住底部导航栏，须 `setAnchorView(R.id.bottomNav)` 锚定到导航栏上方；
   新页面弹提示一律用该扩展，勿直接调 `Snackbar.make`。

## 参考项目(在上一级文件夹内)
* `../Android-nRF-Toolbox-4.4.1`:`Nordic Kotlin-BLE-Library`例子,用于参考ble实现
* `../ChameleonUltra-main/firmware`:ChameleonUltra固件源码
* `../ChameleonUltra-main/software/script`:ChameleonUltra cli源码
* `../ChameleonUltra-main/software/src`:ChameleonUltra Mifare Classic 密钥恢复相关代码
* `../Kotlin-BLE-Library-version-2.0`:用于参考ble例子实现


