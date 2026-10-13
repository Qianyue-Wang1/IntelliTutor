package com.intellitutor.scut.modules.user.dto.vo;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 用户基础信息视图（登录/注册响应中嵌套使用）。
 *
 * <p>
 * 字段对应前端 {@code User} 类型。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
@Builder
public class UserVO {

    /** 用户唯一标识 */
    private String id;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phoneNumber;

    private String loginAccount;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    private Boolean mustChangePassword;

    private List<String> roles;
}
