package com.rc.signaling;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 信令服务器入口（REST + Netty 长连接 + 会话编排 + 中继调度）。
 */
@SpringBootApplication
@MapperScan("com.rc.signaling.dao")
public class SignalingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignalingApplication.class, args);
    }
}
