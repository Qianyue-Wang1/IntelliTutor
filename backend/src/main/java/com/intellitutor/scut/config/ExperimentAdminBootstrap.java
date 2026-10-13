package com.intellitutor.scut.config;

import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExperimentAdminBootstrap implements ApplicationRunner {
    private final ExperimentProperties properties;
    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        String account = properties.getAdminAccount();
        String password = properties.getAdminPassword();
        if (account == null || account.isBlank() || password == null || password.isBlank()) return;
        if (password.length() < 12) throw new IllegalStateException("EXPERIMENT_ADMIN_PASSWORD must contain at least 12 characters");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_user WHERE login_account=? AND deleted=0", Long.class, account);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO t_user(id,login_account,password,nickname,roles,must_change_password,deleted) VALUES(?,?,?,?, 'ADMIN',FALSE,0)",
                UUID.randomUUID().toString(), account, BCrypt.hashpw(password, BCrypt.gensalt()), "实验管理员");
        log.info("Experiment administrator account created: {}", account);
    }
}
