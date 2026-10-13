package com.intellitutor.scut.modules.user.dto.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户详细信息视图（个人中心使用）。
 *
 * <p>
 * 字段对应前端 {@code UserInfo} 类型，包含学习统计数据。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
@Builder
public class UserInfoVO {

    private String id;
    private String email;
    private String phoneNumber;
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 已解决题目数 */
    private Integer solvedProblems;

    /** 累计学习天数 */
    private Integer studyDays;

    /** 已掌握知识点数 */
    private Integer masteredKnowledgePoints;

    /** 本周学习题目数 */
    private Integer weeklyStudyQuestions;

    /** 本周掌握知识点数 */
    private Integer weeklyMasteredKnowledgePoints;

    /** 答题正确率 */
    private BigDecimal accuracy;

    /** 角色列表 */
    private List<String> roles;

    /** 注册时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;

    /** 最后登录时间 */
    private LocalDateTime lastLogin;
}
