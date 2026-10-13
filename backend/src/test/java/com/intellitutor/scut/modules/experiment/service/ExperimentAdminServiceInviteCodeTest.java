package com.intellitutor.scut.modules.experiment.service;

import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.config.ExperimentProperties;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperimentAdminServiceInviteCodeTest {

    @Test
    void createsAOneTimeInviteForAnActiveExperimentWithoutPersistingThePlaintextCode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExperimentEventService events = mock(ExperimentEventService.class);
        when(jdbc.queryForObject("SELECT roles FROM t_user WHERE id=? AND deleted=0", String.class, "admin-1"))
                .thenReturn("ADMIN");
        when(jdbc.queryForMap("SELECT status,registration_policy FROM t_experiment WHERE id=?", "experiment-1"))
                .thenReturn(Map.of("status", "ACTIVE", "registration_policy", "SMS_OR_INVITE"));
        when(jdbc.queryForObject("SELECT current_stage_code FROM t_experiment WHERE id=?", String.class, "experiment-1"))
                .thenReturn("SETUP");

        ExperimentAdminService service = new ExperimentAdminService(jdbc, new ObjectMapper(), events,
                new ExperimentProperties(), null, null);
        ExperimentDtos.InviteCodeVO invite = service.createInviteCode("admin-1", "experiment-1");

        assertTrue(invite.code().matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}"));
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(eq("INSERT INTO t_experiment_invite_code(id,experiment_id,code_hash,max_uses) VALUES(?,?,?,1)"), values.capture());
        Object[] inserted = values.getValue();
        assertEquals("experiment-1", inserted[1]);
        assertTrue(BCrypt.checkpw(invite.code(), String.valueOf(inserted[2])));
        verify(events).recordAdmin(eq("admin-1"), eq("experiment-1"), eq("SETUP"), eq("INVITE_CODE_CREATED"), any());
    }
}
