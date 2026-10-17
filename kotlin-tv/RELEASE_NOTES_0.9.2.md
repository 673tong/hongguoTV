# 红果 TV 原生版 0.9.2

## 本次变化

### 播放链路修复（黑屏 + "已自动重试 3 次"）

- **公开 360p 直连流优先**：官网播放页为每集提供 `https://hongguoduanju.com/video/public/<vid>/360p.mp4`，
  明文、支持 Range、无需签名与解密。客户端先探测它，**必须返回 206** 才采用（200 说明 CDN 不支持分段，
  退回其他链路），命中即播。
- **旧签名链路降级为兜底**：`multi_video_model` + `spade_a` 那条路服务端已下线（固定返回 `code 110001`），
  现在只在公开地址缺失（下架/风控）时才尝试，不再阻塞正常播放。
- **DASH 分片读取正确性**：`StreamInfo` 新增 `initRange` / `indexRange` / `audioInitRange` / `audioIndexRange`；
  视频轨与音频轨各自使用自己的区间（此前音频轨错误复用了视频轨的区间），`RemoteVideo.prepare()`
  一次性取回 init+index 并校验 `moov` 与样本表完整覆盖声明区间，避免索引被截断。

### 代码结构

- 签名链路拆成独立的 `signedStream()`，`playInfo()` 改为 `internal`，便于单测直接覆盖。
- `MainActivity` 的两处 `stream()` 调用补上 `seriesId`（DASH 分支需要它，360p 命中时自动忽略）。
- 新增 6 个单测：360p 优先且不探测 play-info、CDN 忽略 Range 时的降级、DASH 双轨区间解析、
  分离流缺音轨直接判不可用、`playInfo` 必须带 `seriesId`、`parseRange` 的边界与非法输入。
  core 模块 **88 个测试全部通过**。

## 已知限制（重要）

**720p 目前不可用。** 用户指出网页版默认 720p，实测确认：

- 网页播放器走 `POST /api/video/protected/play-info?aid=8662`，返回 `720×1280` 的 **DASH 分离流**
  （视频 `main_url` + 音频 `main_url`），格式与分片区间（`init_range` / `index_range`）都已完整解析出来。
- 但这条流的样本是 **CENC（AES-CTR）加密** 的（`encv`/`enca` + `schm=cenc`，每个分片自带 `senc`/`saiz`/`saio`），
  而 `play-info` 返回的 `player_access_key` **不是内容密钥**，只是取密钥信封的凭证。
- 真正的 AES 内容密钥由站点播放器的 Emscripten 模块 `aw.wasm` 在 **Web Worker 内二次解封**
  （`getLicense` → `decryptWordArray(data, track.kidValue, IV)`），纯 HTTP 侧拿不到。
  本机已把 `aw.wasm` 抽出来并单独驱动 `_decryptKey` / `_descryptKdsKey`，各种 scheme/长度组合
  均返回全零且不抛异常，说明其解封约定依赖调用方的额外状态，暂时无法离线复现。

所以 0.9.2 仍是 360p。要拿到 720p，需要下列任一条路打通：

1. 复现 `aw.wasm` 的密钥解封约定（拿到明文内容密钥后直接喂给已有的 CENC 解密实现）；
2. 在设备上用 WebView / `androidx.javascriptengine` 跑站点播放器，取出 `kidValue`；
3. 找到另一个直接返回 `spade_a` / PlayAuth 的服务端接口。

## 安装

- 与 0.9.0 / 0.9.1 同一签名密钥，可直接覆盖安装。
- 若设备上还装着早期 `080`（由他人仓库打包、签名不同），应用会提示卸载旧版后再安装。
