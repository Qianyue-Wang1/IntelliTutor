package com.intellitutor.scut.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellitutor.scut.modules.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper 接口，继承 MyBatis-Plus {@link BaseMapper}。
 *
 * <p>
 * 常规 CRUD 由 BaseMapper 提供，自定义 SQL 可在对应 XML 或注解中扩展。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
