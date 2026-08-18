package com.rc.client.app;

import com.rc.client.audio.AudioReceiver;
import com.rc.client.audio.AudioStreamer;
import com.rc.client.capture.DesktopDuplicationCapturer;
import com.rc.client.capture.DxgiDesktopDuplicationCapturer;
import com.rc.client.capture.MotionJpegVideoCodec;
import com.rc.client.capture.ScreenStreamer;
import com.rc.client.capture.VideoReceiver;
import com.rc.client.clipboard.ClipboardService;
import com.rc.client.control.InputController;
import com.rc.client.control.InputInjector;
import com.rc.client.file.FileTransferService;
import com.rc.client.security.DeviceIdentity;
import com.rc.client.security.DeviceIdentityStore;
import com.rc.client.signaling.AuthApiClient;
import com.rc.client.signaling.SignalingClientConfig;
import com.rc.client.transport.TransportChannel;
import com.rc.common.constant.ErrorCode;
import com.rc.common.model.ChannelInfo;
import com.rc.common.model.Endpoint;
import com.rc.common.protocol.PathType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/**
 * 桌面端门面：把「账户 + 设备身份 + 信令连接 + 会话编排 + 数据面业务」串成可供 JavaFX UI
 * 调用的高层 API。
 *
 * <p>UI 只需关注 {@link UiListener} 回调与 {@link #login}/{@link #register}/{@link #start}/
 * {@link #connect}/{@link #acceptInvite}/{@link #hangup} 等动作；角色差异（控制端 / 被控端）
 * 与数据面服务（采集推流 / 解码渲染 / 键鼠注入 / 文件 / 剪贴板 / 音频）在此统一装配。</p>
 */
public final class RemoteControlClient implements ClientConnectionManager.SessionListener {

    private static final Logger log = LoggerFactory.getLogger(RemoteControlClient.class);

    /** UI 回调（可能来自传输 / 后台线程，实现方需自行切回 FX 线程）。 */
    public interface UiListener {
        void onState(String message);

        void onDeviceReady(String deviceCode);

        void onSessionConnected(boolean controller);

        void onVideoFrame(BufferedImage image);

        void onInvite(String controllerDeviceCode, long sessionId);

        void onSessionEnded(String reason);

        void onError(String message);

        void onPathChanged(String pathType);

        default void onFileEvent(String message) {
        }
    }

    private final String baseUrl;
    private final SignalingClientConfig signalingConfig;
    private final List<Endpoint> stunServers;
    private final Path receiveDir;
    private final UiListener uiListener;
    private final DeviceIdentityStore identityStore;

    private volatile String accessToken;
    private volatile DeviceIdentity identity;
    private volatile ClientConnectionManager connectionManager;

    // 数据面（随会话建立 / 结束装配与回收）
    private volatile TransportChannel channel;
    private volatile boolean controller;
    private volatile InputController inputController;
    private volatile ScreenStreamer screenStreamer;
    private volatile AudioStreamer audioStreamer;
    private volatile InputInjector inputInjector;
    private volatile FileTransferService fileTransfer;
    private volatile ClipboardService clipboard;
    private volatile AudioReceiver audioReceiver;
    private volatile VideoReceiver videoReceiver;

    public RemoteControlClient(String baseUrl,
                               SignalingClientConfig signalingConfig,
                               List<Endpoint> stunServers,
                               Path receiveDir,
                               UiListener uiListener) {
        this.baseUrl = baseUrl;
        this.signalingConfig = signalingConfig;
        this.stunServers = stunServers;
        this.receiveDir = receiveDir;
        this.uiListener = uiListener;
        this.identityStore = new DeviceIdentityStore();
    }

    // ---------- 账户 ----------

    public void login(String username, String password) throws Exception {
        AuthApiClient api = new AuthApiClient(baseUrl);
        AuthApiClient.LoginResult result = api.login(username, password);
        this.accessToken = result.accessToken();
    }

    public void register(String username, String password) throws Exception {
        new AuthApiClient(baseUrl).register(username, password);
    }

    // ---------- 设备上线 ----------

    /** 加载（或生成）设备身份并启动信令连接 + 设备注册。 */
    public void start() {
        identity = identityStore.load();
        connectionManager = new ClientConnectionManager(identity, signalingConfig, stunServers,
                baseUrl, accessToken, this);
        connectionManager.start();
        uiListener.onDeviceReady(identity.deviceCode());
    }

    /** 设置本机连接密码（被控端校验邀请用；仅存内存）。 */
    public void setConnectPassword(String password) {
        if (identity != null) {
            identity.setConnectPassword(password);
        }
    }

    // ---------- 会话 ----------

    /** 控制端：连接远端设备。 */
    public void connect(String remoteDeviceCode, String password) {
        if (connectionManager == null) {
            uiListener.onError("client not started");
            return;
        }
        uiListener.onState("正在连接 " + remoteDeviceCode + " ...");
        connectionManager.connect(remoteDeviceCode, password);
    }

    /** 被控端：响应对端邀请。 */
    public void acceptInvite(long sessionId, boolean accept) {
        if (connectionManager != null) {
            connectionManager.acceptInvite(sessionId, accept);
        }
    }

    /** 主动挂断当前会话。 */
    public void hangup() {
        if (connectionManager != null) {
            connectionManager.hangup();
        }
    }

    // ---------- 数据面操作 ----------

    /** 发送本地文件给对端（双向），落到对端默认接收目录。 */
    public void sendFile(Path file) {
        FileTransferService ft = fileTransfer;
        if (ft != null) {
            ft.sendFile(file);
        }
    }

    /** 发送本地文件，指定对端相对保存路径（可含子目录，空则用文件名）。 */
    public void sendFile(Path file, String targetPath) {
        FileTransferService ft = fileTransfer;
        if (ft != null) {
            ft.sendFile(file, targetPath);
        }
    }

    /** 开启 / 关闭本机麦克风采集（仅被控端）。 */
    public void setAudioCaptureEnabled(boolean enabled) {
        if (enabled) {
            if (audioStreamer == null && channel != null) {
                audioStreamer = new AudioStreamer(channel);
                audioStreamer.start();
            }
        } else if (audioStreamer != null) {
            audioStreamer.close();
            audioStreamer = null;
        }
    }

    public InputController inputController() {
        return inputController;
    }

    public boolean isController() {
        return controller;
    }

    public String deviceCode() {
        return identity == null ? "" : identity.deviceCode();
    }

    // ---------- SessionListener ----------

    @Override
    public void onConnected(String remoteDeviceCode, TransportChannel ch, boolean controller) {
        this.channel = ch;
        this.controller = controller;
        setupDataPlane(ch, controller);
        uiListener.onState(controller ? "已连接，正在控制 " + remoteDeviceCode : "已被 " + remoteDeviceCode + " 控制");
        uiListener.onSessionConnected(controller);
    }

    @Override
    public void onFailed(ErrorCode code, String message) {
        cleanupDataPlane();
        uiListener.onError(code.rcCode() + " " + message);
    }

    @Override
    public void onRemoteEnded(String reason) {
        cleanupDataPlane();
        uiListener.onSessionEnded(reason == null || reason.isBlank() ? "会话结束" : reason);
    }

    @Override
    public void onPathChanged(PathType pathType, ChannelInfo info) {
        uiListener.onPathChanged(pathType.name());
    }

    @Override
    public void onChannelSwitched(TransportChannel newChannel, boolean controller) {
        rebindDataPlane(newChannel, controller);
    }

    /** 回切（make-before-break）后把数据面业务重绑到新 channel，并强制关键帧重启解码。 */
    private void rebindDataPlane(TransportChannel newChannel, boolean controller) {
        closeDataPlaneServices();
        this.channel = newChannel;
        this.controller = controller;
        setupDataPlane(newChannel, controller);
        if (!controller && screenStreamer != null) {
            screenStreamer.requestKeyFrame();
        }
    }

    @Override
    public void onInvite(String controllerDeviceCode, long sessionId) {
        uiListener.onInvite(controllerDeviceCode, sessionId);
    }

    // ---------- 数据面装配 ----------

    private void setupDataPlane(TransportChannel ch, boolean controller) {
        clipboard = new ClipboardService(ch);
        clipboard.startAutoSync();
        ch.addListener(clipboard);

        fileTransfer = new FileTransferService(ch, receiveDir, new FileTransferService.Listener() {
            @Override
            public void onOffer(int transferId, String fileName, long fileSize) {
                uiListener.onFileEvent("接收文件: " + fileName + " (" + fileSize + " B)");
            }

            @Override
            public void onProgress(int transferId, long received, long total) {
                // 高频进度仅日志，避免 UI 抖动
            }

            @Override
            public void onComplete(int transferId, Path savedTo) {
                uiListener.onFileEvent("文件已保存: " + savedTo);
            }

            @Override
            public void onError(int transferId, String message) {
                uiListener.onFileEvent("文件传输失败: " + message);
            }
        });
        ch.addListener(fileTransfer);

        if (controller) {
            if (inputController == null) {
                inputController = new InputController(ch);
            } else {
                inputController.rebind(ch);
            }
            videoReceiver = new VideoReceiver(ch, new MotionJpegVideoCodec(),
                    (img, pts) -> uiListener.onVideoFrame(img));
            ch.addListener(videoReceiver);
            audioReceiver = new AudioReceiver();
            ch.addListener(audioReceiver);
        } else {
            try {
                DesktopDuplicationCapturer capturer = createCapturer();
                screenStreamer = new ScreenStreamer(ch, capturer, new MotionJpegVideoCodec());
                screenStreamer.start();
            } catch (Exception e) {
                log.warn("screen capture unavailable, streaming disabled: {}", e.getMessage());
            }
            try {
                inputInjector = new InputInjector();
                ch.addListener(inputInjector);
            } catch (Exception e) {
                log.warn("input injection unavailable: {}", e.getMessage());
            }
        }
    }

    private void cleanupDataPlane() {
        closeDataPlaneServices();
        channel = null;
        controller = false;
        inputController = null;
        inputInjector = null;
        fileTransfer = null;
        audioReceiver = null;
    }

    /** 关闭持有独立资源的数据面服务（供会话结束与回切重绑复用）。 */
    private void closeDataPlaneServices() {
        if (screenStreamer != null) {
            screenStreamer.close();
            screenStreamer = null;
        }
        if (audioStreamer != null) {
            audioStreamer.close();
            audioStreamer = null;
        }
        if (videoReceiver != null) {
            videoReceiver.close();
            videoReceiver = null;
        }
        if (clipboard != null) {
            clipboard.close();
            clipboard = null;
        }
        if (fileTransfer != null) {
            fileTransfer.close();
        }
    }

    /** 选择屏幕采集实现（Phase 2：DXGI 原生优先，失败回退 AWT）。 */
    private DesktopDuplicationCapturer createCapturer() {
        DxgiDesktopDuplicationCapturer dxgi = DxgiDesktopDuplicationCapturer.tryCreate();
        return dxgi != null ? dxgi : DesktopDuplicationCapturer.awtFallback();
    }

    /** 退出时统一回收。 */
    public void shutdown() {
        cleanupDataPlane();
        if (connectionManager != null) {
            connectionManager.stop();
        }
    }
}
