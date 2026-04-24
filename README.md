# VideoShowCase

[English](#english) | [中文](#中文)

---

<a id="english"></a>
## English

### 1. Project Overview

VideoShowCase is a **Rayneo X3 Pro Smart Glasses → Relay Phone → Cloud Live Streaming** solution based on Wi-Fi Direct. It offloads the resource-intensive encoding, relaying, and uploading tasks from the constrained camera device (**Rayneo X3 Pro** glasses) to a more powerful relay device (phone).

#### 1.1 Module Structure

| Module    | Device Role              | Description                                                                  |
|-----------|--------------------------|------------------------------------------------------------------------------|
| **glass** | Device A (Camera Side)   | **Rayneo X3 Pro** smart glasses — captures and sends audio/video             |
| **app**   | Device B (Relay Side)    | Phone — receives, plays locally, and pushes stream to cloud                  |

#### 1.2 Data Flow Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Device A (glass)                                               │
│  Camera2 → MediaCodec H.264 → RTP → TCP (optional AAC on same) │
│  (If audio enabled) Mic → MediaCodec AAC (ADTS) → same TCP     │
└─────────────────────────────────────────────────────────────────┘
                                    │
                              Wi-Fi Direct
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│  Device B (app)                                                 │
│  Socket Recv → Demux by frame type (Video RTP / AAC)            │
│       ├→ MediaCodec Decode → SurfaceView (local playback)       │
│       └→ RootEncoder RtmpClient → RTMP (video-only or H.264+AAC)│
└─────────────────────────────────────────────────────────────────┘
```

---

### 2. Technical Implementation

#### 2.1 Wi-Fi Direct Role Assignment

- **Device A (glass)**: Client — discovers and connects to the relay
- **Device B (app)**: Group Owner — creates P2P group, runs `ServerSocket` waiting for connection

After connection, the Group Owner address is obtained via `WifiP2pManager.ConnectionInfoListener` for Socket communication.

#### 2.2 Device A (glass) Implementation

| Component | Class                   | Description                                                           |
|-----------|-------------------------|-----------------------------------------------------------------------|
| Capture   | `CameraCapture`         | Camera2 API, `ImageReader` for YUV420                                 |
| Encode    | `VideoEncoder`          | MediaCodec hardware H.264 encoding                                   |
| Packetize | `RtpPacketizer`         | RFC 3984, Single NAL & FU-A fragmentation; 1-byte frame type prefix   |
| Send      | `VideoStreamingService` | `BufferedOutputStream` (64KB) over TCP; `sendAllowed` gating          |
| Audio     | `AudioEncoder`          | Created only when `audioEnabled = true`; `AudioRecord` PCM → AAC-LC (ADTS), 64 kbps |

**Transport Protocol** (`StreamProtocol`):

1. **Stream Header (24 bytes)**: Magic `VSCH` (4) + width + height + fps + audio sample rate + audio channels (each 4-byte big-endian int)
2. **Each frame**: **1 byte type** + **4 bytes big-endian length** + **payload**
   - `PACKET_TYPE_VIDEO_RTP` (`0x01`): H.264 RTP packet
   - `PACKET_TYPE_AAC_ADTS` (`0x02`): AAC ADTS frame

**Audio capture decision**: After confirming frame rate in `FrameRateSelectActivity`, a dialog (`FDialog`) asks the user to choose "Enable audio" or "Video only". The result is passed via `StreamingActivity.EXTRA_AUDIO_ENABLED` (`boolean`).

#### 2.3 Device B (app) Implementation

| Component | Class              | Description                                                            |
|-----------|--------------------|------------------------------------------------------------------------|
| Receive   | `StreamReceiver`   | Reads 24-byte header; parses 5-byte frame headers (type + length + payload) |
| Parse     | `RtpParser`        | Parses RTP headers, reassembles H.264 Annex B (incl. FU-A)            |
| Decode    | `VideoDecoder`     | MediaCodec hardware decode to `SurfaceView`                           |
| Push      | `RtmpPushModule`   | RootEncoder `RtmpClient`; audio track determined by stream header     |

**RTMP audio handling**: If audio sample rate ≤ 0 in stream header, `setOnlyVideo(true)` is used and `setAudioInfo` is skipped. Otherwise both video and audio tracks are active.

#### 2.4 Cloud Streaming (RootEncoder)

- **Library**: `com.github.pedroSG94.RootEncoder:library:2.6.1`
- **With audio**: `setOnlyVideo(false)` → `setAudioInfo(…)` → `connect`
- **Video only**: `setOnlyVideo(true)` → `connect`
- **First frame requirement**: Must be IDR keyframe for most players to decode

---

### 3. Key Files

#### 3.1 glass Module

| File                                        | Responsibility                                                    |
|---------------------------------------------|-------------------------------------------------------------------|
| `MainActivity.kt`                           | Device discovery, connection; permission requests                 |
| `streaming/ResolutionSelectActivity.kt`     | Resolution selection → navigate to FrameRateSelectActivity        |
| `streaming/FrameRateSelectActivity.kt`      | Frame rate selection; audio option dialog                         |
| `streaming/StreamingActivity.kt`            | Reads `EXTRA_AUDIO_ENABLED`, starts/stops streaming               |
| `streaming/VideoStreamingService.kt`        | Camera, H.264, optional AAC, RTP, TCP                            |
| `camera/CameraCapture.kt`                   | Camera2 capture, YUV to NV12                                     |
| `encoder/VideoEncoder.kt`                   | H.264 encoding                                                   |
| `encoder/AudioEncoder.kt`                   | AAC encoding (64 kbps)                                            |
| `rtp/RtpPacketizer.kt`                      | RTP packetization with frame type prefix                          |
| `streaming/StreamProtocol.kt`               | Protocol constants                                                |
| `wifi/WifiDirectClient.kt`                  | Wi-Fi Direct client                                               |

#### 3.2 app Module

| File                          | Responsibility                                                     |
|-------------------------------|--------------------------------------------------------------------|
| `MainActivity.kt`             | Creates group, starts stream receiver on connection                |
| `streaming/StreamReceiver.kt` | Stream header parsing, TCP frame loop, demux, decode               |
| `streaming/StreamProtocol.kt` | Protocol constants (same as glass)                                 |
| `decoder/VideoDecoder.kt`     | H.264 decode                                                      |
| `rtp/RtpParser.kt`            | RTP parsing, FU-A reassembly                                      |
| `streaming/RtmpPushModule.kt` | RTMP push with audio/video-only modes                             |
| `wifi/WifiDirectServer.kt`    | Group Owner, `ServerSocket` listener                               |

---

### 4. Operation Guide

#### 4.1 Prerequisites

- Two Android devices: one with **glass** app, one with **app**
- **glass** requires: Camera, Microphone, Location, Wi-Fi Direct permissions
- **app** requires: Location and (Android 13+) Nearby Devices permissions
- Recommended: Create group on relay first, then connect from camera side

#### 4.2 Steps

**Step 1: Relay (app) — Create Group**
1. Open **app**
2. Tap **"Create Wi-Fi Direct Group"**
3. Status shows **"Group created. Waiting for camera device..."**

**Step 2: Camera (glass) — Connect & Configure**
1. Open **glass**
2. Tap **"Discover devices"**
3. Select the relay device from the list
4. Choose **resolution** (up to 1920×1080, sorted by pixel count)
5. Choose **frame rate** (30/25/20/15/10/5/1 fps, default 30)
6. Choose **audio option**: "Enable audio" or "Video only (no microphone capture)"

**Step 3: Camera Starts Streaming to Relay**
1. In **StreamingActivity**, tap **"Start streaming"**
2. Status shows **"Streaming (audio + video)"** or **"Streaming (video only)"**

**Step 4: Relay Receives & Plays Locally**
1. **app** displays video on **SurfaceView**
2. Shows resolution, frame rate, and audio info from stream header

**Step 5: Relay Pushes to Cloud**
1. Enter RTMP URL
2. Tap **"Start cloud streaming"**
3. Status: **"Connecting to RTMP server..."** → **"Streaming to cloud"**

**Stop Streaming**
- Cloud: Tap **"Stop streaming"** on relay
- LAN: Tap **"Stop streaming"** on glass StreamingActivity

#### 4.3 Notes

1. **Streaming order**: Start camera streaming a few seconds before cloud push to cache SPS/PPS
2. **Permissions**: Denying required permissions may cause discovery/connection failures
3. **Background**: Relay going to background may release the receiver; camera needs to reconnect
4. **Push failures**: Check RTMP URL, network, and platform requirements

---

### 5. Performance & Troubleshooting

#### 5.1 Sources of Frame Drops

| Type                 | Description                                                               |
|----------------------|---------------------------------------------------------------------------|
| **Send path contention** | Video RTP and AAC share the same TCP with synchronized output stream  |
| **Flush strategy**   | `BufferedOutputStream(64KB)`; video flushes after encoder output; audio flushes every 8 frames |
| **System load**      | Dual MediaCodec, AudioRecord, Camera, network combined                    |

#### 5.2 Troubleshooting Steps

1. **Network vs Device**: Test at close range to rule out Wi-Fi Direct interference
2. **Reduce load**: Lower resolution/frame rate, or select "Video only"
3. **Video-only comparison**: If smoother without audio, the bottleneck is audio-video parallelism
4. **Relay vs Cloud**: If SurfaceView is laggy, check glass→phone; if only RTMP is laggy, check uplink
5. **Disconnection**: `Broken pipe` when remote closes first; handled via `IOException` catch

#### 5.3 Protocol Compatibility

- Stream header is **24 bytes**; incompatible with old 16-byte video-only version
- **glass and app must be the same version**

---

### 6. Dependencies

| Module | Dependency                                | Purpose                          |
|--------|-------------------------------------------|----------------------------------|
| app    | RootEncoder `library:2.6.1` (JitPack)    | RTMP streaming                   |
| glass  | MercurySDK (`glass/libs`)                | Touchpad control, UI, `FDialog`  |

**Repository**: JitPack — `https://jitpack.io`

---

### 7. FAQ

**Q: Relay doesn't show video?**
A: Ensure glasses have tapped "Start streaming" in StreamingActivity and Wi-Fi Direct is connected.

**Q: Cloud push succeeds but pull stream shows no video?**
A: Let LAN streaming stabilize for a few seconds first; check SPS/PPS/IDR keyframe. If the platform requires an audio track, select "Enable audio" on glasses.

**Q: How to get an RTMP URL?**
A: From your live streaming platform or self-hosted Nginx-RTMP.

**Q: Where to change resolution, frame rate, audio?**
A: Resolution/frame rate on the glasses selection pages; audio toggle via the FDialog after frame rate confirmation. App-side info comes from the 24-byte stream header.

**Q: Video is choppy?**
A: See Section 5; try lowering resolution/frame rate or selecting "Video only".

---

### 8. Appendix: Protocol Constants & Intent Conventions

#### 8.1 StreamProtocol (TCP Stream Header & Frame Types)

| Constant                      | Type / Value    | Description                           |
|-------------------------------|-----------------|---------------------------------------|
| `HEADER_MAGIC`                | `"VSCH"`        | Stream header magic (ASCII, 4 bytes)  |
| `HEADER_SIZE`                 | `24`            | Total header length (bytes)           |
| `PACKET_TYPE_VIDEO_RTP`       | `0x01` (Byte)   | Payload is H.264 RTP packet           |
| `PACKET_TYPE_AAC_ADTS`        | `0x02` (Byte)   | Payload is AAC ADTS frame             |
| `FRAME_HEADER_SIZE`           | `5`             | 1-byte type + 4-byte big-endian length|
| `DEFAULT_AUDIO_SAMPLE_RATE`   | `44100`         | Default audio sample rate (Hz)        |
| `DEFAULT_AUDIO_CHANNELS`      | `1`             | Default audio channel count           |

**Header layout (24 bytes, big-endian int except magic)**: `HEADER_MAGIC(4)` → **width** → **height** → **fps** → **audioSampleRate** → **audioChannels**. Video-only: last two fields are **0**.

#### 8.2 glass Intent Extra Keys

| Activity                   | Constant              | Key String          | Type    | Description                     |
|----------------------------|-----------------------|---------------------|---------|---------------------------------|
| `ResolutionSelectActivity` | `EXTRA_HOST`          | `"host"`            | String  | Relay Group Owner IP            |
| `FrameRateSelectActivity`  | `EXTRA_HOST`          | `"host"`            | String  | Same as above                   |
|                            | `EXTRA_WIDTH`         | `"width"`           | Int     | Capture width (px)              |
|                            | `EXTRA_HEIGHT`        | `"height"`          | Int     | Capture height (px)             |
| `StreamingActivity`        | `EXTRA_HOST`          | `"host"`            | String  | Same as above                   |
|                            | `EXTRA_WIDTH`         | `"width"`           | Int     | Same as above                   |
|                            | `EXTRA_HEIGHT`        | `"height"`          | Int     | Same as above                   |
|                            | `EXTRA_FPS`           | `"fps"`             | Int     | Frame rate (1–120)              |
|                            | `EXTRA_AUDIO_ENABLED` | `"audio_enabled"`   | Boolean | Whether to capture mic AAC      |

#### 8.3 Tunable Parameters (Current Implementation)

| Location                    | Parameter                        | Value                               |
|-----------------------------|----------------------------------|-------------------------------------|
| `VideoStreamingService`     | `AUDIO_FLUSH_EVERY_FRAMES`       | 8                                   |
|                             | `BufferedOutputStream` buffer    | 64 × 1024 bytes                     |
|                             | `Socket.sendBufferSize`          | `coerceAtLeast(256 * 1024)`         |
| `VideoEncoder`              | Default bitrate                  | 2,000,000 (2 Mbps)                  |
|                             | `I_FRAME_INTERVAL`               | 1 second                            |
| `AudioEncoder`              | `KEY_BIT_RATE`                   | 64,000 (64 kbps AAC)                |
| `StreamReceiver`            | `BUFFER_SIZE`                    | 256 × 1024                          |
|                             | `MAX_PACKET_BYTES`               | 2 × 1024 × 1024                     |

---
---

<a id="中文"></a>
## 中文

### 一、项目概述

VideoShowCase 是一个基于 Wi-Fi Direct 的 **Rayneo X3 Pro 智能眼镜 → 中继手机 → 云端直播**方案。将资源受限的摄像头设备（**Rayneo X3 Pro** 眼镜端）与耗电耗性能的编码、转发、上传任务分离，由性能更强的中继设备（手机）承担。

#### 1.1 模块划分

| 模块        | 设备角色       | 说明                                          |
|-----------|------------|---------------------------------------------|
| **glass** | 设备 A（摄像头端） | **Rayneo X3 Pro** 智能眼镜，负责采集与发送音视频流           |
| **app**   | 设备 B（中继端）  | 手机，负责接收、本地播放、云端推流                           |

#### 1.2 数据流架构

```
┌─────────────────────────────────────────────────────────────────┐
│  设备 A (glass)                                                  │
│  Camera2 → MediaCodec H.264 → RTP → TCP（与可选 AAC 同连接复用）   │
│  （若用户选择开启音频）麦克风 → MediaCodec AAC(ADTS) → 同上 TCP      │
└─────────────────────────────────────────────────────────────────┘
                                    │
                              Wi-Fi Direct
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│  设备 B (app)                                                    │
│  Socket 接收 → 按帧类型分流（视频 RTP / AAC）                        │
│       ├→ MediaCodec 解码 → SurfaceView 本地播放                   │
│       └→ RootEncoder RtmpClient → RTMP（纯视频或 H.264+AAC）        │
└─────────────────────────────────────────────────────────────────┘
```

---

### 二、技术实现思路

#### 2.1 Wi-Fi Direct 角色分配

- **设备 A（glass）**：Client，发现并连接中继端
- **设备 B（app）**：Group Owner，创建 P2P 组，运行 `ServerSocket` 等待连接

连接成功后，通过 `WifiP2pManager.ConnectionInfoListener` 获取 `groupOwnerAddress`，作为 Socket 连接的 IP。

#### 2.2 设备 A（glass）实现

| 环节 | 实现                      | 说明                                                                   |
|----|-------------------------|----------------------------------------------------------------------|
| 采集 | `CameraCapture`         | Camera2 API，`ImageReader` 采集 YUV420                                  |
| 编码 | `VideoEncoder`          | MediaCodec 硬件 H.264 编码                                               |
| 封装 | `RtpPacketizer`         | RFC 3984，支持 Single NAL 与 FU-A 分片；每包前带 1 字节帧类型                        |
| 发送 | `VideoStreamingService` | `BufferedOutputStream`（默认 64KB）包装 TCP、`sendAllowed` 门控               |
| 音频 | `AudioEncoder`          | 仅当 `audioEnabled = true` 且编码器启动成功时创建；`AudioRecord` PCM → AAC-LC，64 kbps |

**传输格式**（`StreamProtocol`）：

1. **流头 24 字节**：魔数 `VSCH`（4） + 宽 + 高 + 帧率 + 音频采样率 + 声道数（各 4 字节大端 int）
2. **之后每帧**：**1 字节类型** + **4 字节大端长度** + **负载**
   - `PACKET_TYPE_VIDEO_RTP`（`0x01`）：负载为完整 H.264 RTP 包
   - `PACKET_TYPE_AAC_ADTS`（`0x02`）：负载为 AAC ADTS 帧

**是否采集音频的决策时机**：在 `FrameRateSelectActivity` 中用户确认帧率后，弹出 `FDialog` 选择"开启音频"或"仅视频"。选择结果通过 `StreamingActivity.EXTRA_AUDIO_ENABLED`（`boolean`）传入。

#### 2.3 设备 B（app）实现

| 环节 | 实现               | 说明                                                      |
|----|------------------|---------------------------------------------------------|
| 接收 | `StreamReceiver` | 先读 24 字节流头；再按 5 字节帧头解析「类型 + 长度 + 负载」，分流视频 RTP 与 AAC     |
| 解析 | `RtpParser`      | 解析 RTP 头，还原 H.264 Annex B（含 FU-A 重组）                    |
| 解码 | `VideoDecoder`   | MediaCodec 硬件解码，输出到 `SurfaceView`                       |
| 推流 | `RtmpPushModule` | RootEncoder `RtmpClient`；是否带音频轨由流头决定                     |

**RTMP 音频处理**：若流头中音频采样率 ≤ 0，则 `setOnlyVideo(true)`，不调用 `setAudioInfo`。否则视频和音频轨道同时激活。

#### 2.4 云端推流（RootEncoder）

- **库**：`com.github.pedroSG94.RootEncoder:library:2.6.1`
- **有音频**：`setOnlyVideo(false)` → `setAudioInfo(…)` → `connect`
- **纯视频**：`setOnlyVideo(true)` → `connect`
- **首帧要求**：首帧应为 IDR 关键帧，否则多数播放端无法解码

---

### 三、关键文件说明

#### 3.1 glass 模块

| 文件                                          | 职责                                    |
|---------------------------------------------|---------------------------------------|
| `MainActivity.kt`                           | 设备发现、连接；统一权限申请                        |
| `streaming/ResolutionSelectActivity.kt`     | 选择分辨率后跳转帧率页                           |
| `streaming/FrameRateSelectActivity.kt`      | 选择帧率；音频选项对话框                          |
| `streaming/StreamingActivity.kt`            | 读取 `EXTRA_AUDIO_ENABLED`，开始/停止推流      |
| `streaming/VideoStreamingService.kt`        | Camera、H.264、可选 AAC、RTP、TCP           |
| `camera/CameraCapture.kt`                   | Camera2 采集，YUV 转 NV12                 |
| `encoder/VideoEncoder.kt`                   | H.264 编码                              |
| `encoder/AudioEncoder.kt`                   | AAC 编码（64 kbps）                       |
| `rtp/RtpPacketizer.kt`                      | RTP 封装，含帧类型前缀                         |
| `streaming/StreamProtocol.kt`               | 协议常量                                  |
| `wifi/WifiDirectClient.kt`                  | Wi-Fi Direct 客户端                      |

#### 3.2 app 模块

| 文件                            | 职责                                    |
|-------------------------------|---------------------------------------|
| `MainActivity.kt`             | 创建组、连接后启动流接收器                         |
| `streaming/StreamReceiver.kt` | 流头解析、TCP 帧循环、分流、解码                    |
| `streaming/StreamProtocol.kt` | 协议常量（与 glass 一致）                      |
| `decoder/VideoDecoder.kt`     | H.264 解码                              |
| `rtp/RtpParser.kt`            | RTP 解析、FU-A 重组                        |
| `streaming/RtmpPushModule.kt` | RTMP 推流，支持音频/纯视频模式                    |
| `wifi/WifiDirectServer.kt`    | Group Owner，`ServerSocket` 监听         |

---

### 四、操作说明

#### 4.1 前置条件

- 两台 Android 设备：分别安装 **glass**、**app**
- **glass**：需 相机、麦克风、位置、Wi-Fi Direct 相关权限
- **app**：需 位置及 Android 13+ 附近设备权限（不采集麦克风）
- 建议：先在中继端创建组，再在摄像头端连接

#### 4.2 操作步骤

**第一步：中继端（app）创建组**
1. 打开 **app**
2. 点击 **「Create Wi-Fi Direct Group」**
3. 状态显示 **「Group created. Waiting for camera device...」**

**第二步：摄像头端（glass）连接与选项**
1. 打开 **glass**
2. 点击 **「Discover devices」**
3. 选择中继设备
4. 选择 **分辨率**（最高 1920×1080，按像素数排序）
5. 选择 **帧率**（30/25/20/15/10/5/1 fps，默认 30）
6. 选择 **音频选项**：「Enable audio」或「Video only (no microphone capture)」

**第三步：摄像头端推流到中继端**
1. 在 **StreamingActivity** 单击 **「Start streaming」**
2. 状态显示 **「Streaming (audio + video)」** 或 **「Streaming (video only)」**

**第四步：中继端接收与本地播放**
1. **app** 在 **SurfaceView** 上显示画面
2. 展示分辨率、帧率及音频信息

**第五步：中继端推流到云端**
1. 输入 RTMP 地址
2. 点击 **「Start cloud streaming」**
3. 状态：**「Connecting to RTMP server...」** → **「Streaming to cloud」**

**停止推流**
- 云端：中继端点击 **「Stop streaming」**
- 局域网：眼镜端 StreamingActivity 点击 **「Stop streaming」**

#### 4.3 注意事项

1. **推流顺序**：建议摄像头先推流数秒再点云端推流，便于缓存 SPS/PPS
2. **权限**：任一端拒绝必要权限可能导致发现/连接失败
3. **切后台**：中继端切后台可能释放接收，需摄像头重连
4. **推流失败**：检查 RTMP 地址、网络、平台要求等

---

### 五、性能与排障

#### 5.1 视频掉帧来源

| 类型           | 说明                                                               |
|--------------|------------------------------------------------------------------|
| **发送路径争用**   | 视频 RTP 与 AAC 共用同一 TCP，同步发送                                       |
| **flush 策略** | `BufferedOutputStream(64KB)`；视频在编码输出后 flush；音频每 8 帧 flush 一次      |
| **整机负载**     | 双 MediaCodec、AudioRecord、Camera、网络叠加                             |

#### 5.2 自助排查步骤

1. **网络 vs 设备**：近距离测试排除 Wi-Fi Direct 抖动
2. **降低负载**：降低分辨率/帧率，或选择"仅视频"
3. **纯视频对比**：若仅视频更流畅，瓶颈在音视频并行
4. **中继 vs 云端**：SurfaceView 卡顿查 glass→手机；仅 RTMP 卡查上行网络
5. **断连与崩溃**：对端先关 Socket 时可能 `Broken pipe`；通过 `IOException` 捕获处理

#### 5.3 协议兼容性

- 流头 **24 字节**；与旧版 16 字节仅视频版本不兼容
- **glass 与 app 须同版本**

---

### 六、依赖说明

| 模块    | 依赖                                   | 用途              |
|-------|--------------------------------------|-----------------|
| app   | RootEncoder `library:2.6.1`（JitPack） | RTMP 推流         |
| glass | MercurySDK（`glass/libs`）             | 镜腿触控、合目 UI 等    |

**仓库**：JitPack — `https://jitpack.io`

---

### 七、常见问题

**Q：中继端看不到画面？**
A：确认眼镜端已在 StreamingActivity 点击「Start streaming」，且 Wi-Fi Direct 已连接。

**Q：云端推流成功但拉流无画面？**
A：先让局域网推流稳定几秒再点云端推流；检查 SPS/PPS/首帧 IDR。平台若要求音轨：眼镜端需选「Enable audio」。

**Q：推流地址如何获取？**
A：各直播平台或自建 Nginx-RTMP 等。

**Q：分辨率、帧率、音频从哪改？**
A：分辨率/帧率在眼镜端选择页；是否采集音频在帧率确认后的 FDialog。App 端信息来自 24 字节流头。

**Q：画面卡顿？**
A：见第五节；可尝试降分辨率/帧率或选「Video only」减轻负载。

---

### 八、附录：协议常量与 Intent 约定

#### 8.1 StreamProtocol（TCP 流头与帧类型）

| 常量名                         | 类型 / 值       | 含义                           |
|-----------------------------|--------------|------------------------------|
| `HEADER_MAGIC`              | `"VSCH"`     | 流头魔数（ASCII，4 字节）             |
| `HEADER_SIZE`               | `24`         | 流头总长度（字节）                    |
| `PACKET_TYPE_VIDEO_RTP`     | `0x01`（Byte） | 负载为 H.264 RTP 包              |
| `PACKET_TYPE_AAC_ADTS`      | `0x02`（Byte） | 负载为 AAC ADTS 帧               |
| `FRAME_HEADER_SIZE`         | `5`          | 每帧前缀：1 字节类型 + 4 字节大端长度       |
| `DEFAULT_AUDIO_SAMPLE_RATE` | `44100`      | 开启音频时写入流头的默认采样率（Hz）          |
| `DEFAULT_AUDIO_CHANNELS`    | `1`          | 开启音频时写入流头的默认声道数              |

**流头 24 字节布局**：`HEADER_MAGIC(4)` → **width** → **height** → **fps** → **audioSampleRate** → **audioChannels**。纯视频时后两项为 **0**。

#### 8.2 glass Intent Extra 键名

| Activity                   | 常量名                   | Key 字符串           | 类型      | 说明                   |
|----------------------------|-----------------------|-------------------|---------|-----------------------|
| `ResolutionSelectActivity` | `EXTRA_HOST`          | `"host"`          | String  | 中继 Group Owner IP    |
| `FrameRateSelectActivity`  | `EXTRA_HOST`          | `"host"`          | String  | 同上                   |
|                            | `EXTRA_WIDTH`         | `"width"`         | Int     | 采集宽度（像素）             |
|                            | `EXTRA_HEIGHT`        | `"height"`        | Int     | 采集高度（像素）             |
| `StreamingActivity`        | `EXTRA_HOST`          | `"host"`          | String  | 同上                   |
|                            | `EXTRA_WIDTH`         | `"width"`         | Int     | 同上                   |
|                            | `EXTRA_HEIGHT`        | `"height"`        | Int     | 同上                   |
|                            | `EXTRA_FPS`           | `"fps"`           | Int     | 帧率（1–120）            |
|                            | `EXTRA_AUDIO_ENABLED` | `"audio_enabled"` | Boolean | 是否采集并发送麦克风 AAC       |

#### 8.3 发送/接收侧可调参数

| 位置                          | 名称 / 含义                        | 值                               |
|-----------------------------|--------------------------------|---------------------------------|
| `VideoStreamingService`     | `AUDIO_FLUSH_EVERY_FRAMES`     | 8                               |
|                             | `BufferedOutputStream` 缓冲      | 64 × 1024 字节                    |
|                             | `Socket.sendBufferSize`        | `coerceAtLeast(256 * 1024)`     |
| `VideoEncoder`              | 默认码率                           | 2,000,000（2 Mbps）               |
|                             | `I_FRAME_INTERVAL`             | 1 秒                             |
| `AudioEncoder`              | `KEY_BIT_RATE`                 | 64,000（64 kbps AAC）             |
| `StreamReceiver`            | `BUFFER_SIZE`                  | 256 × 1024                      |
|                             | `MAX_PACKET_BYTES`             | 2 × 1024 × 1024                 |

---

## License

*TBD*
