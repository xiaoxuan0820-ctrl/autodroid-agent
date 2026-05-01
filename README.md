# 🦞 CiCi — 旧手机是最便宜的 AI 硬件

[![Build CiCi APK](https://github.com/xiaoxuan0820-ctrl/AegisForge-cici/actions/workflows/build-cici.yml/badge.svg)](https://github.com/xiaoxuan0820-ctrl/AegisForge-cici/actions/workflows/build-cici.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

> 一台旧手机 + 一个开源 APK + 一行 LLM Key = 24 小时在线的私人 AI 管家。**零元成本。**

**CiCi** 将闲置安卓手机变成 AI 助手。通过无障碍服务操控屏幕，用钉钉/飞书/QQ/Discord/Telegram 任一通道远程指挥。自动打卡、定时签到、消息秒回 — 把吃灰的硬件变成最便宜的生产力。

## ✨ 核心能力

| 功能 | 说明 |
|------|------|
| 🧠 **AI 驱动** | 接 LLM (DeepSeek/OpenAI/Anthropic)，自然语言控制手机 |
| 📱 **无障碍操控** | 基于 Android AccessibilityService，免 Root |
| 💬 **多通道** | 钉钉、飞书、QQ、Discord、Telegram — 拿哪个聊天都能控制 |
| ⏰ **定时任务** | 多时段 + 工作日 + 自动重试，把打卡签到自动化 |
| 🛠 **零代码技能** | 内置技能市场，描述需求就能装新技能 |
| 🎭 **人格切换** | 极客助手 / 办公搭子 / 创作达人 / 效率管家，一键切 |
| 🧠 **记忆系统** | 记住你的偏好和习惯，越用越懂你 |
| 🌐 **Web 配置** | 电脑浏览器直接配，不用在手机上打字 |
| 🔄 **自动亮屏/息屏** | 定时任务触发时自动亮屏，完成后息屏省电 |

## 🏗 架构

```
你的消息（钉钉/飞书/QQ/Discord/Telegram）
              ↓
         CiCi APK（闲置手机）
              ↓
    ChannelManager（消息接收）
              ↓
      AgentService（LLM 处理）
              ↓
    AccessibilityService（无障碍操控）
              ↓
         屏幕点击 / 滑动 / 输入
              ↓
         结果截图 → 回传消息通道
```

```
手机上的 CIci
├── 消息通道    → 钉钉 / 飞书 / QQ / Discord / Telegram
├── AI 引擎     → LLM (DeepSeek / OpenAI / Anthropic)
├── 无障碍服务  → 获取屏幕内容 + 点击/滑动/输入
├── 调度系统    → 定时任务 + 多时段 + 重试
├── 记忆系统    → 偏好/账号/习惯持久化存储
├── 技能市场    → 零代码安装自动化技能
├── 人格系统    → 4 种工作风格一键切换
├── Web 配置页  → :9527 端口局域网配置
└── 屏幕管理    → 定时自动亮屏/息屏
```

## 🚀 快速开始

### 1. 下载安装

从 [GitHub Releases](https://github.com/xiaoxuan0820-ctrl/AegisForge-cici/releases) 下载最新 `cici-debug.apk`，安装到闲置 Android 手机。

> 需要 Android 8.0+ (API ≥ 26)

### 2. 开启无障碍服务

设置 → 辅助功能 → 已安装的服务 → CiCi → 开启

### 3. 配置通道

手机和电脑在同一 WiFi，电脑浏览器打开 `http://手机IP:9527`，填写 LLM Key 和消息通道凭证。

推荐配置流程：
```
1. LLM → 填 DeepSeek API Key (便宜好用)
2. 选择消息通道 → 填钉钉/飞书 Bot 凭证
3. 选人格 → 看你喜欢哪种风格
4. 装技能 → 一键安装「钉钉打卡」「淘宝签到」等
```

### 4. 开始使用

在消息通道里发消息：
```
查询今天天气
打开抖音刷视频
每天早上8点提醒我打卡
帮我搜一下iPhone17价格
```

## 🛠 内置技能

| 技能 | 触发 | 说明 |
|------|------|------|
| 📰 每日资讯摘要 | 每天早晨 | 推送热点新闻 |
| ⏰ 钉钉打卡 | 工作日 9:00, 18:00 | 自动打开钉钉打卡 |
| 🛒 淘宝签到 | 每天 8:00 | 领取淘金币 |
| 🛍 京东签到 | 每天 8:30 | 领取京豆 |
| 📺 B站签到 | 每天 10:00 | 领取B币 |
| 🎵 网易云签到 | 每天 10:00 | 领取积分 |

## 🎭 四种人格

| 人格 | 风格 | 适合场景 |
|------|------|----------|
| 🤖 极客助手 | 全能型 | 任何任务，默认模式 |
| 💼 办公搭子 | 文档/邮件/日程 | 办公场景 |
| 🎨 创作达人 | 文案/社交发布 | 内容创作 |
| ⚡ 效率管家 | 打卡签到/提醒 | 自动化任务 |

## 🧠 记忆系统

CiCi 会主动记住你的信息，下次对话自动衔接上下文：

- **用户信息** — 名字、工号、喜好...
- **偏好** — 喜欢什么风格、习惯用法...
- **应用上下文** — 各 App 的常用功能、账号...
- **事实** — 学到的重要信息...

记忆可在 Web 配置页搜索和删除。

## 📡 API 接口

Web 配置页面运行在 `9527` 端口，同时提供 REST API：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/llm` | GET/POST | LLM 配置 |
| `/api/channels` | GET/POST | 通道凭证配置 |
| `/api/persona` | GET/POST | 人格查看/切换 |
| `/api/skills` | GET | 已安装技能列表 |
| `/api/skills/builtin` | GET | 内置技能列表 |
| `/api/skills/install` | POST | 安装内置技能 |
| `/api/skills/delete` | POST | 删除技能 |
| `/api/skills/toggle` | POST | 启用/停用技能 |
| `/api/memory` | GET/POST | 查看/保存记忆 |
| `/api/memory/delete` | POST | 删除记忆 |
| `/api/debug/*` | GET/POST | 调试工具（仅 DEBUG 构建） |

## 📊 竞品对比

CiCi 并非孤岛。手机 AI 自动化赛道正在快速发展，以下是当前主要竞品的横向对比：

### 核心能力对比

| 工具 | 类型 | AI 驱动 | 视觉操控 | 免 Root | 多消息通道 | 定时任务 | 技能市场 | 记忆系统 | 跨设备 |
|------|------|:-------:|:--------:|:-------:|:----------:|:--------:|:--------:|:--------:|:------:|
| **🦞 CiCi** | 手机 APK | ✅ LLM | ✅ Accessibility | ✅ | ✅ 5 通道 | ✅ 多时段+重试 | ✅ 零代码安装 | ✅ 分类记忆 | ❌ |
| **AppAgent** (腾讯) | PC 框架 | ✅ GPT-4V | ✅ 截图+ADB | ✅ | ❌ | ❌ | ❌ | ❌ | PC→安卓 |
| **Xiaomi miclaw** | 系统 Agent | ✅ MiMo | ✅ 系统级 | 系统内置 | ❌ | ✅ | ✅ | ✅ | ✅ 小米全生态 |
| **AutoGLM** (智谱) | 手机 Agent | ✅ GLM | ✅ 截图+ADB | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Claude Orbit** | 云 Agent | ✅ Claude | ✅ 截图 | 🚧 | ❌ | ❌ | ❌ | ✅ | 手机→电脑 |
| **MaaFramework** | 开发框架 | 🚧 MCP | ✅ CV | ✅ | ❌ | ✅ | ✅ 插件 | ❌ | Win/Mac/Linux/安卓 |
| **Tasker** | 自动化 App | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ 插件 | ❌ | ❌ |
| **Auto.js** | JS 脚本引擎 | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ |
| **MacroDroid** | 自动化 App | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ 模板 | ❌ | ❌ |

### 各竞品特点

#### AppAgent（腾讯）
LLM 驱动的多模态 GUI Agent，用 GPT-4V 识别屏幕截图，通过 ADB 控制手机。学术开源项目（CHI 2025）。
> **CiCi 优势**: 真机 APK 常驻运行，多消息通道远程指挥，不必连电脑

#### Xiaomi miclaw（"龙虾"）
小米 HyperOS 3 系统级 AI Agent，基于 MiMo 大模型，可控制 10 亿+ 米家 IoT 设备。2026 年 3 月封测。
> **CiCi 优势**: 不限品牌型号，旧手机也能用，Android 8.0+ 全覆盖
> **可借鉴**: 跨设备协同、IoT 控制、MCP 开放 SDK

#### AutoGLM（智谱 × CogAgent）
CVPR 2024 Highlight，1120×1120 高分辨率 GUI 理解，Plan-Do-Check-Act 闭环执行。
> **CiCi 优势**: 常驻服务 + 定时任务 + 记忆系统，真正 7×24 在线
> **可借鉴**: **PDCA 执行闭环** — 操作后截图验证，失败自动修正

#### Claude Orbit（Anthropic）
Phone-to-Desktop 远程操控，Dispatch 跨设备对话，Computer Use 桌面 Agent。2026 年 3 月发布。**Orbit** 移动端控制研发中。
> **CiCi 优势**: 本地运行无云端依赖，隐私数据不出设备，零成本
> **可借鉴**: **跨设备调度** — 手机收指令，电脑执行重型任务

#### MaaFramework
通用视觉自动化框架，OpenCV + ADB 控制。MaaMCP 暴露手机能力为 MCP Server。
> **CiCi 优势**: 开箱即用 APK，无需编程，零配置上手
> **可借鉴**: **视觉 fallback** — 无障碍服务失效时切图像识别、**MCP Server** — 暴露手机能力给其他 AI

#### Tasker / Auto.js / MacroDroid
传统 Android 自动化工具，规则/脚本驱动，无 AI 能力。
> **CiCi 优势**: AI 自然语言交互，理解复杂意图，越用越聪明
> **可借鉴**: **Tasker 的传感器触发** — WiFi/位置/充电状态触发自动化

### CiCi 的独特定位

```
多消息通道(5)  +  LLM驱动  +  定时任务  +  记忆系统  +  零成本旧手机
——————————————————————————————————————————————————————————
         = 唯一「7×24 在线的私人 AI 管家」开源方案
```

CiCi 是当前市面上唯一同时具备以下全部特质的开源方案：
- ✅ **真机常驻** — APK 安装即用，不是 PC 框架
- ✅ **多通道远程指挥** — 钉钉/飞书/QQ/Discord/Telegram，拿手机就能控手机
- ✅ **LLM 驱动** — 自然语言操控，不需要写规则或脚本
- ✅ **定时自动化** — 自动打卡、签到、提醒
- ✅ **记忆系统** — 记住你的偏好和习惯
- ✅ **零成本** — 旧手机 + 免费 LLM（DeepSeek）就能跑

### 路线图

受竞品启发，计划中的增强方向：

- [ ] **PDCA 执行闭环** — 操作后截图验证，失败自动重试/修正（借鉴 AutoGLM）
- [ ] **Web 实时屏幕预览** — 浏览器远程看手机画面（借鉴 MaaFramework）
- [ ] **视觉操控 fallback** — 无障碍服务受限时切换 ADB + 图像识别
- [ ] **跨设备调度** — 手机收指令，联动电脑执行（借鉴 Claude Dispatch）
- [ ] **IoT 控制接入** — 支持 Home Assistant / 米家（借鉴 Xiaomi miclaw）
- [ ] **MCP Server** — 将手机能力暴露为 MCP 协议，供其他 AI Agent 调用

---

## 🔧 开发

### 构建 APK

推送代码到 master，GitHub Actions 自动构建。或者本地构建：

```bash
# 克隆上游
git clone https://github.com/xiaoxuan0820-ctrl/hermes-agent
cd hermes-agent/android

# 应用 18 个 patch
cp -r /path/to/AegisForge/patches/hermes-agent/src/main/java/com/apk/claw/android/**/*.kt \
   app/src/main/java/com/apk/claw/android/

# 构建
./gradlew assembleDebug
```

### 项目结构

```
project/
├── .github/workflows/       # CI 自动构建
├── patches/hermes-agent/    # 18 个增强补丁
│   ├── agent/               # Agent 核心（系统提示词、人格）
│   ├── scheduler/           # 调度增强（多时段、工作日、重试）
│   ├── memory/              # 记忆系统
│   ├── skill/               # 零代码技能市场
│   ├── tool/                # 工具增强
│   ├── server/              # Web 配置服务器
│   └── assets/web/          # 配置页面 HTML
├── scripts/                 # 图标生成等工具
├── im/                      # IM 接收器（飞书）
└── requirements.txt
```

### 补丁说明

18 个文件覆盖了上游 [hermes-agent/android](https://github.com/xiaoxuan0820-ctrl/hermes-agent) 的对应文件，主要增强：

| # | 文件 | 增强内容 |
|---|------|----------|
| 1 | `AgentConfig.kt` | phone-master 系统提示词 + 记忆/技能/人格规则 |
| 2 | `DefaultAgentService.kt` | 记忆上下文注入 + 人格后缀 + 自动模板学习 |
| 3 | `OkHttpClientBuilderAdapter.java` | DeepSeek thinking mode 适配 |
| 4 | `ScreenManager.kt` | 自动亮屏/息屏 |
| 5 | `TaskScheduler.kt` | 多时段 + 工作日 + 自动重试 3 次 |
| 6 | `ScheduledTaskReceiver.kt` | 定时任务广播接收器 |
| 7 | `TaskExecutionLogger.kt` | 任务执行日志记录 |
| 8 | `ScheduledTaskTemplates.kt` | 8 个预设定时任务模板 |
| 9 | `MemoryManager.kt` | 分类记忆存储与上下文注入 |
| 10 | `MemoryTools.kt` | save_memory / recall_memory 工具 |
| 11 | `ToolRegistry.kt` | 注册所有增强工具 |
| 12 | `ScheduledTaskTools.kt` | list/cancel 定时任务工具 |
| 13 | `TaskOrchestrator.kt` | 任务协调 + 结果回写 |
| 14 | `AppViewModel.kt` | 定时任务 UI 状态管理 |
| 15 | `SkillManager.kt` | 零代码技能 CRUD + 内置市场 |
| 16 | `SkillTools.kt` | create/install/delete 技能工具 |
| 17 | `PersonaConfig.kt` | 4 种人格枚举 + 切换逻辑 |
| 18 | `ConfigServer.kt` | Web UI + 全量 REST API |

## 🤝 贡献

欢迎提 Issue 和 PR！详见 [CONTRIBUTING.md](CONTRIBUTING.md)

## 📄 License

Apache 2.0 © 2025 CiCi Contributors

---

<p align="center">
  <sub>Built with 🦞 by the CiCi community</sub>
</p>
