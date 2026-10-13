package com.intellitutor.scut.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器。
 * <p>
 * 在执行 INSERT / UPDATE 时，自动为标记了
 * {@link com.baomidou.mybatisplus.annotation.FieldFill} 注解的字段注入值，
 * 避免业务代码手动设置 {@code createdAt} / {@code updatedAt}。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Component
public class MyBatisPlusMetaHandler implements MetaObjectHandler {

    /**
     * 新增时自动填充 {@code createdAt} 和 {@code updatedAt}。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("[MetaFill] insertFill triggered for {}", metaObject.getOriginalObject().getClass().getSimpleName());
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    /**
     * 更新时自动填充 {@code updatedAt}。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("[MetaFill] updateFill triggered for {}", metaObject.getOriginalObject().getClass().getSimpleName());
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
