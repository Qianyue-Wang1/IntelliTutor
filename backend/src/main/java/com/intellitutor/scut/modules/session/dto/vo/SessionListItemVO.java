package com.intellitutor.scut.modules.session.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话列表条目 VO，对应前端 {@code SessionListItem} 类型。
 *
 * <p>
 * 前端字段期望：session_id, question, type, created_at, last_accessed。
 * 由于前端原本直接对接 Python，其 {@code SessionListItem} 接口采用了 Python 的字段命名。
 * 此处通过 {@code @JsonProperty} 精确对齐前端期望。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionListItemVO {

    /**
     * Java 侧会话 UUID，对应前端 session_id 字段。
     */
    @JsonProperty("session_id")
    private String id;

    /** 原始题目文本 */
    private String question;

    /**
     * 会话类型：explanation | discussion，对应前端 type 字段。
     */
    @JsonProperty("type")
    private String sessionType;

    /** 会话状态：active | completed | expired */
    private String status;

    /**
     * 会话创建时间，对应前端 created_at 字段，ISO 格式。
     */
    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 最后访问时间，对应前端 last_accessed 字段，ISO 格式。
     */
    @JsonProperty("last_accessed")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastAccessedAt;
}
