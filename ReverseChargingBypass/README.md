# 反向充电阈值解锁 (ReverseChargingBypass)

LSPosed 模块：解除 **ColorOS 无线反向充电「电量 ≥ 25%」限制**，改为可自定义档位（默认 **10%**）。

- 目标机型：一加 15 / ColorOS 16 / Android 16
- 模块类型：Xposed / LSPosed

---

## 一、先说结论：有没有现成的？

**没有。**

我检索了以下来源，均无对应实现：

| 来源 | 结果 |
|---|---|
| LSPosed 模块仓库 (modules.lsposed.org) | 无反向充电阈值相关模块 |
| GitHub `topics/lsposed` | 无 |
| 搜到的相关模块 | BatteryRemapper（电量显示映射）、Hypersc（超充欺骗）、ChargeProtectSetter（充电保护）—— **功能完全不同** |

因此本模块是**从零编写**。

---

## 二、实现思路

### 问题本质

ColorOS 在开启反向无线充电前，会做一次类似：

```java
if (batteryLevel < 25) {
    // 禁止开启 / 自动关闭
    return false;
}
```

这个 25 是**内置常量或配置**，散落在 system_server、设置 App、OPlus 电池管理等若干进程里，且**不同 ColorOS 版本位置会变**（所以网上没有通用模块）。

### 本模块的四层兜底策略

| 层 | 位置 | 做法 |
|---|---|---|
| **L1** 配置读取 | `Settings.Global` / `OplusFeatureConfigManager` / `SystemProperties` | hook `getInt` / `get`，把 key 含 reverse+level/battery 的 25 改成用户值 |
| **L2** 参数抬升 | 各类中名字含 `threshold`/`limit`/`reverse` 的方法 | hook 前把传入的「电量参数」从 10~24 抬高到 25，让原判断通过 |
| **L3** 精确类名 | `WirelessChargingService`、`BatteryService`、`OplusWirelessChargingService` 等 | 直接 hook 已知 getter / 判断方法 |
| **L4** UI 兜底 | `com.android.settings` 里的 PreferenceController | 强制 `isEnabled/isAvailable/canEnable` 返回 `true` |

> 因为拿不到 ColorOS 16 的实机反编译，**L2/L3 是通用扫描式**，类不存在时静默跳过，不会崩溃。实际命中位置需要看日志确认。

### 配置传递

模块设置界面和被 hook 的进程是**不同进程**，所以阈值通过 `Settings.Global` 的 `rcb_reverse_threshold` 传递，所有进程可读。

---

## 三、构建

### 环境要求
- Android Studio Hedgehog 以上 / JDK 17
- Android SDK 35

### 命令行构建

```bash
cd ReverseChargingBypass
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk`

> **签名**：Xposed 模块必须签名才能被 LSPosed 识别。
> ```bash
> apksigner sign --ks my.keystore app-release-unsigned.apk
> ```
> 或直接在 Android Studio 里 Build → Generate Signed Bundle/APK。

---

## 四、安装与激活

1. 安装 APK
2. 打开 **LSPosed** → 模块 → 启用「反向充电阈值解锁」
3. **作用域**勾选（重要，默认已配好）：
   - `android`（system_server，核心）
   - `com.android.settings`
   - `com.oplus.battery`
   - `com.oplus.athena`
   - `com.oplus.safecenter`
4. 重启手机（system_server 必须重启）

---

## 五、设置阈值（10% 档位）

**方式 A（有 root / adb）** — 最可靠：

```bash
settings put global rcb_reverse_threshold 10
```

**方式 B** — 打开模块 App 界面选 10%，点「应用设置」，若提示无权限，界面会给出 adb 命令。

改完 **重启一次** 生效。

---

## 六、验证是否生效

### 1. 看 LSPosed 日志

LSPosed 管理器 → 日志，过滤 `RCBypass`，正常应看到：

```
[RCBypass] Hooking system_server
[RCBypass] Found class: com.android.server.BatteryService
[RCBypass] Hooked threshold getter: ...
[RCBypass] boosted arg[0] in xxx#yyy: 15 -> 25
```

### 2. 实际测试

- 把电量放到 **10%~24%** 之间
- 打开 设置 → 电池 → 无线反向充电
- 应能正常开启（原系统此时会灰掉或自动关闭）

---

## 七、已知限制与风险

1. **类名不确定性**：ColorOS 16 我无法实测反编译，L3 精确 hook 的类名是**基于 ColorOS 15 及 AOSP 推测**。若日志里一个 `Found class` 都没有，说明类名全错，需要你提供实机 dump（见下方）。
2. **可能被 OTA 覆盖**：ColorOS 升级后类名/逻辑可能变，模块需同步更新。
3. **安全建议**：降到 10% 意味着**手机自身电量很低时还对外供电**，可能导致手机自己过放关机。建议不要设太低（10% 已是相对合理下限）。
4. **温度限制仍然存在**：系统对反向充电还有温度保护，本模块**不解除**温控，这是好事。
5. **不影响有线充电**。

---

## 八、如果没生效，请提供这些信息

我需要实机数据才能精准定位。在 root 下执行：

```bash
# 1. 找出反向充电相关类
adb shell "dumpsys battery | grep -i -E 'reverse|wireless'"

# 2. 查找系统里所有含 reverse 的类（需要 root）
adb shell "su -c 'find /system /system_ext /product -name \"*.jar\" | head -50'"

# 3. 关键：抓 system_server 的类列表
adb shell "su -c 'dumpsys activity service system' | grep -i reverse"

# 4. 操作反向充电开关时抓日志
adb logcat -s WirelessCharging:* ReverseCharging:* OplusBattery:* RCBypass:*
```

把第 4 条日志（点击开关时的输出）发我，就能确定真正的 hook 点，我再把 L3 的类名和方法名写成精确版。

---

## 九、工程结构

```
ReverseChargingBypass/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/xposed_init            # 入口: com.rcb.bypass.MainHook
        ├── java/com/rcb/bypass/
        │   ├── MainHook.kt               # 核心 hook（四层兜底）
        │   └── SettingsActivity.kt       # 档位选择界面
        └── res/values/
            ├── arrays.xml                # xposed 作用域
            └── strings.xml
```

---

## 十、免责声明

修改系统电池/供电行为有一定风险，包括但不限于：手机自身过放、系统不稳定、OTA 后功能异常。
**请自行承担风险**，建议先在低风险场景测试，重要数据提前备份。
