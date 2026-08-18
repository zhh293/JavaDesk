package com.rc.client.file;

import com.rc.client.transport.TransportChannel;
import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件互传服务：发送端分块推流（OFFER → CHUNK×N → COMPLETE），接收端按 transferId
 * 关联分片、按 offset 落盘重组。以 {@link TransportListener} 身份挂在 {@code TransportChannel}
 * 上接收 {@link ChannelType#FILE} 帧。
 *
 * <p>可靠语义由承载通道保证（Phase 2 的 QUIC stream 可靠通道；当前裸 UDP 下为尽力而为）。
 * 单个 transferId 串行发送，chunk 顺序落盘，避免并发写同一文件。</p>
 */
public final class FileTransferService implements TransportListener {

    private static final Logger log = LoggerFactory.getLogger(FileTransferService.class);

    /** 分块大小（64 KiB），兼顾 UDP 承载与重组效率。 */
    private static final int CHUNK_SIZE = 64 * 1024;

    /** 文件传输进度 / 结果回调。 */
    public interface Listener {
        void onOffer(int transferId, String fileName, long fileSize);

        void onProgress(int transferId, long received, long total);

        void onComplete(int transferId, Path savedTo);

        void onError(int transferId, String message);
    }

    private final TransportChannel channel;
    private final Path receiveDir;
    private final Listener listener;
    private final AtomicInteger transferSeq = new AtomicInteger();
    private final Map<Integer, ReceiveCtx> receiving = new ConcurrentHashMap<>();

    public FileTransferService(TransportChannel channel, Path receiveDir, Listener listener) {
        this.channel = channel;
        this.receiveDir = receiveDir;
        this.listener = listener;
    }

    /** 发送本地文件（异步，不阻塞调用线程）。 */
    public void sendFile(Path file) {
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rc-file-send");
            t.setDaemon(true);
            return t;
        }).execute(() -> doSend(file));
    }

    private void doSend(Path file) {
        int transferId = transferSeq.incrementAndGet();
        String fileName = file.getFileName().toString();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long fileSize = raf.length();
            channel.send(ChannelType.FILE, FileTransferCodec.offer(transferId, fileName, fileSize));
            byte[] buf = new byte[CHUNK_SIZE];
            long offset = 0;
            int n;
            while ((n = raf.read(buf)) > 0) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                channel.send(ChannelType.FILE, FileTransferCodec.chunk(transferId, offset, chunk));
                offset += n;
            }
            channel.send(ChannelType.FILE, FileTransferCodec.complete(transferId, FileTransferCodec.STATUS_OK));
            log.info("file sent: transferId={} name={} size={}", transferId, fileName, fileSize);
        } catch (Exception e) {
            log.warn("file send failed: {}", file, e);
            channel.send(ChannelType.FILE, FileTransferCodec.complete(transferId, FileTransferCodec.STATUS_ERROR));
            if (listener != null) {
                listener.onError(transferId, e.getMessage());
            }
        }
    }

    @Override
    public void onData(DataFrame frame) {
        if (frame.channel() != ChannelType.FILE) {
            return;
        }
        FileTransferCodec.FileMessage msg = FileTransferCodec.decode(frame.payload());
        if (msg == null) {
            return;
        }
        switch (msg.type()) {
            case FileTransferCodec.TYPE_OFFER -> onOffer(msg);
            case FileTransferCodec.TYPE_CHUNK -> onChunk(msg);
            case FileTransferCodec.TYPE_COMPLETE -> onComplete(msg);
            default -> log.debug("unknown file message type: {}", msg.type());
        }
    }

    private void onOffer(FileTransferCodec.FileMessage msg) {
        Path target = receiveDir.resolve(sanitize(msg.fileName()));
        try {
            Files.createDirectories(receiveDir);
            RandomAccessFile out = new RandomAccessFile(target.toFile(), "rw");
            out.setLength(msg.fileSize());
            receiving.put(msg.transferId(), new ReceiveCtx(msg.fileSize(), out, target));
            if (listener != null) {
                listener.onOffer(msg.transferId(), msg.fileName(), msg.fileSize());
            }
            log.info("file offer: transferId={} name={} size={}", msg.transferId(), msg.fileName(), msg.fileSize());
        } catch (IOException e) {
            log.warn("failed to open receive file: {}", target, e);
            if (listener != null) {
                listener.onError(msg.transferId(), "open failed: " + e.getMessage());
            }
        }
    }

    private void onChunk(FileTransferCodec.FileMessage msg) {
        ReceiveCtx ctx = receiving.get(msg.transferId());
        if (ctx == null) {
            log.debug("chunk for unknown transfer ignored: transferId={}", msg.transferId());
            return;
        }
        try {
            ctx.out().seek(msg.offset());
            ctx.out().write(msg.data());
            long received = ctx.received() + msg.data().length;
            ctx.updateReceived(received);
            if (listener != null) {
                listener.onProgress(msg.transferId(), received, ctx.fileSize());
            }
        } catch (IOException e) {
            log.warn("failed to write chunk: transferId={}", msg.transferId(), e);
            receiving.remove(msg.transferId());
            closeQuietly(ctx.out());
            if (listener != null) {
                listener.onError(msg.transferId(), "write failed: " + e.getMessage());
            }
        }
    }

    private void onComplete(FileTransferCodec.FileMessage msg) {
        ReceiveCtx ctx = receiving.remove(msg.transferId());
        if (ctx == null) {
            log.debug("complete for unknown transfer ignored: transferId={}", msg.transferId());
            return;
        }
        closeQuietly(ctx.out());
        if (msg.status() == FileTransferCodec.STATUS_OK) {
            if (listener != null) {
                listener.onComplete(msg.transferId(), ctx.path());
            }
            log.info("file received: transferId={} path={}", msg.transferId(), ctx.path());
        } else {
            try {
                Files.deleteIfExists(ctx.path());
            } catch (IOException ignored) {
                // 忽略清理失败
            }
            if (listener != null) {
                listener.onError(msg.transferId(), "sender reported failure");
            }
        }
    }

    @Override
    public void onClosed(Throwable cause) {
        receiving.values().forEach(ctx -> closeQuietly(ctx.out()));
        receiving.clear();
    }

    private static String sanitize(String fileName) {
        String name = fileName.replaceAll("[\\\\/]", "_");
        return name.isBlank() ? "unnamed" : name;
    }

    private static void closeQuietly(RandomAccessFile out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // 忽略关闭失败
        }
    }

    private static final class ReceiveCtx {
        private final long fileSize;
        private final RandomAccessFile out;
        private final Path path;
        private long received;

        ReceiveCtx(long fileSize, RandomAccessFile out, Path path) {
            this.fileSize = fileSize;
            this.out = out;
            this.path = path;
        }

        long fileSize() {
            return fileSize;
        }

        RandomAccessFile out() {
            return out;
        }

        Path path() {
            return path;
        }

        long received() {
            return received;
        }

        void updateReceived(long received) {
            this.received = received;
        }
    }
}
