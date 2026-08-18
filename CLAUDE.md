# CLAUDE.md

## 项目概述

生产级远程控制软件（类 ToDesk / 向日葵 / RustDesk），Java + Netty 实现。
核心链路：**P2P 直连优先 → 中心信令服务器调度 → 中继转发兜底**，控制面 / 数据面分离。

## 技术栈

- 语言/框架：Java 17 (LTS) + Netty 4.1.x
- 构建：Maven 多模块（`common` / `client` / `server-signaling` / `server-relay`）
- 序列化：Protobuf（信令/控制消息）+ 裸字节自定义帧（流媒体/文件）
- 传输层：Phase 1 裸 UDP，Phase 2 引入 kwik（纯 Java QUIC v1）
- 存储/中间件：MySQL 8 + Redis 集群 + MQ；可观测 Prometheus（Micrometer）
- GUI：JavaFX（client）

## 关键文档

| 文档 | 内容 |
|------|------|
| `远程控制软件底层链路设计-内网穿透与P2P中继架构.md` | NAT/STUN/ICE/打洞/中继/QoS 底层原理 |
| `远程控制软件开发文档.md` | 模块划分、协议、数据模型、流程、里程碑（**实现以此为准**） |

实现前先读开发文档对应章节，原理疑问查底层链路设计文档。

## 工程结构

```
common/            # 公共基座（com.rc.common）
│   ├── proto/signal.proto          # 信令协议 + PathType/NatType/CandidateType 枚举
│   └── src/.../com/rc/common/
│       ├── constant/   # ChannelType/FrameType/FrameFlags/ErrorCode/SessionStatus/Thresholds/ProtocolConstants
│       ├── crypto/     # RsaCipher/AesGcmCipher/Hkdf/Fingerprint/CryptoService(E2EE)
│       ├── codec/      # DataFrame + DataFrameCodec + SignalFrame(En|De)coder（Netty）
│       ├── model/      # User/Device/Session/IceCandidate/RelayNode/Endpoint/ChannelInfo
│       └── util/       # IdGenerator/ByteBufs
client/            # GUI + 信令客户端 + ICE/打洞 + transport + 采集 + monitor（com.rc.client）
│   └── transport/ # TransportChannel/TransportListener 抽象（已建）
server-signaling/  # REST + Netty 长连接 + 会话编排 + 中继调度（com.rc.signaling）
server-relay/      # UDP/TCP-TLS/WS 密文转发 + 令牌校验（com.rc.relay）
```

## 关键设计决策（已与用户对齐，勿擅自推翻）

1. **QUIC ≠ HTTP/3**：远控用 QUIC 作传输层跑自定义协议（stream 可靠 / datagram 实时帧），不是 HTTP/3。
2. **QUIC 落地**：选 kwik（纯 Java、无 native），Phase 2 替换裸 UDP，`TransportChannel` 接口不变。
3. **降级阶梯已简化**：`P2P-UDP → Relay-UDP → Relay-TCP/TLS → Relay-WS → Ended`。已移除 `P2P-QUIC→自定义UDP` 这一无效级（同一 socket/映射，UDP 不通则都失败）。
4. **连接密码 E2EE**：RSA 只做密钥交换/签名，密码用被控端公钥加密，AES-256-GCM 加密数据；信令/中继只做密文透传，不接触明文。
5. **分阶段策略**：Phase 1 先裸 UDP 打通 P2P/中继全链路，再上 QUIC/富媒体。

## 开发约定

- 包名统一 `com.rc.*`
- 数据面传输统一走 `TransportChannel` 抽象，屏蔽 P2P/Relay、UDP/TCP 差异
- 流媒体/文件块不走 Protobuf，走自定义帧（channel 头 1Byte 分通道）
- Netty 中注意 DirectByteBuf 的 `ReferenceCountUtil.release()` 闭环
- 安全：防重放（seq + 时间戳）、指纹校验（`RC-4102`）、会话令牌短 TTL

## 当前进度

- 已完成：
  - 底层链路设计文档、开发文档（v2.0）
  - 四模块 Maven 骨架（父 POM + common/client/server-signaling/server-relay）
  - `common` 完整基座：Protobuf 协议、编解码器（信令帧 + 数据帧）、RSA-OAEP/AES-GCM/HKDF 加密、模型、常量/失败码/门限
  - `client` 的 `TransportChannel`/`TransportListener` 抽象 + JavaFX 启动骨架
  - `server-signaling` 登录注册 + 心跳（REST + bcrypt + JWT）：`AuthController`/`AuthService`/`JwtService`/`JwtAuthFilter`/`SecurityConfig`
  - `server-signaling` Netty 信令长连接 + 设备上报：`SignalingServer`(TLS 自签/证书 + IdleStateHandler 心跳判死) + `SignalServerHandler`
  - 数据层：`schema.sql` + `UserMapper`/`DeviceMapper`（MyBatis）；`DeviceRegistry` 抽象（dev 内存 / prod Redis）；双 profile（dev H2 内存 / prod MySQL+Redis）
  - `client` STUN 穿透 + UDP 打洞 + 连通性检查：`ice/`（StunCodec/StunClient/NatTypeDetector/CandidateGatherer/PortPredictor/IceAgent/UdpSocket）+ `transport/UdpTransportChannel` + `signaling/`（SignalingClient/DeviceInfoClient）+ `security/DeviceIdentity` + `app/ClientConnectionManager` 状态机
  - `server-signaling` 会话路由：`ConnectionRegistry`/`SessionManager`/`SessionService`（邀请转发/候选转发/会话结束）+ `GET /api/devices/{code}` + `SignalServerHandler` 分派 Invite/Candidate/RelayAlloc/SessionEnd
  - 中继 UDP 兜底转发：`common` `RelayToken`(HMAC 签名令牌) + `RelayPacketCodec`(中继包 JOIN/DATA) ；`server-relay` `RelayConfig`/`RelaySessionRegistry`/`RelayServer`(UDP 密文透传 + 令牌校验)；`server-signaling` 中继分配签发令牌并广播；`client` `RelayTransportChannel` + `ClientConnectionManager` 打洞失败/候选为空降级中继
  - 会话状态机 + 运行中降级：`client/monitor` `QosMonitor`(保活丢失/通道关闭硬门限) + `ClientConnectionManager` 显式 `SessionStatus` 状态机(`transition`) + `degrade` 运行中按阶梯降级 + `SessionListener.onPathChanged`
  - AWT Robot 屏幕采集：`client/capture/` `ScreenCapturer`(Robot 整帧抓屏) + `ScreenCodec`(JPEG 编解码) + `ScreenStreamer`(被控端按帧率推流 VIDEO) + `VideoFrameReceiver`(控制端解码回调)
  - kwik 引入（QUIC 替换裸 UDP，骨架）：`tech.kwik:kwik` 依赖 + `client/transport` `QuicTransportChannel`(可靠通道 stream / 实时通道 datagram 路由 + DataFrameCodec 组帧) + `QuicTransportEndpoint`(打洞 socket 上建立 QUIC，角色 CONTROLLER=client / AGENT=server)
  - TCP/TLS + WebSocket 兜底中继：`server-relay` `TcpRelayServer`(长度前缀帧 + 可选 TLS)/`WsRelayServer`(/rc-relay 二进制帧)/`StreamRelaySessionRegistry`(连接即席位)；`client` `RelayTcpTransportChannel`/`RelayWsTransportChannel`(JOIN 握手后切心跳)；`SessionService` 按 `path_type` 分配端口 + `ClientConnectionManager` 降级阶梯 P2P→RelayUDP→RelayTCP→RelayWS
  - 多地域中继 + 就近调度：`common` `RelayNode`(nodeId/region/udp+tcp+ws 端口/tls/loadRatio/心跳时间戳) + `relay_node` 表扩展 + `RelayNodeMapper`/`RelayNodeRegistry`(TTL 在线表)/`RelayManager`(心跳落库 + region 就近择优)/`RelayController`(`/internal/relay-nodes/**`)；`server-relay` `RelayConfig`(nodeId/region/advertiseHost/signalingUrl) + `RelayHeartbeatReporter`(JDK HttpClient 周期上报负载)；`SessionService.handleRelayAlloc` 就近择优回退静态单节点；`client` `SignalingClientConfig.region` + `RelayAllocReq.region`
  - 文件互传 + 双向剪贴板 + 实时音频：`client/file` `FileTransferCodec`(OFFER/CHUNK/COMPLETE + ACK/NACK)/`FileTransferService`(transferId 重组 + RandomAccessFile 偏移写 + 整文件 CRC 校验 + 缺失分块 NACK 重传 + 目标相对路径越界防护 + Caffeine 缓存自动清理半截传输) + `client/clipboard` `ClipboardCodec`/`ClipboardService`(AWT 剪贴板 + 指纹去重) + `client/audio` `AudioConfig`/`AudioStreamer`/`AudioReceiver`(PCM 48k/16bit 采集回放)
  - H.264 管线 + 低延迟采集（骨架）：`client/capture` `VideoCodec`(接口)/`JpegVideoCodec`(JPEG 占位)/`VideoFraming`(1200B 分片 + FEC + NACK)/`FecCodec`(XOR 单丢片还原)/`VideoSender`(分片+FEC+LRU 重传)/`VideoReceiver`(重组+NACK+FEC) + `DesktopDuplicationCapturer`(awtFallback)
  - QUIC 接线（替换裸 UDP）：`QuicTransportEndpoint.selfSigned`(BouncyCastle 自签 X.509 + PEM) + `DeviceIdentity.keyPair()` + `ClientConnectionManager.setQuicEnabled`/`openP2PChannel`(QUIC 建立失败降级中继)
  - 全局 QoS 监控大盘：`common/metrics` `QosMetrics`(Micrometer 全局注册表门面)/`QosMetricNames`；`client` `QosMonitor` 重写为智能阈值版(PING/PONG 心跳 echo 真实测 RTT/丢包 + EWMA 基线 + 滑动窗口 σ + `baseline+k·σ` 动态静默门限替换固定 lostLimit)；`server-relay` `MetricsExporter`(Netty HTTP `/metrics` Prometheus 抓取端点) + 三协议 JOIN/DATA/字节计数 + 活跃会话 gauge；`server-signaling` `SignalingMetrics`(在线设备/活跃会话 gauge) + actuator `/actuator/prometheus`
  - SSO/OIDC 企业认证：`UserMapper.findBySsoSubject` + `schema.sql` `sso_subject UNIQUE` + `AuthService.loginViaSso`(sub 查无则建本地用户) + `security/OidcLoginSuccessHandler`(OidcUser sub → 本地用户 → 签发 JWT → 重定向 `sso-redirect-uri` 带 token) + `SecurityConfig` oauth2Login + IF_REQUIRED 会话(仅授权码流用)
  - 审计合规 + 智能动态中继路由：`server-signaling` `AuditService`(有界队列 10000 + 单后台线程批量落库 + 满队列丢弃计数)/`AuditLogMapper`(insert/page/count)/`AuditController`(`GET /api/admin/audit` 分页)；AuthService(登录/注册/SSO) + SessionService(邀请/会话结束/中继分配/路径切换) 埋审计点；`RelayNodeRegistry` 每节点 EWMA 质量评分(上报 load + 分配成功率) → 复合分择优替换纯负载升序；`RelayManager.recordAllocResult` + `SessionService` 会话结束/`PathSwitchNotify` 回填质量；`client` 降级/回切时发 `PathSwitchNotify`
  - kwik 服务端 + socket 交接落地：`client/ice` `UdpSocket` 重写为 JDK NIO `DatagramChannel`+`Selector`(reader 线程 STUN/数据帧 demux) + `punchedDatagramSocket()`(交棒：取消 SelectionKey + 切阻塞 + 返回 `DatagramSocket`)；`QuicTransportEndpoint.establishServer`(自签 KeyStore → `ServerConnector.withKeyStore` + `datagramSocketFactory` 注入打洞 socket + ALPN `rc/quic` + `CompletableFuture` 握手超时 4s)；`openP2PChannel` QUIC 建立失败返回 null → 降级中继(不再回退裸 UDP，因 socket 已被 kwik 接管)
  - Relay→P2P 静默回切(make-before-break)：`ClientConnectionManager` `scheduleSwitchback`/`startSwitchback` + `P2PSwitchbackProbe` 内部类(候选触发 → 重打洞 → `QosMonitor` 连续 PONG 验证健康 + `qualityImproved` RTT 改善比校验 → commit 换通道/`transition(P2P_CONNECTED)`/`sendPathSwitch` → 失败 abort 重排)；`QosListener.onProbeHealthy` + `QosMonitor.consecutiveHealthy`/`currentRttMs`；`degrade` 探测期间静默抑制 + 回切成功才 `old.close()`；`QosMetricNames.SWITCHBACK_TOTAL/ABORT_TOTAL`
  - 审计日志导出/归档 + 角色权限(ROLE_ADMIN)：`User` `role` 字段 + `schema.sql` `user.role`/`audit_log_archive` 表；`UserMapper` role 读写；`JwtService` `CLAIM_ROLE` 签发；`JwtAuthFilter` 映射 `ROLE_*` 权限；`SecurityConfig` `/api/admin/**` → `hasRole("ADMIN")`；`AuditLogMapper.exportAll/archiveBefore/deleteBefore` + `AuditService.exportCsv/archive`(先插后删事务) + `AuditController` `GET /export`(CSV 下载)/`POST /archive`
  - 桌面端 JavaFX UI + 键鼠远控链路：`client/signaling` `AuthApiClient`(登录/注册 REST) + `client/security` `DeviceIdentityStore`(设备身份持久化，设备码跨启动稳定) + `DeviceIdentity.setConnectPassword`(连接密码运行时设置) + `client/control` `ControlCodec`(键鼠帧，magic "CT" 与 QoS echo 区分)/`InputController`(控制端 JavaFX 采集+坐标缩放)/`InputInjector`(被控端 Robot 注入) + `client/app` `RemoteControlClient`(门面：账户+身份+连接编排+角色化数据面装配) + `client/ui` `ClientApplication`(视图切换+FX 线程桥)/`LoginView`/`MainView`/`RemoteView`(登录/主界面/远控视图) + `ClientConnectionManager` 新增 `onInvite`/`acceptInvite`(被控端用户确认)/`hangup`(主动挂断)/`onConnected` 带 role
  - Phase 2 富媒体深化：`client/capture` `MotionJpegVideoCodec`(脏矩形检测 + 关键帧/增量帧，替换全关键帧 JPEG 占位)/`H264VideoCodec`(JCodec 软编骨架，待对照 javadoc)/`DxgiDesktopDuplicationCapturer`(DXGI JNA 骨架，待 Windows 联调) + `ScreenStreamer` 重写为 `DesktopDuplicationCapturer + VideoCodec + VideoSender` 分片/FEC/NACK 管线 + `requestKeyFrame` + `VideoReceiver.close()` + `RemoteControlClient` 用 `VideoReceiver` 替换 `VideoFrameReceiver`/`onChannelSwitched`(回切重绑数据面)/`createCapturer`(DXGI 优先 AWT 回退) + `InputController.rebind`(回切重绑 channel) + `ClientConnectionManager` `SessionListener.onChannelSwitched`(commit 回切通知)；父 POM + client 加 JNA/JCodec 依赖
- 下一步：编译核对与构建落地（补装 JDK 17 + Maven；对照 kwik 0.9.x / JCodec 0.2.5 javadoc 校正 `QuicTransportEndpoint`/`QuicTransportChannel`/`H264VideoCodec` 中标注 `待对照 javadoc` 的 API 签名）；Phase 2 收尾（H.264 硬编 FFmpeg native 化替换 JCodec 软编、`DxgiDesktopDuplicationCapturer` DXGI COM vtable 绑定联调）
- 构建环境：需 JDK 17 + Maven（当前开发机为 Java 11、未装 Maven，编译前先补装；**kwik 0.9.x 具体 API 未编译核对，接入时需对照其 javadoc 校正**）
