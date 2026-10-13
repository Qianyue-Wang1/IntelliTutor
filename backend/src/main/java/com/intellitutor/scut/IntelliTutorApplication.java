package com.intellitutor.scut;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IntelliTutor Java 业务端主启动类
 *
 * <p>
 * 职责范围：用户鉴权、会话状态管理、消息队列生产/消费、
 * 跨端任务调度（HTTP 转发至 Python 推理端）以及 WebSocket 数据推送。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.intellitutor.scut.modules.**.mapper")
public class IntelliTutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelliTutorApplication.class, args);
    }
}
