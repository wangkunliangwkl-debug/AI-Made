# 熄屏切4G / 亮屏切5G · Magisk 模块 v1.1

熄屏时把手机锁在 4G，亮屏 1 秒后恢复 5G。针对 vivo / iQOO（OriginOS）优化，其他品牌也能用。

**v1.1 重点修复了 v1.0「装了没效果」的问题，并加了完整日志。**

---

## 一、v1.0 为什么没效果（重要，请先看这段）

如果你之前装过 v1.0 发现没反应，原因有这几个，v1.1 全部改掉了：

| 问题 | v1.0 情况 | v1.1 处理 |
|---|---|---|
| **切网方式根本不对** | 只执行 `settings put global preferred_network_mode`。这只是改数据库里的一个值，**不会通知基带**。Android 11 以后真正生效必须走 `Phone.setPreferredNetworkType` → RIL | 改成四通道依次尝试：`cmd phone` → `service call phone` → `settings put` → `svc data` 重连，并且**切完回读实际网络类型验证** |
| **Toast 从来没有编译过** | 安装脚本用 `find` 找 `d8`，但手机系统里根本没有 `d8`（那是电脑上 build-tools 里的东西），所以 dex 从来没生成，Toast 必然失败 | 改用 `cmd notification post` 发系统通知，不依赖任何编译。另保留 toast 模式供折腾 |
| **没有验证环节** | 切完就完事，不管有没有生效 | 切完等 6 秒读 `gsm.network.type`，判断到底是不是 NR / LTE，失败自动重试一次，全过程写日志 |
| **日志看不到** | 写在 `/data/adb/modules/` 里，必须 root 文件管理器才能翻 | 写到 `/sdcard/Download/ScreenNetSwitch/`，任何文件管理器直接打开 |

---

## 二、安装

1. 下载 `ScreenNetSwitch-v1.1.zip`
2. Magisk / KernelSU / APatch → 模块 → 从本地安装 → 选这个 zip
3. **重启手机**（service.sh 是开机脚本，不重启不启动）

---

## 三、怎么看它有没有工作（关键）

重启后，用任意文件管理器打开：

```
Download/ScreenNetSwitch/
```

里面会有三个文件：

| 文件 | 内容 |
|---|---|
| `install.log` | 安装记录（机型、系统版本、安装时间） |
| `boot.log` | 开机启动过程（守护进程有没有拉起来、PID 多少） |
| `run.log` | **主日志**，每次亮灭屏的完整切换记录 |

正常工作的 `run.log` 长这样：

```
[2026-11-03 10:20:01] ============ 守护进程启动 v1.1 ============
[2026-11-03 10:20:01]   机型       : iQOO Neo7 SE
[2026-11-03 10:20:01]   Android    : 14 (SDK 34)
[2026-11-03 10:20:01]   当前 PNM   : 9
[2026-11-03 10:20:01]   当前网络   : NR_SA
[2026-11-03 10:20:01]   初始屏幕   : ON (power:mWakefulness=Awake)
[2026-11-03 10:20:01] ============ 进入主循环 =================
[2026-11-03 10:25:33] 状态变化: 1 -> 0  来源=power:mWakefulness=Asleep
[2026-11-03 10:25:33]   → 熄屏，切换到 4G (mode=11)
[2026-11-03 10:25:33] ------------------------------------------
[2026-11-03 10:25:33] == 开始切换 ==  目标 mode=11
[2026-11-03 10:25:33]   切换前: PNM=9  gsm.network.type=NR_SA
[2026-11-03 10:25:33]   [通道1] cmd phone set-preferred-network-type 11
[2026-11-03 10:25:33]     rc=0 out=<empty>
[2026-11-03 10:25:33]   [通道2] service call phone setPreferredNetworkType
[2026-11-03 10:25:33]     code=27 -> Result: Parcel(...)
[2026-11-03 10:25:33]   [通道3] settings put global preferred_network_mode 11
[2026-11-03 10:25:33]     写入后 PNM=11
[2026-11-03 10:25:33]   [通道4] svc data disable/enable 重连
[2026-11-03 10:25:34]   等待 6s 后验证实际网络...
[2026-11-03 10:25:40]   验证结果: PNM=11  gsm.network.type=LTE
[2026-11-03 10:25:40]   OK 已在 LTE
[2026-11-03 10:25:40] == 切换结束 ==
```

**重点看这三行**，它们直接决定功能是否真的生效：
- `切换前: ... gsm.network.type=xxx`
- `验证结果: ... gsm.network.type=xxx`
- `OK 已在 LTE` / `!! 仍在使用 NR，切 4G 未生效`

如果看到 `!!` 或 `re=1 out=...` 报错，说明这台机器的通道跟预期不一样，**把 run.log 发出来就能定位**。

---

## 四、手动命令

在终端（或 Magisk 模块页的「执行」按钮）里：

```bash
su -c 'sh /data/adb/modules/screen_net_switch/action.sh status'
```

| 命令 | 作用 |
|---|---|
| `status` | 看守护进程在不在、屏幕当前状态、当前网络 |
| **`diag`** | **完整诊断，出问题第一个跑这个**，输出全在 run.log |
| `log` | 直接看最近 60 行日志 |
| `test4g` | 手动切 4G，立刻验证（不用灭屏也能测） |
| `test5g` | 手动切 5G，立刻验证 |
| `testnotify` | 测试通知能不能弹出来 |
| `restart` | 重启守护进程（改完 config.sh 用） |
| `stop` | 停掉守护进程 |
| `clearlog` | 清空日志 |

**最快的自测方法**：

```bash
su -c 'sh /data/adb/modules/screen_net_switch/action.sh test4g'
```
然后等 10 秒，看状态栏是不是从 5G 掉到 4G。如果这一步都不生效，说明这台机器的切网通道需要换方案，日志里会写清哪条通道失败了。

---

## 五、配置

编辑 `/data/adb/modules/screen_net_switch/config.sh`，然后执行 `action.sh restart`。

```bash
MODE_5G=9          # 5G 模式值。联通/电信填 9，移动填 32
MODE_4G=11         # 4G 模式值（LTE Only）
POLL_INTERVAL=2    # 轮询间隔
SCREEN_ON_DELAY=1  # 亮屏后延迟几秒切 5G
TOAST_MODE=notification   # notification 或 toast
RECONNECT_DATA=true       # 切完要不要重连数据（v1.1 默认开，因为多数机器需要）
VERIFY_DELAY=6            # 切完等几秒验证
RETRY_ON_FAIL=true        # 没生效要不要重试
```

**MODE_5G 选哪个值**：如果默认的 `9` 切不回 5G，改成 `32`（移动卡）或 `26`。可以先用 `action.sh diag` 看 `ro.telephony.default_network` 的值作为参考。

---

## 六、已知限制

1. **系统设置的「启用5G」开关不会跟着变**。这个模块是从网络模式层面切，不碰那个 UI 开关。所以第三方 App 显示的状态可能和实际不符，以状态栏和 `gsm.network.type` 为准。

2. **切网需要几秒**。基带重新注册不是瞬时的，亮屏后大概 3~8 秒才能重新挂上 5G。这是物理限制，改不了。

3. **熄屏时如果有来电**，因为锁在 LTE Only，VoNR 用不了，会走 VoLTE 兜底。一般没问题，但如果你的运营商 VoLTE 覆盖差，可能出现接通延迟。

4. **省电收益有限**。熄屏锁 4G 确实比 5G 省一点，但主要是省基带功耗。如果你的目的是省电，效果可能没想象中大。

5. **部分机型可能依旧不生效**。如果 `action.sh diag` 显示所有通道都失败，那这台机器的电话服务有私有实现，只能走 Xposed hook `com.android.phone` 或 vivo 电话应用。这种情况需要额外做，把日志发我，我看能不能补。

---

## 七、卸载

Magisk 里移除模块即可。卸载脚本会自动把网络模式恢复到 5G（`MODE_5G` 的值），不会把你锁死在 4G。

日志目录会保留，需要的话自己删 `Download/ScreenNetSwitch/`。

---

## 八、反馈问题时请提供

1. `Download/ScreenNetSwitch/run.log`
2. `Download/ScreenNetSwitch/boot.log`
3. `action.sh diag` 执行后的 run.log 末尾部分

有这三个，基本能一眼看出问题在哪。