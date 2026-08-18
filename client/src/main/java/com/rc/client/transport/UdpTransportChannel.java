package com.rc.client.transport;

import com.rc.client.ice.UdpSocket;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameFlags;
import com.rc.common.constant.FrameType;
import com.rc.common.constant.Thresholds;
import com.rc.common.model.ChannelInfo;
import com.rc.common.model.Endpoint;
import com.rc.common.protocol.PathType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2P UDP 数据面：复用 {@link IceAgent} 打洞产出的同一 socket，
 * 以 DataFrame 多通道收发 + NAT 保活 + listener 分发。
 *
 * <p>会话密钥（AES-256-GCM，由 E2EE 邀请派生）暂存于此，待采集/输入通道落地后
 * 在 {@link #send} / 入站解密处启用端到端加密。</p>
 */
public final class UdpTransportChannel implements TransportChannel {

    private static final Logger log = LoggerFactory.getLogger(UdpTransportChannel.class);

    private final UdpSocket socket;
    private final Endpoint peer;
    private final byte[] sessionKey;
    private final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final ScheduledExecutorService keepalive;
    private final ChannelInfo info = new ChannelInfo(PathType.P2P, 0, 0, 0);
    private volatile boolean closed;

    public UdpTransportChannel(UdpSocket socket, Endpoint peer, byte[] sessionKey) {
        this.socket = socket;
        this.peer = peer;
        this.sessionKey = sessionKey;
        this.keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-udp-keepalive");
            t.setDaemon(true);
            return t;
        });
        socket.setDataListener(this::onFrame);
        startKeepalive();
    }

    @Override
    public void send(ChannelType ch, byte[] payload) {
        if (closed) {
            return;
        }
        DataFrame frame = new DataFrame(ch, FrameType.DATA, FrameFlags.NONE,
                seq.getAndIncrement(), System.currentTimeMillis(), payload);
        socket.sendData(peer, frame);
    }

    @Override
    public void addListener(TransportListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(TransportListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ChannelInfo info() {
        return info;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        keepalive.shutdownNow();
        socket.setDataListener(null);
        socket.close();
        for (TransportListener listener : listeners) {
            try {
                listener.onClosed(null);
            } catch (Exception e) {
                log.warn("listener onClosed failed", e);
            }
        }
    }

    private void onFrame(DataFrame frame) {
        if (closed) {
            return;
        }
        for (TransportListener listener : listeners) {
            listener.onData(frame);
        }
    }

    private void startKeepalive() {
        keepalive.scheduleAtFixedRate(() -> {
            if (closed) {
                return;
            }
            DataFrame heartbeat = new DataFrame(ChannelType.CONTROL, FrameType.HEARTBEAT,
                    FrameFlags.NONE, seq.getAndIncrement(), System.currentTimeMillis(), new byte[0]);
            socket.sendData(peer, heartbeat);
        }, Thresholds.KEEPALIVE_HOME_MS, Thresholds.KEEPALIVE_HOME_MS, TimeUnit.MILLISECONDS);
    }
}
