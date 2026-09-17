# BlackAnnin's Drone / BlackAnnin的无人机

一个为 Minecraft **1.21.1（NeoForge 21.1.25x）** 打造的航拍无人机模组：放飞一架无人机，让它跟随你飞行、替你探路、并且**把它的第一人称画面实时推流到 OBS**。

Aerial drone mod for Minecraft **1.21.1 (NeoForge 21.1.25x)**: deploy a drone that follows you, navigates around obstacles on its own, and **streams its first-person view straight into OBS**.

[中文说明](#中文说明) · [English](#english)

---

# 中文说明

## 简介

**BlackAnnin的无人机** 添加了一架可收回的航拍无人机。放飞后它会悬浮跟随着你飞行，能自主绕过障碍、穿过门洞与矮通道找到你；同时它搭载的摄像头画面会以**原版渲染管线**渲染，并通过 **Spout2** 实时发送给 OBS —— 适合直播、录制航拍镜头。

模组 ID：`blackannin_drone` ｜ 版本：`0.3.0-1.21.1` ｜ 协议：MIT

## 功能特性

### 无人机基础

- **放置与回收**：手持「航拍无人机」右键放置；**潜行 + 右键点击无人机**，或使用控制面板的「回收无人机」按钮即可收回（服务端校验归属后返还物品）。
- **悬停飞行**：无人机无重力、无 AI，始终悬浮；碰撞箱与模型严格对齐。
- **朝向**：自动跟随模式下镜头朝你面向的方向；手动操控时镜头平滑转向移动方向（悬停时保持朝向，不会乱转）。
- **掉落保护**：不受摔落伤害、不可推动、不会被距离卸载；玩家离线时才掉落为物品。

### 跟随与飞行

- **距离与高度可调**：默认跟随在你身后 3 格、上方 1 格，均可在配置中调整。
- **速度联动**：无人机速度随你的移动速度提升（含创造模式飞行），可设置联动系数与最高速度上限，高速移动不再掉队。
- **平滑跟随**：水平方向缓动跟随；垂直方向在你**落地时始终跟随高度**（跑酷逐格上升也能跟上），空中只有大幅升降（飞行、坠落）才跟随，**平地原地连跳不会让其上下浮动**；移动加减速同样做了平滑，避免顿挫。
- **爬行跟随**：当你钻活板门、爬 1 格高通道时，跟随点会切换为「贴身且与你同高」，无人机从**同一个洞口**钻进来跟随，而不是在外面绕飞。
- **强制传送兜底**：与你的距离超过阈值（默认 16 格）时立即传送到你身边；**你死亡时它原地等待**，复活后自动跟来；**跨维度**（下界/末地/传送）同样自动跟随。

### 智能寻路

这是本模组投入最多的部分，无人机不会傻傻地撞墙：

- **视线直飞**：与目标之间视线通畅时直接飞过去，带速度平滑。
- **全局 A\* 寻路**：看不到目标时，以 **1 格为粒度**做 A\* 搜索（26 邻域、按无人机真实碰撞箱逐格判定通行、禁止斜向切角），因此能**穿过 1 格宽的门洞、活板门下的矮口、竖井**，也能**在封闭房间外壁找到唯一的入口**。寻路终点使用玩家自身位置（跟随点常落在墙内，会导致搜索失败）。每 10 tick 重新规划一次。
- **局部绕障**：需要绕行时**固定一侧**沿障碍前进（避免左右反复切换导致原地打转）；候选方向包含**纯上升/下降**与该侧 0°~180°（15° 步长）的**水平与斜向**移动，统一按「这一步走完后离你最近」评分 → 每一步都取**当前可行的最短绕路**。左右都不行时会尝试上下与斜向。
- **可通过的方块**：水、打开的门与活板门（无碰撞）直接通行；关着的门视为障碍并绕开。
- **卡住自愈**：若一段时间内净位移过小（原地打转）且离你较远，会先**换侧改道**；再一段时间仍无进展则**立即传送**到你身边。所有方向都被堵死时同样立即传送。

### FPV 视角与 OBS 推流

- **通过 Spout2 输出**到 OBS：在 OBS 中添加 **Spout2 捕获**源，选择发送器 `BlackAnninDrone` 即可。
- **原版渲染管线**：无人机视角由原版管线（天空、光照、雾、手部裁剪）渲染到独立帧缓冲，**玩家自己的画面与 HUD 完全不受影响**，两者互不干扰。
- **分辨率实时生效**：输出分辨率默认 **1920×1080**，在配置中修改后**下一帧立即生效**，无需重启游戏。
- **独立视场角**：无人机视角默认 **70**（我的世界默认值），不随你的视场角或疾跑变化；可用控制面板的滑块在 30~110 之间实时调节。
- **排障开关**：开启 `spoutDebugDump` 后，每次开始推流会把**实际发送给 OBS 的那一帧**导出为游戏目录下的 `drone_stream_debug.png`，便于判断问题出在模组侧还是 OBS 侧。

### 控制面板与按键

- **按键**：默认 `J` 打开「无人机控制面板」（可在按键设置中修改）。
- **面板内容**：
  - 方向操控：向前 / 向后 / 向左 / 向右 / 升高 / 降低（每次移动 1 格，限制在最大活动半径内）
  - **恢复跟随**：结束手动操控，回到自动跟随
  - **回收无人机**：立即收回并返还物品
  - **无人机视场角滑块**：实时调节 FPV 视场角
- **快捷回收**：潜行时右键点击无人机本体也可收回。

## 配置项

**通用配置（`config/blackannin_drone-common.toml`）**

| 配置项 | 默认 | 说明 |
|---|---|---|
| `droneFollowDistance` | 3.0 | 跟随距离（格） |
| `droneFollowHeight` | 1.0 | 跟随高度（格） |
| `droneTakeoffDelay` | 40 | 放置后起飞延迟（tick） |
| `droneMaxRadius` | 5.0 | 手动操控时的最大活动半径（格） |
| `droneSpeedFactor` | 2.5 | 玩家速度对无人机速度的影响系数（0 = 不受影响） |
| `droneMaxSpeed` | 3.0 | 无人机最高飞行速度（格/tick） |
| `droneTeleportDistance` | 16.0 | 超过该距离强制传送回玩家身边（格） |

**客户端配置（`config/blackannin_drone-client.toml`）**

| 配置项 | 默认 | 说明 |
|---|---|---|
| `spoutEnabled` | true | 启用 FPV 画面与 Spout 输出 |
| `spoutSenderName` | BlackAnninDrone | OBS 中显示的发送器名称 |
| `spoutWidth` / `spoutHeight` | 1920 / 1080 | 输出分辨率（改动下一帧生效） |
| `spoutLibraryPath` | 空 | SpoutLibrary.dll 路径，留空自动搜索 |
| `droneFov` | 70.0 | 无人机视角视场角（独立于玩家设置） |
| `spoutDebugDump` | false | 排障：导出实际发送的帧为 PNG |

## 安装要求

- Minecraft **1.21.1** + **NeoForge 21.1.25x**
- 使用推流功能需要：**Windows** 系统 + OBS 的 **Spout2 插件**（`SpoutLibrary.dll` 可随模组自动释放，也可在配置中指定路径）
- 仅玩游戏不推流时无需任何额外依赖（可在客户端配置中关闭 `spoutEnabled`）

## 已知限制

- **光影 / Sodium**：无人机视角需要「一帧内再渲染一次世界」，这与 Iris/Sodium 的渲染管线存在冲突，光影或 Sodium 环境下**无人机画面可能渲染异常**（玩家自身画面不受影响）。当前版本针对**原版渲染**做了完整验证，建议不装光影或 Sodium 时使用推流功能。
- **OBS 端**：Spout 源会缓存上一次收到的纹理，改动分辨率或发送器后若画面不更新，请在 OBS 中删除该源并重新添加。

## 从源码构建

```bash
./gradlew build
# 产物：build/libs/blackannin_drone-<版本>.jar
```

开发环境运行客户端：`./gradlew runClient`

---

# English

## Introduction

**BlackAnnin's Drone** adds a retrievable aerial drone to Minecraft. Once deployed it hovers and follows you, navigates around obstacles, squeezes through doorways and crawl spaces to reach you, and renders its camera feed through the **vanilla render pipeline** while streaming it to OBS in real time over **Spout2** — perfect for live streams and cinematic aerial shots.

Mod ID: `blackannin_drone` · Version: `0.3.0-1.21.1` · License: MIT

## Features

### The Drone

- **Deploy & retrieve**: right-click with the *Aerial Drone* item to deploy. **Sneak + right-click the drone**, or use the **"Retrieve Drone"** button in the control panel, to stow it again (ownership is verified server-side before the item is returned).
- **Hovering flight**: no gravity, no AI, always floating; the hitbox is precisely aligned with the model.
- **Heading**: in follow mode the camera faces where you face; in manual control it turns smoothly toward its movement direction (it holds its heading while hovering, so the view never spins).
- **Protected**: no fall damage, not pushable, never despawned by distance; it only drops as an item when its owner logs off.

### Following & Flight

- **Tunable distance and height**: 3 blocks behind and 1 block above you by default, both configurable.
- **Speed scales with you**: the drone speeds up with your movement (creative flight included) with configurable factor and top speed, so it no longer falls behind.
- **Smooth following**: eased horizontal tracking; it always follows your height **while you are on the ground** (so block-by-block parkour climbs are matched), follows only large altitude changes while airborne (flight, falling), and **ignores in-place bunny hopping** so the view never bobs. Acceleration is smoothed as well.
- **Crawl-aware**: when you crawl through a trapdoor or a one-block-high tunnel, the follow point switches to "hugging you at your own height", so the drone **comes in through the very same opening** instead of flying around outside.
- **Teleport fallbacks**: instantly teleports to you beyond the configured distance (16 blocks by default); **waits in place when you die** and follows again after you respawn; follows you **across dimensions**.

### Smart Pathfinding

This is where most of the work went — the drone will not just bump into walls:

- **Line-of-sight flight**: when the path to the target is clear it simply flies there (velocity-smoothed).
- **Global A\* pathfinding**: when the target cannot be seen, it runs an A\* search on a **1-block grid** (26-neighbourhood, passability tested against the drone's real collision box, diagonal corner-cutting forbidden). This lets it **pass through one-block-wide doorways, gaps under trapdoors and vertical shafts**, and **find the single entrance on the outer wall of a sealed room**. The search goal is your own position (the follow point often sits inside a wall, which would make the search fail). Re-planned every 10 ticks.
- **Local obstacle avoidance**: while blocked it commits to a **fixed side** and follows the obstacle (no left/right flip-flopping that would make it spin in place). Candidates include **straight up and down** plus horizontal and diagonal moves at 0°–180° in 15° steps, all scored by "distance to you after this step" — so every step takes the **shortest currently passable detour**. If left and right both fail, up/down and diagonals are tried as well.
- **Passable blocks**: water and opened doors/trapdoors have no collision and are flown straight through; closed doors count as obstacles and are routed around.
- **Self-recovery when stuck**: if it barely moves (circling) while still far from you, it **switches sides** first; if there is still no progress after another moment, it **teleports to you immediately**. When every direction is blocked it teleports right away too.

### FPV View & OBS Streaming

- **Spout2 output** to OBS: add a **Spout2 Capture** source in OBS and pick the sender `BlackAnninDrone`.
- **Vanilla render pipeline**: the drone's view is rendered with the vanilla pipeline (sky, lighting, fog, hand suppression) into its own framebuffer, so **your own view and HUD are completely unaffected** — the two never interfere.
- **Live resolution**: output defaults to **1920×1080** and changes apply **on the very next frame**, no game restart needed.
- **Independent FOV**: the drone view defaults to **70** (the Minecraft default) and is unaffected by your own FOV or sprinting. Adjustable live between 30 and 110 with the slider in the control panel.
- **Debug option**: enable `spoutDebugDump` to export the **exact frame sent to OBS** as `drone_stream_debug.png` in the game directory — ideal for telling whether a problem lies in the mod or in OBS.

### Controls

- **Key binding**: `J` (configurable) opens the **Drone Control Panel**.
- **Panel**:
  - Movement: forward / backward / left / right / up / down (1 block per press, clamped to the maximum radius)
  - **Reset Follow**: leave manual control and resume automatic following
  - **Retrieve Drone**: stow the drone and get the item back
  - **Drone FOV slider**: adjust the first-person field of view live
- **Quick retrieve**: sneak + right-click the drone itself.

## Configuration

**Common (`config/blackannin_drone-common.toml`)**

| Option | Default | Description |
|---|---|---|
| `droneFollowDistance` | 3.0 | Follow distance (blocks) |
| `droneFollowHeight` | 1.0 | Follow height (blocks) |
| `droneTakeoffDelay` | 40 | Delay before takeoff after placement (ticks) |
| `droneMaxRadius` | 5.0 | Maximum radius from the owner in manual control (blocks) |
| `droneSpeedFactor` | 2.5 | How much the owner's speed affects the drone (0 = unaffected) |
| `droneMaxSpeed` | 3.0 | Maximum flight speed (blocks/tick) |
| `droneTeleportDistance` | 16.0 | Teleport back to the owner beyond this distance (blocks) |

**Client (`config/blackannin_drone-client.toml`)**

| Option | Default | Description |
|---|---|---|
| `spoutEnabled` | true | Enable the FPV view and Spout output |
| `spoutSenderName` | BlackAnninDrone | Sender name shown in OBS |
| `spoutWidth` / `spoutHeight` | 1920 / 1080 | Output resolution (applied on the next frame) |
| `spoutLibraryPath` | empty | Path to SpoutLibrary.dll; empty = auto-detect |
| `droneFov` | 70.0 | Drone view FOV (independent of the player setting) |
| `spoutDebugDump` | false | Debug: write the actual sent frame to a PNG |

## Requirements

- Minecraft **1.21.1** + **NeoForge 21.1.25x**
- For streaming: **Windows** and the **Spout2 plugin** for OBS (`SpoutLibrary.dll` is extracted automatically; a custom path can be set in the config)
- No extra dependency for normal play — simply disable `spoutEnabled` in the client config if you do not stream

## Known Limitations

- **Shaders / Sodium**: the drone view requires rendering the world a second time per frame, which conflicts with the Iris/Sodium pipeline — **the drone feed may render incorrectly with shaders or Sodium installed** (your own view is unaffected). This release is fully validated on **vanilla rendering**; use the streaming feature without shaders or Sodium.
- **OBS side**: a Spout source caches the last texture it received. If the picture does not update after changing the resolution or sender, remove the source in OBS and add it again.

## Building from Source

```bash
./gradlew build
# Output: build/libs/blackannin_drone-<version>.jar
```

Run the client in a dev environment with `./gradlew runClient`.
