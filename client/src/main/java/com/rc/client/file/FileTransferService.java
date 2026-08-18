package com.rc.client.file;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.rc.client.transport.TransportChannel;
import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * 文件互传服务：发送端分块推流（OFFER → CHUNK×N → COMPLETE），接收端按 transferId
 * 关联分片、按 offset 落盘重组、整文件 CRC 校验，并以 ACK/NACK 反馈发送端重传。
 *
 * <p>可靠语义分两层：承载通道保证有序（QUIC stream 可靠通道），应用层再叠加
 * <b>文件级 CRC 校验 + 缺失分块 NACK 重传</b>，以覆盖裸 UDP 尽力而为路径的丢块；
 * 半截传输由 Caffeine {@code expireAfterAccess} 自动清理，避免 Map 泄漏。</p>
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

    /** 发送线程（复用，避免每文件新建线程池泄漏；单通道串行发送与既有语义一致）。 */
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rc-file-send");
        t.setDaemon(true);
        return t;
    });

    /** 接收端上下文：Caffeine 过期自动关闭文件 + 删除半截文件。 */
    private final Cache<Integer, ReceiveCtx> receiving = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .removalListener((Integer id, ReceiveCtx ctx, RemovalCause cause) -> {
                closeQuietly(ctx.out());
                if (cause != RemovalCause.EXPLICIT && cause != RemovalCause.REPLACED) {
                    try {
                        Files.deleteIfExists(ctx.path());
                    } catch (IOException ignored) {
                        // 忽略清理失败
                    }
                    if (listener != null) {
                        listener.onError(id, "transfer timed out");
                    }
                }
            })
            .build();

    /** 发送端上下文（仅持文件路径供 NACK 重传，过期自动清理，无打开句柄）。 */
    private final Cache<Integer, SendCtx> sending = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(1))
            .build();

    public FileTransferService(TransportChannel channel, Path receiveDir, Listener listener) {
        this.channel = channel;
        this.receiveDir = receiveDir;
        this.listener = listener;
    }

    /** 发送本地文件到对端默认接收目录（文件名作相对路径）。 */
    public void sendFile(Path file) {
        sendFile(file, null);
    }

    /** 发送本地文件；{@code targetPath} 为对端相对保存路径（可含子目录，空则用文件名）。 */
    public void sendFile(Path file, String targetPath) {
        sendExecutor.execute(() -> doSend(file, targetPath));
    }

    private void doSend(Path file, String targetPath) {
        int transferId = transferSeq.incrementAndGet();
        String fileName = file.getFileName() == null ? "unnamed" : file.getFileName().toString();
        try {
            long fileSize = Files.size(file);
            long crc = crcOf(file);
            channel.send(ChannelType.FILE, FileTransferCodec.offer(transferId, fileName, fileSize, crc, targetPath));
            byte[] buf = new byte[CHUNK_SIZE];
            long offset = 0;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                int n;
                while ((n = raf.read(buf)) > 0) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    channel.send(ChannelType.FILE, FileTransferCodec.chunk(transferId, offset, chunk));
                    offset += n;
                }
            }
            channel.send(ChannelType.FILE, FileTransferCodec.complete(transferId, FileTransferCodec.STATUS_OK));
            sending.put(transferId, new SendCtx(file));
            log.info("file sent: transferId={} name={} size={} crc={}", transferId, fileName, fileSize, crc);
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
            case FileTransferCodec.TYPE_ACK -> onAck(msg);
            case FileTransferCodec.TYPE_NACK -> onNack(msg);
            default -> log.debug("unknown file message type: {}", msg.type());
        }
    }

    private void onOffer(FileTransferCodec.FileMessage msg) {
        try {
            Path target = resolveTarget(msg.targetPath(), msg.fileName());
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            RandomAccessFile out = new RandomAccessFile(target.toFile(), "rw");
            out.setLength(msg.fileSize());
            receiving.put(msg.transferId(), new ReceiveCtx(msg.fileSize(), msg.fileCrc(), out, target));
            if (listener != null) {
                listener.onOffer(msg.transferId(), msg.fileName(), msg.fileSize());
            }
            log.info("file offer: transferId={} name={} size={} target={}",
                    msg.transferId(), msg.fileName(), msg.fileSize(), target);
        } catch (Exception e) {
            log.warn("failed to open receive file: {}", e.getMessage());
            channel.send(ChannelType.FILE, FileTransferCodec.ack(msg.transferId(), FileTransferCodec.STATUS_ERROR));
            if (listener != null) {
                listener.onError(msg.transferId(), "open failed: " + e.getMessage());
            }
        }
    }

    private void onChunk(FileTransferCodec.FileMessage msg) {
        ReceiveCtx ctx = receiving.getIfPresent(msg.transferId());
        if (ctx == null) {
            log.debug("chunk for unknown transfer ignored: transferId={}", msg.transferId());
            return;
        }
        try {
            ctx.out().seek(msg.offset());
            ctx.out().write(msg.data());
            ctx.markChunk(msg.offset());
            if (listener != null) {
                listener.onProgress(msg.transferId(), ctx.receivedBytes(), ctx.fileSize());
            }
            maybeFinalize(msg.transferId(), ctx);
        } catch (IOException e) {
            log.warn("failed to write chunk: transferId={}", msg.transferId(), e);
            receiving.invalidate(msg.transferId());
            if (listener != null) {
                listener.onError(msg.transferId(), "write failed: " + e.getMessage());
            }
        }
    }

    private void onComplete(FileTransferCodec.FileMessage msg) {
        ReceiveCtx ctx = receiving.getIfPresent(msg.transferId());
        if (ctx == null) {
            log.debug("complete for unknown transfer ignored: transferId={}", msg.transferId());
            return;
        }
        ctx.markComplete();
        if (!ctx.allReceived()) {
            BitSet missing = ctx.missing();
            for (int i = missing.nextSetBit(0); i >= 0; i = missing.nextSetBit(i + 1)) {
                channel.send(ChannelType.FILE, FileTransferCodec.nack(msg.transferId(), (long) i * CHUNK_SIZE));
            }
            log.info("file incomplete, NACK {} chunks: transferId={}", missing.cardinality(), msg.transferId());
            return;
        }
        maybeFinalize(msg.transferId(), ctx);
    }

    private void onAck(FileTransferCodec.FileMessage msg) {
        sending.invalidate(msg.transferId());
        if (msg.status() == FileTransferCodec.STATUS_OK) {
            log.info("file acknowledged: transferId={}", msg.transferId());
        } else {
            log.warn("receiver reported failure: transferId={}", msg.transferId());
            if (listener != null) {
                listener.onError(msg.transferId(), "receiver rejected file");
            }
        }
    }

    private void onNack(FileTransferCodec.FileMessage msg) {
        SendCtx ctx = sending.getIfPresent(msg.transferId());
        if (ctx == null) {
            log.debug("nack for unknown send ignored: transferId={}", msg.transferId());
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(ctx.file().toFile(), "r")) {
            long offset = msg.offset();
            if (offset < 0 || offset >= raf.length()) {
                return;
            }
            raf.seek(offset);
            byte[] buf = new byte[CHUNK_SIZE];
            int n = raf.read(buf);
            if (n <= 0) {
                return;
            }
            byte[] chunk = new byte[n];
            System.arraycopy(buf, 0, chunk, 0, n);
            channel.send(ChannelType.FILE, FileTransferCodec.chunk(msg.transferId(), offset, chunk));
            log.debug("chunk retransmitted: transferId={} offset={}", msg.transferId(), offset);
        } catch (IOException e) {
            log.warn("failed to retransmit chunk: transferId={} offset={}", msg.transferId(), msg.offset(), e);
        }
    }

    /** 收到全部数据块且 COMPLETE 已到后，校验 CRC 并回执。 */
    private void maybeFinalize(int transferId, ReceiveCtx ctx) {
        if (!ctx.completeSeen() || !ctx.allReceived()) {
            return;
        }
        closeQuietly(ctx.out());
        long actual = crcOf(ctx.path());
        receiving.invalidate(transferId);
        if (actual == ctx.expectedCrc()) {
            channel.send(ChannelType.FILE, FileTransferCodec.ack(transferId, FileTransferCodec.STATUS_OK));
            if (listener != null) {
                listener.onComplete(transferId, ctx.path());
            }
            log.info("file received: transferId={} path={}", transferId, ctx.path());
        } else {
            channel.send(ChannelType.FILE, FileTransferCodec.ack(transferId, FileTransferCodec.STATUS_ERROR));
            try {
                Files.deleteIfExists(ctx.path());
            } catch (IOException ignored) {
                // 忽略清理失败
            }
            if (listener != null) {
                listener.onError(transferId, "checksum mismatch");
            }
            log.warn("file checksum mismatch: transferId={} path={}", transferId, ctx.path());
        }
    }

    @Override
    public void onClosed(Throwable cause) {
        close();
    }

    /** 释放发送线程与接收/发送上下文。 */
    public void close() {
        sendExecutor.shutdownNow();
        receiving.invalidateAll();
        sending.invalidateAll();
    }

    /** 解析对端相对保存路径并做越界防护（拒绝绝对路径与 {@code ..} 穿越）。 */
    private Path resolveTarget(String targetPath, String fileName) throws IOException {
        String rel = (targetPath == null || targetPath.isBlank()) ? sanitize(fileName) : targetPath;
        Path base = receiveDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(rel).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("path escapes receive dir: " + rel);
        }
        return resolved;
    }

    private static String sanitize(String fileName) {
        String name = fileName.replaceAll("[\\\\/]", "_");
        return name.isBlank() ? "unnamed" : name;
    }

    private static long crcOf(Path file) {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[CHUNK_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        } catch (IOException e) {
            return -1;
        }
        return crc.getValue();
    }

    private static void closeQuietly(RandomAccessFile out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // 忽略关闭失败
        }
    }

    private record SendCtx(Path file) {
    }

    private static final class ReceiveCtx {
        private final long fileSize;
        private final long expectedCrc;
        private final RandomAccessFile out;
        private final Path path;
        private final int chunkCount;
        private final BitSet receivedChunks = new BitSet();
        private volatile boolean completeSeen;

        ReceiveCtx(long fileSize, long expectedCrc, RandomAccessFile out, Path path) {
            this.fileSize = fileSize;
            this.expectedCrc = expectedCrc;
            this.out = out;
            this.path = path;
            this.chunkCount = (int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
        }

        long fileSize() {
            return fileSize;
        }

        long expectedCrc() {
            return expectedCrc;
        }

        RandomAccessFile out() {
            return out;
        }

        Path path() {
            return path;
        }

        boolean completeSeen() {
            return completeSeen;
        }

        void markComplete() {
            this.completeSeen = true;
        }

        void markChunk(long offset) {
            receivedChunks.set((int) (offset / CHUNK_SIZE));
        }

        boolean allReceived() {
            return receivedChunks.cardinality() == chunkCount;
        }

        long receivedBytes() {
            return Math.min((long) receivedChunks.cardinality() * CHUNK_SIZE, fileSize);
        }

        BitSet missing() {
            BitSet m = new BitSet(chunkCount);
            m.set(0, chunkCount);
            m.andNot(receivedChunks);
            return m;
        }
    }
}
