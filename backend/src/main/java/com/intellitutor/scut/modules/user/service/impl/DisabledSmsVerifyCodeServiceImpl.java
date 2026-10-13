package com.intellitutor.scut.modules.user.service.impl;

import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.user.service.SmsVerifyCodeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Keeps the application bootable when phone verification is intentionally disabled.
 */
@Service
@ConditionalOnProperty(
        prefix = "intellitutor.aliyun.dypns",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class DisabledSmsVerifyCodeServiceImpl implements SmsVerifyCodeService {

    @Override
    public void sendVerifyCode(String phoneNumber) {
        throw new BusinessException(ResultCode.SMS_SERVICE_UNAVAILABLE);
    }

    @Override
    public boolean checkVerifyCode(String phoneNumber, String code) {
        throw new BusinessException(ResultCode.SMS_SERVICE_UNAVAILABLE);
    }
}

