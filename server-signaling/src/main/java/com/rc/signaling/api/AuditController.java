package com.rc.signaling.api;

import com.rc.signaling.service.AuditService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询 / 导出 / 归档（管理端）。本控制器整体由 {@code SecurityConfig} 限定
 * {@code ROLE_ADMIN} 访问（{@code /api/admin/**} → hasRole("ADMIN")）。
 */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResult<AuditPage> page(@RequestParam(defaultValue = "0") long offset,
                                     @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<Map<String, Object>> items = auditService.page(offset, bounded);
        return ApiResult.ok(new AuditPage(auditService.count(), items));
    }

    /** 全量导出 CSV 下载。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        byte[] body = auditService.exportCsv().getBytes(StandardCharsets.UTF_8);
        String disposition = ContentDisposition.attachment()
                .filename("audit.csv", StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(body);
    }

    /** 归档早于 {@code before}（epoch 毫秒）的日志到归档表，返回归档行数。 */
    @PostMapping("/archive")
    public ApiResult<Long> archive(@RequestParam long before) {
        return ApiResult.ok(auditService.archive(before));
    }

    public record AuditPage(long total, List<Map<String, Object>> items) {
    }
}
