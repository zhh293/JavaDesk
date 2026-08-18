# JavaDesk

> 生产级远程控制软件（类 ToDesk / 向日葵 / RustDesk），Java 17 + Netty 实现。
> 核心链路：**P2P 直连优先 → 中心信令服务器调度 → 中继转发兜底**，控制面 / 数据面分离。

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
- **全自动降级阶梯**：`P2P-UDP → Relay-UDP → Relay-TCP/TLS → Relay-WS → Ended`，链路劣化自动下沉。
- **平滑回切**：Relay 驻留期间后台静默 P2P 试探，`make-before-break` 无感切回直连。
- **端到端加密**：RSA-OAEP 密钥交换 + AES-256-GCM 数据加密，连接密码以被控端公钥加密，服务端零接触明文。
- **QUIC 传输层**：kwik（纯 Java QUIC v1），stream 跑可靠通道、datagram 跑实时帧。
- **多通道复用**：单 socket / 中继流上以 1 Byte channel 头复用控制 / 视频 / 音频 / 文件 / 剪贴板。
- **富媒体能力**：屏幕采集推流（JPEG 占位 + H.264 管线骨架）、键鼠远控、文件互传、双向剪贴板、实时音频。
- **智能 QoS**：PING/PONG 心跳真实测 RTT/丢包，EWMA 基线 + 滑动窗口 σ 动态阈值，替代固定门限。
- **多地域中继**：中继节点心跳上报负载，信令按 region 就近择优 + EWMA 质量评分动态路由。
- **企业级认证与合规**：JWT（角色权限 ROLE_ADMIN）、SSO/OIDC 授权码流、异步审计流水（落库 + 导出归档）。
- **可观测**：Micrometer + Prometheus，三端统一暴露指标（在线设备 / 活跃会话 / 字节计数 / 打洞成功率）。

---

## 3. 系统架构

### 3.1 拓扑

```
                    ┌─────────────────────────┐
                    │      信令集群 (无状态)    │
                    │  Spring Boot + Netty    │
                    │  REST(8080) + 长连接(8443)│
                    └───┬─────────┬─────────┬─┘
                        │ HTTPS   │TCP/TLS  │ 内部接口
            ┌───────────▼───┐ ┌───▼────┐  ┌──▼──────────────┐
            │ MySQL + Redis │ │  STUN  │  │  中继服务器集群    │
            │ (prod)        │ │ coturn │  │  UDP/TCP/WS 转发  │
            └───────────────┘ └────────┘  └──────────────────┘

  控制端 (Controller)                   被控端 (Agent)
       │  ① P2P 打洞直连 (QUIC/UDP)        │
       └──────────────┬────────────────────┘
                      │ 失败/劣化降级
       ┌──────────────▼────────────────────┐
       │     中继：密文透传（E2EE 不落地）    │
       └───────────────────────────────────┘
```

### 3.2 核心链路

1. 客户端登录信令中心（REST 账号口令 / SSO），签发 JWT；
2. 客户端经 Netty 长连接上报设备（设备码、公钥指纹、NAT 类型），保持心跳；
3. 控制端发起邀请，连接密码经被控端公钥加密后经信令透传；
4. 被控端解密校验并确认，双方经 STUN/ICE 收集候选并 UDP 打洞；
5. 打洞成功建立 P2P 直连（Phase 2 升级为 QUIC）；失败则向信令申请就近中继 + 一次性令牌，走中继密文转发；
6. 运行中 QoS 监测持续评估链路质量，触发降级或平滑回切，对上层透明。

---

## 4. 技术栈

| 类别 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 17 (LTS) | 服务端 / 客户端统一 |
| 网络框架 | Netty 4.1.115 | 海量并发长连接，异步高性能 |
| 构建 | Maven 多模块 | common / client / server-signaling / server-relay |
| 序列化 | Protobuf 3.25.3 | 信令 / 控制消息；流媒体 / 文件走裸字节自定义帧 |
| 传输层 | kwik 0.9.3 | 纯 Java QUIC v1，Phase 2 替换裸 UDP |
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
│       ├── proto/signal.proto       # 信令协议 + PathType/NatType/CandidateType 枚举
│       └── java/com/rc/common/
│           ├── constant/            # ChannelType/FrameType/FrameFlags/ErrorCode/SessionStatus/Thresholds
│           ├── crypto/              # RsaCipher/AesGcmCipher/Hkdf/Fingerprint/CryptoService/RelayToken
│           ├── codec/               # DataFrame+Codec / SignalFrame(En|De)coder / RelayPacketCodec
│           ├── model/               # User/Device/Session/IceCandidate/RelayNode/Endpoint/ChannelInfo
│           ├── metrics/             # QosMetrics(Micrometer 门面)/QosMetricNames
│           └── util/                # IdGenerator/ByteBufs
├── client/                          # 富客户端 com.rc.client
│   └── src/main/java/com/rc/client/
│       ├── app/                     # ClientApplication(JavaFX)/RemoteControlClient(门面)/ClientConnectionManager(状态机)
│       ├── ui/                      # LoginView/MainView/RemoteView
│       ├── signaling/               # SignalingClient(长连接)/AuthApiClient(REST)/DeviceInfoClient
│       ├── ice/                     # StunCodec/StunClient/NatTypeDetector/CandidateGatherer/PortPredictor/IceAgent/UdpSocket
│       ├── transport/               # TransportChannel 抽象 + UDP/QUIC/RelayUDP/RelayTCP/RelayWS 实现
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
│       │   ├── session/             # ConnectionRegistry/SessionManager/DeviceRegistry(内存|Redis)/RelayNodeRegistry
│       │   ├── service/             # AuthService/JwtService/SessionService/DeviceService/RelayManager/AuditService
│       │   ├── security/            # JwtAuthFilter/SecurityConfig/OidcLoginSuccessHandler
│       │   ├── metrics/             # SignalingMetrics
│       │   └── dao/                 # UserMapper/DeviceMapper/RelayNodeMapper/AuditLogMapper
│       └── resources/               # application.yml(dev H2) / application-prod.yml(MySQL+Redis) / schema.sql
└── server-relay/                    # 中继服务器 com.rc.relay
    └── src/main/java/com/rc/relay/
        ├── RelayApplication         # 入口(plain main)，同时启动三协议转发
        ├── udp/                     # RelayServer + RelaySessionRegistry
        ├── tcp/                     # TcpRelayServer + StreamRelaySessionRegistry
        ├── ws/                      # WsRelayServer(/rc-relay 二进制帧)
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

1. 控制端提取目标设备 RSA 公钥，将「连接密码 + 随机熵」加密为密文包；
2. 信令服务器作为第三方通道纯透传；
3. 被控端用本地私钥解密并校验口令；
4. 校验成功后双方凭随机数派生会话级 **AES-256-GCM** 密钥，接管后续流媒体与输入指令。

> RSA 仅用于加密短凭据 / 签名；数据加密统一走 AES-GCM。信令 / 中继不接触明文。

### 6.4 降级阶梯与 QoS 自适应

```
P2P-UDP（打洞直连，Phase 2 升级为 QUIC）
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

- **硬门限**：传输层 close / 连续 3 个保活周期无响应 → 立即降级；
- **软门限**：丢包率 / RTT / 卡顿率持续超阈值 → 降级（阈值由 EWMA 基线 + k·σ 动态计算）；
- **平滑回切**：中继强制驻留 ≥ 30s，后台静默试探，连续成功且质量提升 > 20% 才 `make-before-break` 切回直连。

### 6.5 安全

| 机制 | 说明 |
|------|------|
| 防重放 | 严格递增 `seq` + 时间戳，服务端幂等拦截 |
| 防伪造 | 强制校验设备公钥指纹，指纹篡改抛 `RC-4102` 熔断告警 |
| 会话令牌 | 中继令牌短 TTL（60s）、一次性使用，HMAC 签名 |
| 角色权限 | JWT 携带 role 声明，`/api/admin/**` 限 `ROLE_ADMIN` |
| 审计合规 | 登录 / 邀请 / 挂断等安全事件异步落库，支持 CSV 导出与归档 |

---

## 7. 快速开始

### 7.1 环境准备

- **JDK 17**（LTS）
- **Maven 3.8+**
- （生产可选）MySQL 8、Redis、STUN 服务器（如 coturn）

> dev 模式零外部依赖即可自测：信令默认 H2 内存库、无证书时自动自签。

### 7.2 构建

```bash
# 克隆
git clone https://github.com/zhh293/JavaDesk.git
cd JavaDesk

# 全量构建（common 模块会触发 protobuf 代码生成）
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
| `rc.signaling.relay-host/port` | `127.0.0.1:9090` | 中继地址（Phase 1 静态单节点） |
| `rc.signaling.relay-secret` | `RC_RELAY_SECRET` 环境变量 | 与中继共享的 HMAC secret |
| `rc.security.jwt-secret` | `RC_JWT_SECRET` 环境变量 | JWT 签名密钥（prod 必须注入） |

`application-prod.yml` 激活 `--spring.profiles.active=prod`，切 MySQL 8 + Redis，`sql.init.mode=never`（需手动执行 `schema.sql`）。

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
| `RC_RELAY_SECRET` | 内置 dev 值 | 与信令共享的令牌签名 secret（prod 必改） |
| `RC_RELAY_SESSION_TTL_SECONDS` | `120` | 会话席位 TTL |
| `RC_RELAY_NODE_ID` | `relay-1` | 节点 ID |
| `RC_RELAY_REGION` | `cn-east` | 地域标识 |
| `RC_RELAY_ADVERTISE_HOST` | `127.0.0.1` | 向信令广播的公网地址 |
| `RC_RELAY_SIGNALING_URL` | `http://127.0.0.1:8080/internal/relay-nodes/heartbeat` | 心跳上报接口 |

### 8.3 客户端

默认值硬编码于 `ClientApplication.buildClient`：

| 项 | 值 |
|----|----|
| REST 端口 | `8080` |
| 信令长连接 | `8443`（TLS + trustAll，dev 自签） |
| STUN 服务器 | `stun.l.google.com:19302` |
| 文件接收目录 | `~/rc-received` |
| 设备身份 | `~/.rc-client/identity.json`（设备码跨启动稳定） |

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

当前处于 **Phase 1 完成、Phase 2 进行中** 阶段：

- ✅ 四模块 Maven 骨架 + `common` 完整基座（协议 / 编解码 / 加密 / 模型 / 常量）
- ✅ 登录注册 + 心跳（REST + bcrypt + JWT）、SSO/OIDC 骨架
- ✅ 信令长连接 + 设备上报 + 会话路由 + 中继调度
- ✅ STUN 穿透 + UDP 打洞 + 连通性检查 + 端口预测
- ✅ 中继三协议兜底（UDP / TCP-TLS / WS）+ 密文透传 + 令牌校验
- ✅ 会话状态机 + 降级阶梯 + 平滑回切（make-before-break）
- ✅ AWT Robot 屏幕采集推流 + H.264 管线骨架 + 键鼠远控
- ✅ 文件互传 / 双向剪贴板 / 实时音频
- ✅ QUIC（kwik）接线替换裸 UDP
- ✅ 全局 QoS 监控 + 智能阈值 + 多地域中继就近调度
- ✅ 桌面端 JavaFX UI + 键鼠远控链路

> 详细进度与待办见 [`CLAUDE.md`](CLAUDE.md)；下一步为编译核对、H.264 硬编替换 JPEG 占位、DXGI 原生抓屏。

---

## 11. 文档索引

| 文档 | 内容 |
|------|------|
| [`远程控制软件开发文档.md`](远程控制软件开发文档.md) | 模块划分、协议、数据模型、流程、里程碑（实现以此为准） |
| [`远程控制软件底层链路设计-内网穿透与P2P中继架构.md`](远程控制软件底层链路设计-内网穿透与P2P中继架构.md) | NAT/STUN/ICE/打洞/中继/QoS 底层原理 |
| [`CLAUDE.md`](CLAUDE.md) | 开发约定、关键设计决策、当前进度 |

---

## License

[Apache-2.0](LICENSE)（如未单独提供 License 文件，默认保留所有权利）。
