# JavaDesk

> 生产级远程控制软件（类 ToDesk / 向日葵 / RustDesk），Java 17 + Netty 实现。
> 核心链路：**P2P 直连优先 → 服务端会话级路由仲裁 → 中继转发兜底**，控制面 / 数据面分离。
>
> 当前运行主链已统一到 V2：连接 fencing、共享会话/路由 CAS、Redis Streams 跨节点投递、
> Nacos Relay 注册发现、动态健康评分、Ed25519 assignment ticket、双端提交和 E2EE 数据帧均已接线。
> 旧消息字段仍保留在 Protobuf 中用于滚动升级解码，但客户端与 Relay 数据面不再执行旧 HMAC 自主选路链路。

[![Java](https://img.shields.io/badge/Java-17%20(LTS)-orange.svg)](#)
[![Netty](https://img.shields.io/badge/Netty-4.1.115-blue.svg)](#)
[![License](https://img.shields.io/badge/License-Apache--2.0-green.svg)](#)

---

## 目录

1. [项目背景](#1-项目背景)
2. [核心特性](#2-核心特性)
3. [系统架构](#3-系统架构)
4. [技术栈](#4-技术栈)
5. [工程结构](#5-工程结构)
6. [核心设计](#6-核心设计)
7. [快速开始](#7-快速开始)
8. [配置说明](#8-配置说明)
9. [端口与端点](#9-端口与端点)
10. [项目状态](#10-项目状态)
11. [文档索引](#11-文档索引)

---

## 1. 项目背景

在企业与个人办公场景中，远程控制工具是刚需。市面上的 ToDesk、向日葵、RustDesk 等产品虽成熟，但多数存在以下痛点：

- **数据经第三方服务器中转**，隐私与合规风险高；
- **P2P 打洞成功率**受 NAT 类型影响大，复杂网络下体验不稳定；
- **企业级能力缺失**：SSO 单点登录、审计合规、多地域就近调度、全链路可观测等难以开箱即用。

JavaDesk 旨在从零实现一款**可自托管、端到端加密（E2EE）、P2P 优先**的远程控制软件，在保证可达性（中继兜底）的前提下，最大化直连率、最小化中转成本，并对齐大规模商业化运营所需的工程标准。

### 设计哲学

| 原则 | 说明 |
|------|------|
| 控制面 / 数据面分离 | 信令（低带宽强一致）与流媒体（高带宽低时延）解耦，独立扩缩容 |
| P2P 优先、中继兜底 | 直连省成本、低时延；中继是可达性底线，不假设打洞 100% 成功 |
| 端到端加密 | 信令与中继只做密文透传，不接触密钥与明文 |
| 降级自动、业务无感 | 路径切换对上层透明，会话 ID 不变 |

---

## 2. 核心特性

- **P2P 直连**：STUN 探测 + ICE 候选收集 + UDP 打洞 + 端口预测（对称 NAT 场景）。
- **服务端统一路由仲裁**：候选集可以最终一致，但会话路径必须经 `PREPARE → 双端 READY → COMMIT`，用 `routeEpoch` 防脑裂。
- **全自动降级阶梯**：`P2P-QUIC/UDP → Relay-UDP → Relay-TCP/TLS → Relay-WS → Ended`，V2 中客户端只执行服务端 assignment，不自主换 relay。
- **无损迁移**：新 Relay 先 JOIN，两端 READY 后服务端 COMMIT，旧路径最后 RETIRE；任何一端都不能单方面快进 epoch。
- **端到端加密**：`SecureTransportChannel` 使用方向隔离的 AES-256-GCM epoch key、确定性唯一 nonce 与重放窗口；relay 只看到最小 outer frame。
- **QUIC 传输层**：kwik（纯 Java QUIC v1），stream 跑可靠通道、datagram 跑实时帧。
- **多通道复用**：单 socket / 中继流上以 1 Byte channel 头复用控制 / 视频 / 音频 / 文件 / 剪贴板。
- **富媒体能力**：屏幕采集推流（JPEG 占位 + H.264 管线骨架）、键鼠远控、文件互传、双向剪贴板、实时音频。
- **智能 QoS**：PING/PONG 心跳真实测 RTT/丢包，EWMA 基线 + 滑动窗口 σ 动态阈值，替代固定门限。
- **多地域中继**：Nacos 管 ephemeral 实例和静态端口能力，Redis 管连接数、CPU、带宽、direct-memory 与网络维度 EWMA，信令按 region/provider/path 综合择优。
- **企业级认证与合规**：JWT（角色权限 ROLE_ADMIN）、SSO/OIDC 授权码流、异步审计流水（落库 + 导出归档）。
- **可观测**：Micrometer + Prometheus，三端统一暴露指标（在线设备 / 活跃会话 / 字节计数 / 打洞成功率）。

---

## 3. 系统架构

### 3.1 拓扑

```
                    ┌─────────────────────────┐
                    │      信令集群 (可横向扩展) │
                    │  Spring Boot + Netty    │
                    │  REST(8080) + 长连接(8443)│
                    └───┬─────────┬─────────┬─┘
                        │ HTTPS   │TCP/TLS  │ 内部接口
            ┌───────────▼───┐ ┌───▼────┐  ┌──▼──────────────┐
            │ MySQL + Redis │ │  STUN  │  │  中继服务器集群    │
            │ lease/session │ │ coturn │  │ epoch/role 席位   │
            └───────────────┘ └────────┘  └──────────────────┘

  控制端 (Controller)                   被控端 (Agent)
       │  ① P2P 打洞直连 (QUIC/UDP)        │
       └──────────────┬────────────────────┘
                      │ 失败/劣化降级
       ┌──────────────▼────────────────────┐
       │ 中继：只校验票据和席位，业务密文透传 │
       └───────────────────────────────────┘
```

### 3.2 核心链路

1. 客户端登录信令中心（REST 账号口令 / SSO），签发 JWT；
2. 客户端经 Netty 长连接上报设备（设备码、公钥指纹、NAT 类型），保持心跳；
3. 控制端发起邀请，连接密码经被控端公钥加密后经信令透传；
4. 被控端解密校验并确认，双方经 STUN/ICE 收集候选并 UDP 打洞；
5. 打洞成功建立 P2P；失败时任一端提交 `RelaySwitchRequest(baseEpoch)`，服务端只选择一个 relay，并向两端下发同 assignment 的角色票据；
6. 两端 JOIN 并报告 READY，只有双 READY 才 COMMIT；客户端在 `SwitchableTransportChannel` 下原子换路，再退役旧路径；
7. 信令重连后通过 lease fencing 与 session snapshot 对账；过期通知、旧 epoch 和旧连接不能覆盖当前事实。

---

## 4. 技术栈

| 类别 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 17 (LTS) | 服务端 / 客户端统一 |
| 网络框架 | Netty 4.1.115 | 海量并发长连接，异步高性能 |
| 构建 | Maven 多模块 | common / client / server-signaling / server-relay |
| 序列化 | Protobuf 3.25.3 | 信令 / 控制消息；流媒体 / 文件走裸字节自定义帧 |
| 传输层 | kwik 0.9.1 | Maven Central 已发布的 0.9.x 版本，stream + datagram API 已编译校正 |
| 安全 | BouncyCastle 1.78 + JJWT 0.12.5 | RSA-OAEP / AES-GCM / HKDF + JWT(HS256) |
| 服务端框架 | Spring Boot 3.2.5 + MyBatis | 仅 server-signaling |
| 存储 | MySQL 8 + Redis 集群 | dev 默认 H2 内存库零依赖自测 |
| GUI | JavaFX 21.0.2 | 桌面客户端 |
| 可观测 | Micrometer + Prometheus | 三端统一指标 |

---

## 5. 工程结构

```
remote-control/
├── pom.xml                          # 父 POM（依赖版本统一 + 插件管理）
├── common/                          # 公共基座 com.rc.common
│   └── src/main/
│       ├── proto/signal.proto       # 当前长连接 wire：注册、Snapshot、Assignment/Ready/Commit/Abort/Retire
│       ├── proto/signal_v2.proto    # 分布式信封与状态模型定义
│       └── java/com/rc/common/
│           ├── constant/            # ChannelType/FrameType/FrameFlags/ErrorCode/SessionStatus/Thresholds
│           ├── crypto/              # HKDF/AES-GCM/Ed25519 ticket/nonce/replay window
│           ├── codec/               # 信令帧、Outer/Inner E2EE 帧、RelayPacket V2
│           ├── model/               # User/Device/Session/IceCandidate/RelayNode/Endpoint/ChannelInfo
│           ├── metrics/             # QosMetrics(Micrometer 门面)/QosMetricNames
│           └── util/                # IdGenerator/ByteBufs
├── client/                          # 富客户端 com.rc.client
│   └── src/main/java/com/rc/client/
│       ├── app/                     # ClientApplication(JavaFX)/RemoteControlClient(门面)/ClientConnectionManager(状态机)
│       ├── ui/                      # LoginView/MainView/RemoteView
│       ├── signaling/               # SignalingClient(长连接)/AuthApiClient(REST)/DeviceInfoClient
│       ├── ice/                     # StunCodec/StunClient/NatTypeDetector/CandidateGatherer/PortPredictor/IceAgent/UdpSocket
│       ├── transport/               # 原始路径 + SwitchableTransportChannel + SecureTransportChannel
│       ├── session/                 # SessionActor / 纯 reducer / epoch 事件
│       ├── relay/                   # 只执行指定 assignment 的 RelayClusterInvoker
│       ├── capture/                 # ScreenCapturer/ScreenCodec/ScreenStreamer + H.264 管线(VideoCodec/FecCodec/VideoSender/Receiver)
│       ├── control/                 # ControlCodec/InputController(采集)/InputInjector(注入)
│       ├── file/                    # FileTransferCodec/FileTransferService
│       ├── clipboard/               # ClipboardCodec/ClipboardService
│       ├── audio/                   # AudioConfig/AudioStreamer/AudioReceiver
│       ├── monitor/                 # QosMonitor(智能阈值)/QosListener
│       └── security/                # DeviceIdentity/DeviceIdentityStore(设备身份持久化)
├── server-signaling/                # 信令服务器 com.rc.signaling
│   └── src/main/
│       ├── java/com/rc/signaling/
│       │   ├── api/                 # AuthController/DeviceController/RelayController/AuditController(REST)
│       │   ├── netty/               # SignalingServer(TLS 长连接)/SignalServerHandler
│       │   ├── connection/          # ConnectionLeaseStore（内存 / Redis Lua fencing）
│       │   ├── session/             # SessionStore、成员鉴权、状态机
│       │   ├── route/               # RouteCoordinator + RouteStore（内存 / Redis Lua CAS）
│       │   ├── messaging/           # Local/Redis Streams SignalRouter + inbox/DLQ
│       │   ├── service/             # AuthService/JwtService/SessionService/DeviceService/RelayManager/AuditService
│       │   ├── security/            # JWT、internal service gate、生产配置强校验
│       │   ├── metrics/             # SignalingMetrics
│       │   └── dao/                 # UserMapper/DeviceMapper/RelayNodeMapper/AuditLogMapper
│       └── resources/               # application.yml(dev H2) / application-prod.yml(MySQL+Redis) / schema.sql
└── server-relay/                    # 中继服务器 com.rc.relay
    └── src/main/java/com/rc/relay/
        ├── RelayApplication         # 入口(plain main)，同时启动三协议转发
        ├── udp/                     # RelayPacket V2 + session/epoch/path/role 席位
        ├── tcp/                     # TCP/TLS V2 角色席位与序列 fencing
        ├── ws/                      # WsRelayServer(/rc-relay 二进制帧)
        ├── session/                 # V2 session+epoch+path 角色席位
        ├── security/                # Ed25519 公钥刷新 + ticket replay guard
        ├── config/                  # RelayConfig(环境变量)
        └── metrics/                 # MetricsExporter(/metrics Prometheus 端点)
```

---

## 6. 核心设计

### 6.1 传输抽象 `TransportChannel`

数据面统一走 `TransportChannel` 抽象，屏蔽 P2P / Relay、UDP / TCP / QUIC 差异：

```java
public interface TransportChannel {
    void send(ChannelType ch, byte[] payload);  // CONTROL/VIDEO/AUDIO/FILE/CLIPBOARD
    void addListener(TransportListener l);
    ChannelInfo info();                         // 路径类型、RTT、丢包率
    void close();
}
```

### 6.2 轻量级多路复用

单 socket / 中继流上以 **1 Byte channel 头**分组复用：

| channel | 含义 | 可靠性 |
|---------|------|--------|
| 0 | 控制信令（键鼠） | 可靠，P0 最高 |
| 1 | 视频数据 | 部分可靠，P1 |
| 2 | 音频数据 | 部分可靠，P1 |
| 3 | 文件传输 | 强可靠，P3 |
| 4 | 系统剪贴板 | 可靠，P2 |

### 6.3 端到端加密（E2EE）

1. 控制端提取目标设备 RSA 公钥，将「连接密码 + 随机熵」加密为密文包，信令只透传；
2. 双方以邀请熵和 session 成员身份派生 `sessionMasterKey`；
3. 每个 `routeEpoch + pathType` 派生独立 epoch key，再拆成 `c2a` / `a2c` 两个方向密钥；
4. `SecureTransportChannel` 把 channel/type/flags/streamSeq/payload 全部放进 encrypted inner frame；
5. outer frame 只保留 session、epoch、direction、packetSeq 与 ciphertext，并作为 AES-GCM AAD；
6. nonce 布局为 `epochLow32 | direction16 | stream16 | sequence32`，接收端按 stream 维护 64 包重放窗口。

> RSA 仅用于加密短凭据 / 签名；数据加密统一走 AES-GCM。信令 / 中继不接触明文。
> P2P 是 `routeEpoch=0`，首次 Relay 是 epoch 1；每次提交都派生新方向密钥，旧 epoch 数据不能进入新通道。

### 6.4 降级阶梯与 QoS 自适应

```
P2P（QUIC/UDP）
    │ 失败/劣化
    ▼
Relay-UDP（UDP 中继）
    │ 失败/劣化
    ▼
Relay-TCP/TLS（中继）
    │ 失败
    ▼
Relay-WebSocket(443)（伪装 HTTP 流量兜底）
    │ 失败
    ▼
会话结束 (Ended)
```

- **硬门限**：传输层 close / 连续端到端保活无响应 → 提交 `RelaySwitchRequest`；
- **软门限**：丢包率 / RTT / 卡顿率持续超阈值 → 降级（阈值由 EWMA 基线 + k·σ 动态计算）；
- **故障换路**：客户端对同一 assignment 最多重试 3 次（指数退避 + full jitter），失败后上报；节点和下一传输类型只由服务端选择。

### 6.5 安全

| 机制 | 说明 |
|------|------|
| 防重放 | `messageId` 幂等 + connection seq；数据面 nonce 序号 + ReplayWindow |
| 防伪造 | 强制校验设备公钥指纹，指纹篡改抛 `RC-4102` 熔断告警 |
| 会话令牌 | V2 Ed25519 签名，绑定 node/epoch/path/role/device/connectionEpoch/jti |
| 角色权限 | JWT 携带 role 声明，`/api/admin/**` 限 `ROLE_ADMIN` |
| SSO 回调 | URL 只携带 60 秒一次性交接码；access/refresh token 存服务端并且仅可兑换一次 |
| 内部接口 | `/internal/**` 不再 permitAll；校验应用层服务凭据，生产反向代理或 service mesh 必须再强制 mTLS |
| 审计合规 | 登录 / 邀请 / 挂断等安全事件异步落库，支持 CSV 导出与归档 |

---

## 7. 快速开始

### 7.1 环境准备

- **JDK 17**（LTS）
- **Maven 3.8+**
- （生产必需）MySQL 8、Redis 高可用、Nacos 集群、正式 TLS 证书与 STUN 服务

> dev 模式零外部依赖即可自测：信令默认 H2 内存库、无证书时自动自签。

### 7.2 构建

```bash
# 克隆
git clone https://github.com/zhh293/JavaDesk.git
cd JavaDesk

# 全量编译 + 单元/并发不变式测试（common 会生成 Protobuf Java 类型）
mvn clean test

# 仅打包
mvn clean package -DskipTests
```

### 7.3 启动信令服务器

```bash
# dev 默认：H2 内存库 + 自动自签证书，REST 8080 / Netty 长连接 8443
mvn -pl server-signaling -am spring-boot:run

# prod：MySQL + Redis（需先注入环境变量，见 §8）
mvn -pl server-signaling -am spring-boot:run -Dspring-boot.run.profiles=prod
```

### 7.4 启动中继服务器

中继为 `plain main`（非 Spring），配置经环境变量读取，dev 默认值可直接启动：

```bash
mvn -pl server-relay -am package -DskipTests

# Windows 示例（classpath 分隔符为分号）
mvn -pl server-relay -am dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "server-relay/target/classes;common/target/classes;$(cat cp.txt)" com.rc.relay.RelayApplication

# Linux / macOS（分隔符为冒号）
java -cp "server-relay/target/classes:common/target/classes:$(cat cp.txt)" com.rc.relay.RelayApplication
```

> 也可在 IDE 中直接运行 `RelayApplication` 的 `main`。

### 7.5 启动客户端（两个实例）

```bash
mvn -pl client -am package -DskipTests
mvn -pl client -am dependency:build-classpath -Dmdep.outputFile=cp.txt

# Windows
java -cp "client/target/classes;common/target/classes;$(cat cp.txt)" com.rc.client.app.ClientLauncher

# Linux / macOS
java -cp "client/target/classes:common/target/classes:$(cat cp.txt)" com.rc.client.app.ClientLauncher
```

> 启动入口为 `ClientLauncher`（独立于 JavaFX `Application` 子类，规避模块启动检查）。

### 7.6 使用流程

1. **双方启动客户端**，在登录界面输入信令服务器地址（默认 `127.0.0.1`）、用户名、密码，点击「注册」或「登录」；
2. **被控端**：登录后进入主界面，查看「本机设备码」，并在「本机连接密码」输入框中设置连接密码；
3. **控制端**：在主界面输入被控端的「远端设备码」与「远端连接密码」，点击「连接」；
4. **被控端**：弹出「远程控制请求」确认框，点击「OK」接受；
5. 会话建立后，**控制端**看到远端实时画面并可键鼠操作；**被控端**显示「正在被远程控制」；
6. 底部工具栏支持**发送文件**（FileChooser 选择文件），被控端可**开启 / 关闭麦克风**；
7. 任意一方点击「断开」结束会话。

> 单机自测：开两个客户端窗口，一个注册为被控端（设置连接密码），另一个登录后填入前者设备码发起连接。

### 7.7 启动本地分布式拓扑

`deploy/docker-compose.cluster.yml` 会启动 MySQL、Redis、Nacos 3.2.3、两个信令节点、两个 Relay 和 Nginx 四层/HTTP 网关。先准备正式 TLS 文件、32 字节以上凭据，以及 Base64 PKCS#8/X.509 Ed25519 密钥对，再执行：

```bash
docker compose -f deploy/docker-compose.cluster.yml up --build
```

本机验证默认入口是 REST `localhost:8080`、信令 TLS `localhost:8443`；两个 Relay 分别使用 `19090-19092` 与 `29090-29092`。跨主机部署时必须把 `RELAY_ADVERTISE_HOST` 改为客户端可达的公网或专网地址。生产 Nacos 应使用 3/5 节点集群，compose 中的 standalone 只用于本地多节点验收。

---

## 8. 配置说明

### 8.1 信令服务器（Spring Boot）

`server-signaling/src/main/resources/application.yml`（dev 默认），关键项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | `8080` | REST 端口 |
| `spring.datasource.url` | H2 内存库 | dev 零依赖；prod 切 MySQL |
| `rc.signaling.port` | `8443` | Netty 信令长连接端口 |
| `rc.signaling.tls` | `true` | dev 无证书自动自签 |
| `rc.signaling.cert-file/key-file` | 空 | 正式 PEM 证书链 / 私钥；prod 必填 |
| `rc.nacos.*` | dev 关闭 / prod 必开 | Relay ephemeral 注册、健康实例订阅与静态能力发现 |
| `rc.security.relay-ticket-*` | dev 临时生成 / prod 必填 | Ed25519 assignment 签名私钥、公钥和 keyId |
| `rc.security.jwt-secret` | `RC_JWT_SECRET` 环境变量 | JWT 签名密钥（prod 必须注入） |
| `rc.security.internal-service-token` | dev 值 | `/internal/**` 服务身份凭据；prod 必须注入 |

`application-prod.yml` 激活 `--spring.profiles.active=prod`，切 MySQL 8 + Redis，`sql.init.mode=never`（需手动执行 `schema.sql`）。
OIDC 是可选能力；启用时同时激活 `oidc` profile（`prod,oidc`）并提供 `OIDC_CLIENT_ID`、`OIDC_CLIENT_SECRET`、`OIDC_ISSUER_URI`。未启用时服务不会访问占位 IdP，也不会注册 OAuth2 登录过滤器。

### 8.2 中继服务器（环境变量）

`RelayConfig` 从系统属性 / 环境变量读取，dev 默认值可直接启动：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `RC_RELAY_HOST` | `0.0.0.0` | 监听地址 |
| `RC_RELAY_PORT` | `9090` | UDP 转发端口 |
| `RC_RELAY_TCP_PORT` | `9091` | TCP/TLS 转发端口 |
| `RC_RELAY_WS_PORT` | `9092` | WebSocket 转发端口 |
| `RC_RELAY_METRICS_PORT` | `9093` | Prometheus 抓取端口 |
| `RC_RELAY_TLS` | `false` | 是否启用 TLS（TCP/WS） |
| `RC_RELAY_CERT_FILE` / `RC_RELAY_KEY_FILE` | 空 | PEM 证书链 / 私钥 |
| `RC_RELAY_SESSION_TTL_SECONDS` | `120` | 会话席位 TTL |
| `RC_RELAY_NODE_ID` | `relay-1` | 节点 ID |
| `RC_RELAY_REGION` | `cn-east` | 地域标识 |
| `RC_RELAY_ADVERTISE_HOST` | `127.0.0.1` | 向信令广播的公网地址 |
| `RC_RELAY_SIGNALING_URL` | `http://127.0.0.1:8080/internal/relay-nodes/heartbeat` | 心跳上报接口 |
| `RC_INTERNAL_SERVICE_TOKEN` | dev 值 | 中继调用信令 internal API 的凭据 |
| `RC_NACOS_ENABLED` / `RC_NACOS_*` | dev 关闭 | 注册中心地址、namespace、账号、service/group |
| `RC_RELAY_CAPACITY` | `1000` | 调度容量基线（并发会话） |
| `RC_RELAY_BANDWIDTH_CAPACITY_MBPS` | `1000` | 带宽利用率计算基线 |

### 8.3 客户端

默认值硬编码于 `ClientApplication.buildClient`：

| 项 | 值 |
|----|----|
| REST 端口 | `8080` |
| 信令长连接 | `8443`（TLS；dev 可信任自签证书，prod 必须配置并校验证书链） |
| STUN 服务器 | `stun.l.google.com:19302` |
| 文件接收目录 | `~/rc-received` |
| 设备身份 | `~/.rc-client/identity.json`（设备码跨启动稳定） |

桌面客户端只对 `localhost/127.0.0.1/::1` 默认信任开发自签证书；远程主机默认验证证书。仅本地调试时可显式传入 `-Drc.client.trust-all=true`，远程 REST 默认使用 HTTPS（可用 `-Drc.client.rest-scheme=http` 覆盖开发环境）。

---

## 9. 端口与端点

| 服务 | 端口 | 说明 |
|------|------|------|
| 信令 REST | `8080` | `/api/auth/**`、`/api/devices/**`、`/api/admin/**` |
| 信令 Netty | `8443` | 长连接（TLS） |
| 信令 Prometheus | `8080/actuator/prometheus` | Micrometer 指标 |
| 中继 UDP | `9090` | 数据面转发 |
| 中继 TCP/TLS | `9091` | 数据面转发 |
| 中继 WS | `9092` | `/rc-relay` 数据面转发 |
| 中继 Prometheus | `9093/metrics` | 中继指标 |

---

## 10. 项目状态

当前运行主链为 **V2 分布式路由与 Relay 数据面**：

- ✅ 四模块 Maven 骨架 + `common` 完整基座（协议 / 编解码 / 加密 / 模型 / 常量）
- ✅ 登录注册 + 心跳（REST + bcrypt + JWT）、SSO/OIDC 60 秒一次性交接码
- ✅ 信令长连接 + 设备上报 + 会话路由 + 中继调度
- ✅ STUN 穿透 + UDP 打洞 + 连通性检查 + 端口预测
- ✅ 中继三协议兜底（UDP / TCP-TLS / WS）+ RelayPacket V2 + Ed25519 角色票据
- ✅ 会话状态机 + 服务端权威降级阶梯 + PREPARE/双 READY/COMMIT/RETIRE
- ✅ AWT Robot 屏幕采集推流 + H.264 管线骨架 + 键鼠远控
- ✅ 文件互传 / 双向剪贴板 / 实时音频
- ✅ QUIC（kwik）接线替换裸 UDP
- ✅ 修正不存在的 kwik 0.9.3，按 0.9.1 实际 API 完成全模块编译
- ✅ 真实长连接 wire 的 Snapshot、Relay Assignment/Ready/Commit/Abort/Retire 消息
- ✅ ConnectionLease fencing；旧连接断开/心跳不能覆盖新连接
- ✅ SessionStore / SessionAuthorization / RouteCoordinator；100 并发请求单 assignment 测试
- ✅ Redis Lua session/route CAS + Redis Streams inbox、deadline、dedupe、DLQ
- ✅ Ed25519 relay ticket + session/epoch/path/role 席位 + 活动 TTL
- ✅ SwitchableTransportChannel、SecureTransportChannel、nonce/replay/tamper 测试
- ✅ `/internal/**` 认证、生产不安全配置拒绝启动、SSO token 不进入 redirect URL
- ✅ Nacos 注册发现 + Redis 高频运行指标/网络 EWMA + region/provider/path 综合调度
- ✅ 客户端同 assignment 三次 full-jitter 重试，失败上报后由信令排除节点并重选
- ✅ Nginx 双信令负载均衡与双 Relay 的 Docker Compose 集群编排
- ✅ 桌面端 JavaFX UI + 键鼠远控链路

> 本仓库测试命令为 `mvn test`。单元/并发测试覆盖票据、V2 JOIN、角色席位、连接 fencing、路由 CAS、健康评分、稳定通道和 E2EE；外部 Redis/MySQL/Nacos 与节点 kill 验收应使用 `deploy/docker-compose.cluster.yml`。当前执行环境没有 Docker，因此本次只能验证 compose 文件和 Java 构建，不能伪称容器/Chaos 已实跑。

---

## 11. 文档索引

| 文档 | 内容 |
|------|------|
| [`远程控制软件开发文档.md`](远程控制软件开发文档.md) | 模块划分、协议、数据模型、流程、里程碑（实现以此为准） |
| [`远程控制软件底层链路设计-内网穿透与P2P中继架构.md`](远程控制软件底层链路设计-内网穿透与P2P中继架构.md) | NAT/STUN/ICE/打洞/中继/QoS 底层原理 |
| [`JavaDesk分布式化重构方案V2-超级完整版.md`](JavaDesk分布式化重构方案V2-超级完整版.md) | V2 权威目标、全局不变式、协议与验收标准 |
| [`项目架构流程与底层原理.md`](项目架构流程与底层原理.md) | 当前项目架构、端到端流程、状态机与底层原理 |
| [`CLAUDE.md`](CLAUDE.md) | 开发约定、关键设计决策、当前进度 |

---

## License

[Apache-2.0](LICENSE)（如未单独提供 License 文件，默认保留所有权利）。
