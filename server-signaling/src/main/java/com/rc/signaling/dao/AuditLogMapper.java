package com.rc.signaling.dao;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * {@code audit_log} 表访问（MyBatis 注解映射）。审计日志异步落库，本 Mapper 提供
 * 插入、分页查询、全量导出与归档；写入由 {@code AuditService} 后台线程批量执行，
 * 避免阻塞业务线程。
 */
@Mapper
public interface AuditLogMapper {

    @Insert("INSERT INTO audit_log (user_id, device_id, action, detail, created_at) "
            + "VALUES (#{userId}, #{deviceId}, #{action}, #{detail}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Map<String, Object> entry);

    @Select("SELECT id, user_id, device_id, action, detail, created_at "
            + "FROM audit_log ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> page(@Param("offset") long offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM audit_log")
    long count();

    /** 全量导出（按 id 升序，别名统一为 camelCase；超大数据集应改游标/分片流式导出）。 */
    @Select("SELECT id, user_id AS userId, device_id AS deviceId, action, detail, created_at AS createdAt "
            + "FROM audit_log ORDER BY id ASC")
    List<Map<String, Object>> exportAll();

    /** 归档：把 {@code created_at < before} 的行移入归档表（保留原始 id），返回迁移行数。 */
    @Insert("INSERT INTO audit_log_archive (id, user_id, device_id, action, detail, created_at) "
            + "SELECT id, user_id, device_id, action, detail, created_at "
            + "FROM audit_log WHERE created_at < #{before}")
    int archiveBefore(@Param("before") long before);

    /** 删除主表中 {@code created_at < before} 的行（归档成功后调用）。 */
    @Delete("DELETE FROM audit_log WHERE created_at < #{before}")
    int deleteBefore(@Param("before") long before);
}
