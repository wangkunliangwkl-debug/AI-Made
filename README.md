# AI-Made（AI自造）

一个由 AI 辅助从零编写、打包、签名的 LSPosed/Xposed 模块 —— **反向充电阈值解锁**（ReverseChargingBypass）。

## 项目说明

解除 **ColorOS 无线反向充电「电量 ≥ 25%」限制**，改为可自定义档位。

- 目标机型：一加 15 / ColorOS 16 / Android 16
- 模块类型：Xposed / LSPosed
- 最新版本：v3.6

## 目录结构

| 目录 | 说明 |
|---|---|
| `ReverseChargingBypass/` | 完整 Gradle 源码工程（含详细 README.md） |
| `src/` | 最新 UI 源码（面板、旋钮、竖排开关、磁贴服务） |
| `tools/` | 构建与逆向辅助脚本 |
| `releases/` | 已签名 APK 产物 |

## 快速开始

详见 [`ReverseChargingBypass/README.md`](ReverseChargingBypass/README.md)。

已签名 APK：[`releases/ReverseChargingBypass-v3.6.apk`](releases/ReverseChargingBypass-v3.6.apk)

## 免责声明

修改系统电池/供电行为有一定风险，请自行评估并承担后果。
