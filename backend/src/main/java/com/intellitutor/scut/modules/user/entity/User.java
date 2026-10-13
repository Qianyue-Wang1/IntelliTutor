package com.intellitutor.scut.modules.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 用户实体，对应 PostgreSQL {@code t_user} 表。
 *
 * <p>
 * 核心字段说明：
 * <ul>
 * <li>{@code email} / {@code phoneNumber}：至少有一个非空（注册时二选一）</li>
 * <li>{@code password}：BCrypt 哈希存储</li>
 * <li>{@code roles}：逗号分隔的角色字符串，默认 {@code "STUDENT"}</li>
 * <li>{@code deleted}：逻辑删除标记，0=正常 1=已删除</li>
 * </ul>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
@TableName("t_user")
public class User {

    /** 主键，UUID 由 MyBatis-Plus 自动分配 */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 注册邮箱（可空，与 phoneNumber 至少有一个非空） */
    private String email;

    /** 注册手机号（可空，与 email 至少有一个非空） */
    private String phoneNumber;

    /** Formal experiment login name; does not need to be an e-mail or phone. */
    private String loginAccount;

    /** BCrypt 密码哈希 */
    private String password;

    /** 用户昵称 */
    private String nickname;

    /** 头像 URL（可空，存储对象存储地址或外链） */
    private String avatar;

    /** 已解题数 */
    private Integer solvedProblems;

    /** 累计学习天数 */
    private Integer studyDays;

    /** 已掌握知识点数量 */
    private Integer masteredKnowledgePoints;

    /** 本周刷题数 */
    private Integer weeklyStudyQuestions;

    /** 本周新掌握知识点 */
    private Integer weeklyMasteredKnowledgePoints;

    /** 正确率（0.00 ~ 1.00） */
    private BigDecimal accuracy;

    /** 角色列表，逗号分隔，默认 STUDENT */
    private String roles;

    /** Pre-created experiment accounts must replace their one-time password. */
    private Boolean mustChangePassword;

    private OffsetDateTime passwordChangedAt;

    /** 创建时间（由数据库 DEFAULT 或 MyBatis-Plus 自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 最后登录时间 */
    private LocalDateTime lastLogin;

    /** 逻辑删除标记：0=正常，1=已删除 */
    @TableLogic
    private Integer deleted;
}
