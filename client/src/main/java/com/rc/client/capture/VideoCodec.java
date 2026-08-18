package com.rc.client.capture;

import java.awt.image.BufferedImage;

/**
 * 视频编解码抽象。屏幕采集后经此编码为可传输字节流，控制端解码还原。
 *
 * <p>Phase 2 目标为 H.264（关键帧 + 增量帧）；当前以 {@link JpegVideoCodec}（整帧 JPEG）
 * 占位实现，接口不变，后续替换 H.264 编码器（javacpp/jcodec 或 native）零侵入。</p>
 */
public interface VideoCodec {

    /** 编码后的视频帧（keyFrame 标记是否为关键帧 / IDR）。 */
    record EncodedFrame(byte[] data, boolean keyFrame, long ptsMs) {
    }

    /** 编码一帧；{@code forceKeyFrame} 强制输出关键帧（首帧 / 场景切换 / 请求重发时）。 */
    EncodedFrame encode(BufferedImage image, boolean forceKeyFrame);

    /** 解码一帧为图像；解码失败返回 {@code null}。 */
    BufferedImage decode(byte[] data, boolean keyFrame);
}
