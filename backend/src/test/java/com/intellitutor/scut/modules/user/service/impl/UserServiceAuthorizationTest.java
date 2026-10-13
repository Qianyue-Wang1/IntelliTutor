package com.intellitutor.scut.modules.user.service.impl;

import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.utils.JwtUtil;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.user.mapper.UserMapper;
import com.intellitutor.scut.modules.user.service.EmailVerifyCodeService;
import com.intellitutor.scut.modules.user.service.SmsVerifyCodeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class UserServiceAuthorizationTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void rejectsPathUserIdDifferentFromAuthenticatedUser() {
        UserMapper userMapper = mock(UserMapper.class);
        UserServiceImpl service = new UserServiceImpl(
                userMapper,
                mock(JwtUtil.class),
                mock(StringRedisTemplate.class),
                mock(SmsVerifyCodeService.class),
                mock(EmailVerifyCodeService.class));
        UserContext.setUserId("attacker");

        assertThrows(BusinessException.class, () -> service.getUserInfo("victim"));
        verifyNoInteractions(userMapper);
    }
}
