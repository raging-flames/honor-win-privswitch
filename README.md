# 隐私开关（PrivSwitch）· HONOR WIN

面向 **HONOR WIN（AAP-AN00）/ MagicOS 11（Android 17）** 的原生隐私管控小工具：把已实施的隐私管控项集中管理，并支持 **应用 / 恢复 / 验证**。

- 纯原生（Activity + 原生控件），无 WebView、无第三方依赖
- 手工构建（`aapt2` + `javac` + `d8` + `zipalign` + `apksigner`），不依赖 Gradle
- 通过 `su` 执行 `pm` / `settings` / `iptables`，**不注入进程、不修改系统分区**
- 所有管控与验证都在本机完成

> 本项目针对 **HONOR AAP-AN00 / MagicOS 11（Android 17）** 的包名与数据路径编写，同型号同版本可直接使用；其它机型/版本需要自行调整包名与路径。

![设置页](docs/screenshots/settings.png)

## 功能

| # | 项目 | 类型 | 管控内容 |
|---|---|---|---|
| 1 | HiViewTunnel | 包禁用 | `com.hihonor.hiviewtunnel` 上传执行器 |
| 2 | 主题 | 包禁用 | `com.hihonor.android.thememanager` |
| 3 | 快游戏 | 包禁用 | `com.hihonor.quickgame` |
| 4 | CHR | 包禁用 | `com.hihonor.android.chr` 通信统计 |
| 5 | HiView UE 采集 | Settings | `user_experience_involved = 0` |
| 6 | LBS 众包 | Settings | `hihonor_crowdsourcing_switch_turn_on = 0` |
| 7 | LBS 端点阻断 | iptables | `cgicol.amap.com`、`logservice-lbs-drcn.dt.hihonorcloud.com` |
| 8 | 应用市场指标阻断 | iptables | `metrics-appdistribute-drcn.dt.hihonorcloud.com` |
| 9 | 天气广告阻断 | iptables | `tracking.ads.honor.com`、`ads-drcn.platform`、`tracking-data.platform`、`adsmind.gdtimg.com` |
| 10 | 家长控制断网 | iptables uid | 目标 UID 全部出网（保留 loopback） |
| 11 | 系统管家上报端点 | iptables | `apigw-cn.yun.hihonor.com`（与其它应用共用，谨慎） |

每项提供：

- **应用**：执行保护（幂等：先删同名规则再添加）
- **恢复**：撤销保护
- **验证**：读取实际状态、规则命中计数，域名项额外做一次主动探测

顶部：应用全部 / 恢复全部 / 全部验证 / 刷新；底部为运行日志。

## 环境要求

| 项目 | 要求 |
|---|---|
| 设备 | HONOR WIN（AAP-AN00），MagicOS 11 / Android 17（其它版本需调整包名与路径） |
| root | KernelSU 或 Magisk（需要可用的 `su`） |
| 构建 | Windows + PowerShell 5.1+、JDK 17+、Android SDK（build-tools + platform） |

App 运行在手机上，**安装完成后无需电脑**；只有“主动探测”需要网络，离线时会显示未命中。

## 构建

```powershell
# 可选：指定 SDK / JDK（不设置时脚本会尝试常见路径）
$env:ANDROID_SDK_ROOT = "C:\Users\you\AppData\Local\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

产物：`build/privswitch.apk`（debug 签名，首次构建会自动生成 `debug.keystore`）。

构建链路：

```
aapt2 compile -> aapt2 link -> javac (source/target 17) -> d8
  -> 合并 classes.dex -> zipalign -> apksigner
```

## 安装

```bash
adb install -r -t build/privswitch.apk
```

> 如果手机上已安装**不同签名**的旧版本，需要先 `adb uninstall com.local.privswitch` 再安装。

## 使用

1. 安装后打开应用，顶部应显示 `root: 已授权 ✓`
2. 首次使用需在 **KernelSU 管理器 → 超级用户** 中允许本应用
   - KernelSU 在未授权时会对应用隐藏 `su`，表现为 `Cannot run program "su": error=2`
3. 逐项点「应用」，或点「应用全部」
4. 点「验证」查看实际状态、规则命中计数与数据文件情况

## 验证语义

| 类型 | 验证方式 |
|---|---|
| 包禁用 | `pm list packages -d --user 0` 中存在该包 |
| Settings | `settings get secure <key>` 等于期望值 |
| 域名规则 | `iptables -S` / `ip6tables -S` 规则存在，`-L -v` 命中计数大于 0；并对首个域名发一次探测连接（TCP 载荷 `probe <host>`）确认规则可匹配 |
| UID 规则 | 规则存在（REJECT + loopback RETURN，v4/v6 各 2 条）+ 命中计数 |
| 数据文件 | 检查 `dataPaths` 是否存在 |

> 验证是“配置层 + 部分行为层”：能证明规则/开关/包状态已就位，以及规则确实能匹配流量；**不能证明加密流量中绝无上传**（TLS 内容不可见、QUIC/ECH 可能绕过、部分采集仅存内存）。

## 项目结构

```
.
├─ AndroidManifest.xml
├─ res/values/strings.xml
├─ res/drawable/ic_launcher.png
├─ src/com/local/privswitch/
│  ├─ MainActivity.java      # UI、管控项定义、应用/恢复/验证
│  └─ Shell.java             # su -c 执行封装（绝对路径 + 超时）
├─ scripts/
│  ├─ build.ps1              # 手工构建（无 Gradle）
│  └─ gen_icon.py            # 生成应用图标（Pillow，可选）
├─ docs/screenshots/
├─ CHANGELOG.md
└─ README.md
```

## 自动化（调试用）

Activity 支持 intent extra `action`：

```bash
adb shell am start -n com.local.privswitch/.MainActivity --es action verify_all
adb shell am start -n com.local.privswitch/.MainActivity --es action apply_all
adb shell am start -n com.local.privswitch/.MainActivity --es action revert_all
```

执行结果写入应用内运行日志。

## 已知限制

1. **iptables 规则重启后丢失**：重启后需重新点「应用全部」。
2. **只管理 user 0**：多用户 / 分身（如 user 10、128）中的同名包不受影响。
3. **域名规则全局生效**：`apigw`、`logservice`、`metrics`、`ads` 等域名可能被其它应用共用，阻断后可能影响它们的云端功能。
4. **系统管家组件禁用不可用**：`pm disable-user` 对 `com.hihonor.systemmanager` 组件返回 `not allowed to disable this package`，因此第 11 项改为阻断 `apigw-cn.yun.hihonor.com`。
5. **主动探测会产生一次短连接**（仅域名项），载荷为 `probe <host>`，不发送业务数据。
6. 无 root 时按钮禁用，仅显示状态。

## 免责声明

本项目仅用于**自有设备**上的隐私自查与研究，按现状提供、不附带任何担保。
使用 root、修改系统设置与防火墙规则存在风险（功能异常、系统不稳定等），请自行评估并承担后果。
请勿用于侵犯他人隐私或违反当地法律法规的用途。

## 更新记录

见 [CHANGELOG.md](CHANGELOG.md)。
