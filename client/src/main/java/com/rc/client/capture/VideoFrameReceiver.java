package com.rc.client.capture;

import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * 控制端视频帧接收器：以 {@link TransportListener} 身份挂在 {@code TransportChannel} 上，
 * 过滤 {@link ChannelType#VIDEO} 帧并解码为 {@link BufferedImage}，回调 {@link FrameListener}。
 *
 * <p>Phase 1 仅做解码 + 回调（上层接 JavaFX ImageView 显示）；渲染/缩放/丢帧策略 Phase 2 完善。</p>
 */
public final class VideoFrameReceiver implements TransportListener {

    private static final Logger log = LoggerFactory.getLogger(VideoFrameReceiver.class);

    /** 视频帧解码回调（上层据此渲染）。 */
    public interface FrameListener {
        void onFrame(BufferedImage image);
    }

    private final FrameListener listener;

    public VideoFrameReceiver(FrameListener listener) {
        this.listener = listener;
    }

    @Override
    public void onData(DataFrame frame) {
        if (frame.channel() != ChannelType.VIDEO) {
            return;
        }
        try {
            BufferedImage image = ScreenCodec.decode(frame.payload());
            if (image != null) {
                listener.onFrame(image);
            }
        } catch (Exception e) {
            log.debug("skip undecodable video frame: {}", e.getMessage());
        }
    }

    @Override
    public void onClosed(Throwable cause) {
        // 视频流随数据面结束；无独立资源需释放。
    }
}
