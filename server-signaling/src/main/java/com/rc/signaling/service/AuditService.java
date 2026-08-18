package com.rc.signaling.service;

import com.rc.signaling.dao.AuditLogMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计日志服务（异步落库）。
 *
 * <p>业务线程调用 {@link #record} 仅入队即返回，由单后台线程批量写库，避免审计写放大拖慢
 * 登录/邀请等关键路径。有界队列（默认 10000）满时丢弃新事件并计数，保证审计不反向压垮核心链路。
 * <b>prod 落地：</b>本实现为单机内存队列 + MyBatis 落库；多节点 / 高吞吐时应替换为 MQ
 * （Kafka/RabbitMQ）生产者，由消费端统一入库，此处保留相同 {@link #record} 契约即可无缝切换。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final String ACTION_LOGIN = "login";
    public static final String ACTION_REGISTER = "register";
    public static final String ACTION_DEVICE_ONLINE = "device_online";
    public static final String ACTION_DEVICE_OFFLINE = "device_offline";
    public static final String ACTION_INVITE = "invite";
    public static final String ACTION_SESSION_START = "session_start";
    public static final String ACTION_SESSION_END = "session_end";
    public static final String ACTION_RELAY_ALLOC = "relay_alloc";
    public static final String ACTION_PATH_SWITCH = "path_switch";

    private static final int QUEUE_CAPACITY = 10000;
    private static final int BATCH_SIZE = 200;
    private static final long DRAIN_INTERVAL_MS = 200;

    private final AuditLogMapper mapper;
    private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean running = true;

    public AuditService(AuditLogMapper mapper) {
        this.mapper = mapper;
        this.writer = new Thread(this::drainLoop, "rc-audit-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /** 记录一条审计事件（非阻塞；user/device 可空）。 */
    public void record(Long userId, Long deviceId, String action, String detail) {
        Map<String, Object> entry = new HashMap<>(5);
        entry.put("userId", userId);
        entry.put("deviceId", deviceId);
        entry.put("action", action);
        entry.put("detail", detail == null ? "" : detail);
        entry.put("createdAt", System.currentTimeMillis());
        if (!queue.offer(entry)) {
            dropped.incrementAndGet();
        }
    }

    /** 记录无 user/device 归属的审计事件。 */
    public void record(String action, String detail) {
        record(null, null, action, detail);
    }

    /** 分页查询审计日志（按时间倒序）。 */
    public List<Map<String, Object>> page(long offset, int limit) {
        return mapper.page(offset, limit);
    }

    public long count() {
        return mapper.count();
    }

    /**
     * 全量导出为 CSV 文本（供管理端下载）。列：id,user_id,device_id,action,detail,created_at。
     * 大数据集应改为游标/分片流式写出，此处一次性拼装仅为 Phase 落地。
     */
    public String exportCsv() {
        List<Map<String, Object>> rows = mapper.exportAll();
        StringBuilder sb = new StringBuilder(rows.size() * 64);
        sb.append("id,user_id,device_id,action,detail,created_at\r\n");
        for (Map<String, Object> row : rows) {
            sb.append(csv(row.get("id"))).append(',')
                    .append(csv(row.get("userId"))).append(',')
                    .append(csv(row.get("deviceId"))).append(',')
                    .append(csv(row.get("action"))).append(',')
                    .append(csv(row.get("detail"))).append(',')
                    .append(csv(row.get("createdAt"))).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * 归档：把 {@code created_at < before} 的日志从主表迁入归档表（先插后删，事务保证原子）。
     * 返回归档行数。
     */
    @Transactional
    public long archive(long before) {
        int archived = mapper.archiveBefore(before);
        mapper.deleteBefore(before);
        log.info("audit archived {} entries before {}", archived, before);
        return archived;
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private void drainLoop() {
        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                queue.drainTo(batch, BATCH_SIZE);
                if (!batch.isEmpty()) {
                    for (Map<String, Object> entry : batch) {
                        try {
                            mapper.insert(entry);
                        } catch (Exception e) {
                            log.warn("audit insert failed, dropping one entry: {}", e.getMessage());
                        }
                    }
                    batch.clear();
                } else {
                    TimeUnit.MILLISECONDS.sleep(DRAIN_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 停机前尽力排空
        queue.drainTo(batch);
        for (Map<String, Object> entry : batch) {
            try {
                mapper.insert(entry);
            } catch (Exception e) {
                log.warn("audit final-drain insert failed: {}", e.getMessage());
            }
        }
        if (dropped.get() > 0) {
            log.warn("audit dropped {} events due to queue full", dropped.get());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        writer.interrupt();
    }
}
