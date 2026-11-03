# 熄屏切4G / 亮屏切5G

Magisk 模块 · 针对 vivo/iQOO OriginOS 优化

## 功能

- 熄屏 → 把网络模式改成 LTE Only（等效关闭「启用5G」开关）
- 亮屏 → 等 1 秒 → 恢复 5G → 弹 Toast「已切换到5G网络」
- 带 30 秒防抖动，避免反复亮灭屏导致基带重注册
- 卸载自动还原，不会把你手机锁死在 4G

## 安装

1. 把整个 `ScreenNetSwitch` 目录打包成 zip（目录内的文件要在 zip 根，不要多一层文件夹）
   ```bash
   cd ScreenNetSwitch && zip -r ../ScreenNetSwitch-v1.0.zip . -x "*.git*"
   ```
2. Magisk → 模块 → 从本地安装 → 选这个 zip
3. 重启

## 首次使用必做：探测环境

重启后执行（需要 root）：

```bash
su -c 'sh /data/adb/modules/screen_net_switch/action.sh probe'
```

**重点看输出里的 `preferred_network_mode` 当前值**，以及有没有 vivo 私有键。

然后测试切换是否真的生效：

```bash
# 强制切 4G，观察状态栏是否掉到 4G
su -c 'sh /data/adb/modules/screen_net_switch/action.sh force-4g'

# 强制切 5G
su -c 'sh /data/adb/modules/screen_net_switch/action.sh force-5g'
```

**如果 force-4g 之后状态栏还是显示 5G**，说明你这台机器不认 PNM 写入，那就需要改方案（见下面「已知限制」）。

## 常用命令

```bash
su -c 'sh /data/adb/modules/screen_net_switch/action.sh status'      # 查看状态+日志
su -c 'sh /data/adb/modules/screen_net_switch/action.sh test-toast'  # 测试 Toast
su -c 'sh /data/adb/modules/screen_net_switch/action.sh reload'      # 改完配置后重载
su -c 'sh /data/adb/modules/screen_net_switch/action.sh stop'        # 临时停止
```

## 配置

改 `/data/adb/modules/screen_net_switch/config.sh`，然后 `action.sh reload`。

**最可能需要改的一项**：`MODE_5G`

| 你的卡 | MODE_5G 填 |
|---|---|
| 移动 | `32` |
| 联通 / 电信 | `9` 或 `26` |
| 广电 | `33` |

## 已知限制（重要，请务必读）

### 1. PNM 写入不一定立刻生效

`settings put global preferred_network_mode` 只是改了「偏好值」，真正让基带切过去需要 RIL 重新下发。部分 vivo 机型会：
- 立刻生效 ✅
- 忽略写入，等下次开关飞行模式才生效 ❌
- 被系统 UI 覆盖回去 ❌

如果 force-4g 无效，把 `config.sh` 里的 `RECONNECT_DATA` 改成 `true`，让模块用「关数据→开数据」强制触发重连。这会有 1~2 秒断网。

### 2. 设置里的「启用5G」开关不会跟着变

这个模块改的是底层网络模式，**不是**那个开关的 UI 状态。你可能看到：开关还显示「开」，但实际已经锁 4G 了。这是正常的，两者不同步。

如果你**真的**需要那个开关的视觉状态也跟着变，那得 hook vivo 的 Settings/Phone 应用，那是 Xposed 的活，不是 Magisk 的活。

### 3. Toast 可能弹不出来

`app_process` 弹 Toast 需要 dex 文件。如果你的 ROM 没有 `d8`/`dx`，安装时编译会失败。备选方案：

```bash
# 手动用 Termux 编译（如果装了 Termux）
pkg install openjdk-17
d8 Toast.java --output out/
```

或者最简单的替代：把 `config.sh` 里 `TOAST_ENABLE=false`，改用通知栏提示（我可以再给你加）。

### 4. 频繁切网的副作用

每次切网 = 基带重新注册网络。频繁切会导致：
- 熄屏期间来电可能延迟（如果熄屏锁了 LTE Only，VoNR 用不了，VoLTE 正常的话还好）
- 耗电反而可能增加（重注册比待机 5G 更耗电）

**说句实话**：这个功能实际省电收益很小，甚至可能是负的。如果你是想省电，更有效的做法是熄屏关 5G SA 保持 NSA，或者干脆别切。但既然你要，我按你的要求做出来了。

## 文件结构

```
ScreenNetSwitch/
├── module.prop          # 模块信息
├── config.sh            # 用户配置（改这个）
├── customize.sh         # 安装脚本
├── service.sh           # 开机启动守护
├── screen_watch.sh      # 核心逻辑
├── action.sh            # 手动管理脚本
├── uninstall.sh         # 卸载还原
└── toast/Toast.java     # Toast 组件
```