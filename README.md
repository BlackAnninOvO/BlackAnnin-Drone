# BlackAnnin's Drone / BlackAnnin的无人机

一个为 Minecraft **1.21.1（NeoForge 21.1.25x）** 打造的航拍无人机模组：放飞一架无人机，让它跟随你飞行、替你探路、并且**把它的第一人称画面实时推流到 OBS**。

Aerial drone mod for Minecraft **1.21.1 (NeoForge 21.1.25x)**: deploy a drone that follows you, navigates around obstacles on its own, and **streams its first-person view straight into OBS**.

[中文说明](#中文说明) · [English](#english)

---

# 中文说明

## 简介

**BlackAnnin的无人机** 添加了一架可收回的航拍无人机。放飞后它会悬浮跟随着你飞行，能自主绕过障碍、穿过门洞与矮通道找到你；想亲自掌舵时，掏出**无人机遥控器**（或按下 J 键），用键盘和鼠标像操作真实穿越机一样**丝滑地**驾驶它，遥测面板上罗盘、人工地平仪、高度与速度一目了然。它的摄像头画面以**原版渲染管线**渲染，并通过 **Spout2** 实时发送给 OBS —— 直播、录航拍镜头，都随你。

模组 ID：`blackannin_drone` ｜ 版本：`0.5.0-1.21.1` ｜ 协议：MIT

## 功能特性

### 无人机基础

- **放置与回收**：手持「航拍无人机」右键放置；**潜行 + 右键点击无人机**，或使用控制面板的「回收无人机」按钮即可收回（服务端校验归属后返还物品）。
- **悬停飞行**：无人机无重力、无 AI，始终悬浮；碰撞箱与模型严格对齐。
- **朝向**：自动跟随模式下镜头朝你面向的方向；手动操控时镜头平滑转向移动方向（悬停时保持朝向，不会乱转）。
- **完全无敌**：不受燃烧、窒息、摔落、爆炸、怪物攻击等任何伤害，也不会被怪物当作攻击目标；**只有玩家主动回收或 `/kill` 指令能移除它**。不可推动、不会被距离卸载；玩家离线时才掉落为物品。

### 跟随与飞行

- **距离与高度可调**：默认跟随在你身后 3 格、上方 1 格，均可在配置中调整。
- **速度联动**：无人机速度随你的移动速度提升（含创造模式飞行），可设置联动系数与最高速度上限，高速移动不再掉队。
- **平滑跟随**：水平方向缓动跟随；垂直方向在你**落地时始终跟随高度**（跑酷逐格上升也能跟上），空中只有真正的大幅升降（飞行、坠落）才跟随，**跑跳与原地连跳都不会让其上下浮动**；移动加减速同样做了平滑，避免顿挫。
- **爬行跟随**：当你钻活板门、爬 1 格高通道时，跟随点会切换为「贴身且与你同高」，无人机从**同一个洞口**钻进来跟随，而不是在外面绕飞。
- **强制传送兜底**：与你的距离超过阈值（默认 16 格）时立即传送到你身边；**你死亡时它原地等待**，复活后自动跟来；**跨维度**（下界/末地/传送）同样自动跟随。

### 智能寻路

这是本模组投入最多的部分，无人机不会傻傻地撞墙：

- **视线直飞**：与目标之间视线通畅时直接飞过去，带速度平滑。
- **全局 A\* 寻路**：看不到目标时，以 **1 格为粒度**做 A\* 搜索（26 邻域、按无人机真实碰撞箱逐格判定通行、禁止斜向切角），因此能**穿过 1 格宽的门洞、活板门下的矮口、竖井**，也能**在封闭房间外壁找到唯一的入口**。寻路终点使用玩家自身位置（跟随点常落在墙内，会导致搜索失败）。每 10 tick 重新规划一次。
- **局部绕障**：需要绕行时**固定一侧**沿障碍前进（避免左右反复切换导致原地打转）；候选方向包含**纯上升/下降**与该侧 0°~180°（15° 步长）的**水平与斜向**移动，统一按「这一步走完后离你最近」评分 → 每一步都取**当前可行的最短绕路**。左右都不行时会尝试上下与斜向。
- **可通过的方块**：水、打开的门与活板门（无碰撞）直接通行；**遇到关闭的木门 / 活板门 / 栅栏门会自动打开并通过**，继续跟随；**铁门这类没有钥匙也打不开的方块，无人机会直接接入电路控制把它打开**——穿过去之后自动还原，就像一台真正的电子设备那样。
- **卡住自愈**：若一段时间内净位移过小（原地打转）且离你较远，会先**换侧改道**；再一段时间仍无进展则**立即传送**到你身边。所有方向都被堵死时同样立即传送。

### FPV 视角与 OBS 推流

- **通过 Spout2 输出**到 OBS：先安装 [obs-spout2-plugin](https://github.com/Off-World-Live/obs-spout2-plugin)，再在 OBS 中添加 **Spout2 捕获**源，选择发送器 `BlackAnninDrone` 即可。
- **原版渲染管线**：无人机视角由原版管线（天空、光照、雾、手部裁剪）渲染到独立帧缓冲，**玩家自己的画面与 HUD 完全不受影响**，两者互不干扰。
- **分辨率实时生效**：输出分辨率默认 **1920×1080**，在配置中修改后**下一帧立即生效**，无需重启游戏。
- **独立视场角**：无人机视角默认 **70**（我的世界默认值），不随你的视场角或疾跑变化；可用控制面板的滑块在 30~110 之间实时调节。
- **排障开关**：开启 `spoutDebugDump` 后，每次开始推流会把**实际发送给 OBS 的那一帧**导出为游戏目录下的 `drone_stream_debug.png`，便于判断问题出在模组侧还是 OBS 侧。

### 无人机遥控器与操控台（v0.5.0 重制）

- **无人机遥控器**：新增物品（创造物品栏可取）。手持**右键**即可打开操控台；默认按键 `J` 也能打开；还可以用指令 `/droneui` 直接呼出。物品采用 BlockBench 制作的 3D 模型，手持、物品栏与掉落物展示姿态均已调好。
- **手动接管制**：打开面板默认处于"观察"状态（无人机继续自动跟随），点击**「手动操纵」**按钮或按 `K` 才会接管；接管后 `WASD` 三轴连续移动、`空格`/`左Shift` 升降——按住就持续飞行，加减速平滑，撞墙自动贴墙滑行。「手动操纵」与**「恢复跟随」**（或 `R`）是两个独立按钮、按状态互相点亮，交还控制权后鼠标怎么动都不会误接管。手动模式活动半径约 15 格，靠近边界会被柔性拉回。
- **两种视角模式**（面板切换或按 `V`）：**鼠标直接控制**——接管后自动锁定光标，移动鼠标即可**无限旋转**视角，完全不受窗口边界限制；此时摇杆不再显示，面板底部的提示会变成「`V` 解锁鼠标（切换为摇杆视角）」，配合 `R` 恢复跟随、`X` 回收即可完成全部操作；**摇杆渐进控制**——拖动面板左下角的摇杆，视角以角速度缓慢跟随、松手自动回中（下拉为低头），光标正常显示可拖动 UI。摇杆灵敏度在面板内实时拖动调节（0.5~12）。
- **航测风格操控台**：顶部 LINK 连接状态灯；滚动罗盘刻度带（N/E/S/W）；**人工地平仪**（分割线随俯仰滚动）；右侧遥测中英双语实时刷新——模式 MODE、高度 ALT、距离 DIST、水平速度 H.SPD、垂直速度 V.SPD、航向 HDG、坐标 POS。
- **「重置视角」按钮（或按 `C`）**：把无人机视角对准你本人面向的方向、俯仰归零，飞迷糊时一键回正；点击**「恢复跟随」时也会自动初始化视角**，手动模式遗留的朝向不会带进跟随视角。
- **`X` 回收无人机**：收回并返还物品；`ESC` 关闭面板后无人机会原地悬停待命。
- **快捷回收**：潜行时右键点击无人机本体也可收回。
- **管理员指令**：`/dronespawn` 在准星方向生成一台已认主的无人机（需要权限等级 2，快速测试很方便）。

## 配置项

**通用配置（`config/blackannin_drone-common.toml`）**

| 配置项 | 默认 | 说明 |
|---|---|---|
| `droneFollowDistance` | 3.0 | 跟随距离（格） |
| `droneFollowHeight` | 1.0 | 跟随高度（格） |
| `droneTakeoffDelay` | 40 | 放置后起飞延迟（tick） |
| `droneSpeedFactor` | 2.5 | 玩家速度对无人机速度的影响系数（0 = 不受影响） |
| `droneMaxSpeed` | 3.0 | 无人机最高飞行速度（格/tick） |
| `droneManualSpeed` | 0.5 | 手动操控模式的飞行速度上限（格/tick，可调 0.1~2.0；默认 0.5 约合 10 格/秒，想要更快就调大） |
| `droneManualRadius` | 64.0 | 手动操控模式的活动半径（格），可调上限 **128**；独立于强制传送距离 |
| `droneTeleportDistance` | 16.0 | 超过该距离强制传送回玩家身边（格）；**仅自动跟随模式生效**，手动模式由活动半径柔性限制 |

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
- 使用推流功能需要：**Windows** 系统 + OBS 的 **[Spout2 插件（obs-spout2-plugin）](https://github.com/Off-World-Live/obs-spout2-plugin)**（`SpoutLibrary.dll` 会随模组自动释放到游戏目录，也可在配置中指定路径）
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

**BlackAnnin's Drone** adds a retrievable aerial drone to Minecraft. Once deployed it hovers and follows you, navigates around obstacles, squeezes through doorways and crawl spaces to reach you. And when you feel like taking the stick yourself, pull out the **Drone Remote** (or press `J`) and fly it with your keyboard and mouse, as smooth as a real FPV quad — compass, artificial horizon, altitude and speed all live on the telemetry panel. Its camera feed is rendered through the **vanilla render pipeline** and streamed to OBS in real time over **Spout2** — great for live streams and cinematic aerial shots.

Mod ID: `blackannin_drone` · Version: `0.5.0-1.21.1` · License: MIT

## Features

### The Drone

- **Deploy & retrieve**: right-click with the *Aerial Drone* item to deploy. **Sneak + right-click the drone**, or use the **"Retrieve Drone"** button in the control panel, to stow it again (ownership is verified server-side before the item is returned).
- **Hovering flight**: no gravity, no AI, always floating; the hitbox is precisely aligned with the model.
- **Heading**: in follow mode the camera faces where you face; in manual control it turns smoothly toward its movement direction (it holds its heading while hovering, so the view never spins).
- **Fully invulnerable**: immune to fire, suffocation, fall, explosion and mob damage, and mobs never target it — **only you can retrieve it, or the `/kill` command can remove it**. It cannot be pushed and is never despawned by distance; it only drops as an item when its owner logs off.

### Following & Flight

- **Tunable distance and height**: 3 blocks behind and 1 block above you by default, both configurable.
- **Speed scales with you**: the drone speeds up with your movement (creative flight included) with configurable factor and top speed, so it no longer falls behind.
- **Smooth following**: eased horizontal tracking; it always follows your height **while you are on the ground** (so block-by-block parkour climbs are matched), follows only real altitude changes while airborne (flight, falling), and **ignores the jump arc entirely** — so neither running jumps nor in-place bunny hopping make the view bob. Acceleration is smoothed as well.
- **Crawl-aware**: when you crawl through a trapdoor or a one-block-high tunnel, the follow point switches to "hugging you at your own height", so the drone **comes in through the very same opening** instead of flying around outside.
- **Teleport fallbacks**: instantly teleports to you beyond the configured distance (16 blocks by default); **waits in place when you die** and follows again after you respawn; follows you **across dimensions**.

### Smart Pathfinding

This is where most of the work went — the drone will not just bump into walls:

- **Line-of-sight flight**: when the path to the target is clear it simply flies there (velocity-smoothed).
- **Global A\* pathfinding**: when the target cannot be seen, it runs an A\* search on a **1-block grid** (26-neighbourhood, passability tested against the drone's real collision box, diagonal corner-cutting forbidden). This lets it **pass through one-block-wide doorways, gaps under trapdoors and vertical shafts**, and **find the single entrance on the outer wall of a sealed room**. The search goal is your own position (the follow point often sits inside a wall, which would make the search fail). Re-planned every 10 ticks.
- **Local obstacle avoidance**: while blocked it commits to a **fixed side** and follows the obstacle (no left/right flip-flopping that would make it spin in place). Candidates include **straight up and down** plus horizontal and diagonal moves at 0°–180° in 15° steps, all scored by "distance to you after this step" — so every step takes the **shortest currently passable detour**. If left and right both fail, up/down and diagonals are tried as well.
- **Passable blocks**: water and opened doors/trapdoors have no collision and are flown straight through; **closed wooden doors, trapdoors and fence gates are opened automatically** so it can keep following; and blocks you could never open by hand — **iron doors — are wired open like a proper electronic device**: the drone opens them, flies through, and restores them on the way out.
- **Self-recovery when stuck**: if it barely moves (circling) while still far from you, it **switches sides** first; if there is still no progress after another moment, it **teleports to you immediately**. When every direction is blocked it teleports right away too.

### FPV View & OBS Streaming

- **Spout2 output** to OBS: install [obs-spout2-plugin](https://github.com/Off-World-Live/obs-spout2-plugin), then add a **Spout2 Capture** source in OBS and pick the sender `BlackAnninDrone`.
- **Vanilla render pipeline**: the drone's view is rendered with the vanilla pipeline (sky, lighting, fog, hand suppression) into its own framebuffer, so **your own view and HUD are completely unaffected** — the two never interfere.
- **Live resolution**: output defaults to **1920×1080** and changes apply **on the very next frame**, no game restart needed.
- **Independent FOV**: the drone view defaults to **70** (the Minecraft default) and is unaffected by your own FOV or sprinting. Adjustable live between 30 and 110 with the slider in the control panel.
- **Debug option**: enable `spoutDebugDump` to export the **exact frame sent to OBS** as `drone_stream_debug.png` in the game directory — ideal for telling whether a problem lies in the mod or in OBS.

### Drone Remote & Console (reworked in v0.5.0)

- **Drone Remote**: a new item (also in the creative inventory). **Right-click** it to open the drone console; the `J` key (configurable) does the same, and so does the `/droneui` command. The item uses a 3D BlockBench model, with display poses tuned for hand, inventory, ground and frames.
- **Take-control gating**: the console opens in an observe state (the drone keeps following automatically); only after clicking **Take Control** or pressing `K` do the controls respond — `WASD` for continuous three-axis movement, `Space`/`Left Shift` to climb or descend. Hold a key and it **keeps flying**, with smoothed acceleration and deceleration; bumping into a wall makes it slide along it. Click **Reset Follow** or press `R` to hand control back for good — moving the mouse afterwards will never re-take it. Manual range is about 15 blocks, softly pulled back near the boundary.
- **Two view modes** (switch on the panel or with `V`): **Mouse** — the cursor is locked once you take control, so the mouse rotates the view endlessly with no window-edge limit; the joystick is hidden and the hint line becomes "`V` unlock the mouse (switch to stick view)", with `R` to reset follow and `X` to retrieve. **Stick** — drag the joystick in the lower-left corner; the view follows its deflection at an adjustable rate and re-centers on release (pull down to pitch down), and the cursor stays visible for the UI. Stick sensitivity is a live slider (0.5–12).
- **Avionics-style console**: a `LINK` status lamp on top; a scrolling compass ribbon (N/E/S/W); an **artificial horizon** whose split line rolls with pitch; and bilingual live telemetry on the right — MODE, ALT, DIST, H.SPD, V.SPD, HDG and POS.
- **Reset View** button (or press `C`): snaps the drone's view to the direction you are facing with a level pitch — one tap to re-orient when you lose your bearings; clicking **Reset Follow** also initializes the view, so the heading left over from manual flight never carries into follow mode.
- **`X` retrieves** the drone and returns the item; after closing with `ESC` the drone hovers in place, standing by.
- **Quick retrieve**: sneak + right-click the drone itself.
- **Admin command**: `/dronespawn` spawns an owned drone a few blocks ahead (permission level 2 — handy for quick testing).

## Configuration

**Common (`config/blackannin_drone-common.toml`)**

| Option | Default | Description |
|---|---|---|
| `droneFollowDistance` | 3.0 | Follow distance (blocks) |
| `droneFollowHeight` | 1.0 | Follow height (blocks) |
| `droneTakeoffDelay` | 40 | Delay before takeoff after placement (ticks) |
| `droneSpeedFactor` | 2.5 | How much the owner's speed affects the drone (0 = unaffected) |
| `droneMaxSpeed` | 3.0 | Maximum flight speed (blocks/tick) |
| `droneManualSpeed` | 0.5 | Manual-mode top speed (blocks/tick, tunable 0.1–2.0; the 0.5 default is about 10 blocks/s — raise it if you want more punch) |
| `droneManualRadius` | 64.0 | Manual-mode operating radius (blocks), up to **128**; independent of the teleport distance |
| `droneTeleportDistance` | 16.0 | Teleport back to the owner beyond this distance (blocks); **follow mode only** — manual mode is softly bounded by its own radius |

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
- For streaming: **Windows** and the **[Spout2 plugin for OBS (obs-spout2-plugin)](https://github.com/Off-World-Live/obs-spout2-plugin)** (`SpoutLibrary.dll` is extracted into the game directory automatically; a custom path can be set in the config)
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
