package com.intellitutor.scut.modules.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellitutor.scut.modules.task.entity.TaskRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 推理任务 Mapper，继承 MyBatis-Plus BaseMapper 获得基础 CRUD。
 *
 * <p>
 * 补充自定义 update 方法，按状态精细更新，避免 MQ 并发写冲突。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskRecord> {

    /**
     * 将任务状态由 queued 更新为 processing（MQ 消费开始时调用）。
     * 使用 WHERE status='queued' 防止重复消费覆盖已完成状态。
     */
    @Update("UPDATE t_task SET status = 'processing' WHERE id = #{id} AND status = 'queued'")
    int updateStatusToProcessing(@Param("id") String id);

    /**
     * 将任务标记为成功，写入 Python 响应体与完成时间。
     */
    @Update("UPDATE t_task SET status = 'success', result = #{result}, completed_at = #{completedAt} WHERE id = #{id}")
    int updateStatusToSuccess(@Param("id") String id,
            @Param("result") String result,
            @Param("completedAt") LocalDateTime completedAt);

    /**
     * 将任务标记为失败，写入错误信息与完成时间。
     */
    @Update("UPDATE t_task SET status = 'failed', error_message = #{errorMessage}, completed_at = #{completedAt} WHERE id = #{id}")
    int updateStatusToFailed(@Param("id") String id,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") LocalDateTime completedAt);
}
