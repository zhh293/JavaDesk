-- 远程控制软件数据模型 DDL（对应开发文档 §9.2）
-- 兼容 H2(MODE=MySQL) 与 MySQL 8；时间字段统一 BIGINT（epoch 毫秒），对应模型 Long。
-- prod 环境请用本文件在 MySQL 建库（后续可接入 Flyway/Liquibase 迁移）。

CREATE TABLE IF NOT EXISTS `user` (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(128),
    sso_subject   VARCHAR(128) UNIQUE,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',  -- USER / ADMIN，管理员经 SQL 提升：UPDATE `user` SET role='ADMIN' WHERE username='...'
    created_at    BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS `device` (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                BIGINT       NOT NULL,
    device_code            VARCHAR(64)  NOT NULL UNIQUE,
    device_name            VARCHAR(128),
    os                     VARCHAR(64),
    version                VARCHAR(32),
    connect_password_hash  VARCHAR(128),
    device_public_key      TEXT,
    public_key_fingerprint VARCHAR(64),
    nat_type               TINYINT      NOT NULL DEFAULT 0,
    last_online_at         BIGINT,
    status                 TINYINT      NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS `session` (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(64) NOT NULL UNIQUE,
    controller_id BIGINT,
    agent_id      BIGINT,
    status        TINYINT     NOT NULL DEFAULT 0,
    path_type     TINYINT     NOT NULL DEFAULT 0,
    relay_node_id BIGINT,
    created_at    BIGINT,
    ended_at      BIGINT
);

CREATE TABLE IF NOT EXISTS `audit_log` (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT,
    device_id  BIGINT,
    action     VARCHAR(64),
    detail     TEXT,
    created_at BIGINT
);

-- 审计归档表（保留原始 id，避免与主表自增冲突；归档为「先插入归档、后删除主表」两步）
CREATE TABLE IF NOT EXISTS `audit_log_archive` (
    id         BIGINT PRIMARY KEY,
    user_id    BIGINT,
    device_id  BIGINT,
    action     VARCHAR(64),
    detail     TEXT,
    created_at BIGINT
);

CREATE TABLE IF NOT EXISTS `relay_node` (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    node_id           VARCHAR(64)  NOT NULL UNIQUE,
    host              VARCHAR(128),
    region            VARCHAR(32),
    udp_port          INT,
    tcp_port          INT,
    ws_port           INT,
    tls               TINYINT      NOT NULL DEFAULT 0,
    load_ratio        FLOAT,
    status            TINYINT      NOT NULL DEFAULT 0,
    last_heartbeat_at BIGINT
);
