package com.intellitutor.scut.modules.experiment.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.experiment.service.ExperimentRegistrationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperimentRegistrationControllerTest {

    @Test
    void publicRegistrationUsesTheCurrentExperimentWithoutAStudentSuppliedCode() {
        ExperimentRegistrationService service = mock(ExperimentRegistrationService.class);
        ExperimentRegistrationController controller = new ExperimentRegistrationController(service);
        ExperimentDtos.PublicExperimentRegisterReq request = new ExperimentDtos.PublicExperimentRegisterReq(
                "张三", "13800138000", "safe-password", "SMS_VERIFIED", "123456");
        ExperimentRegistrationService.RegistrationResult expected =
                new ExperimentRegistrationService.RegistrationResult("student-1", "13800138000");
        when(service.registerCurrent(new ExperimentRegistrationService.CurrentExperimentRegisterReq("张三", "13800138000",
                "safe-password", "SMS_VERIFIED", "123456"))).thenReturn(expected);

        Result<ExperimentRegistrationService.RegistrationResult> response = controller.register(request);

        assertEquals(expected, response.getData());
        verify(service).registerCurrent(eq(new ExperimentRegistrationService.CurrentExperimentRegisterReq("张三", "13800138000",
                "safe-password", "SMS_VERIFIED", "123456")));
    }

    @Test
    void publicRegistrationShowsOnlyMethodsEnabledForTheCurrentExperiment() {
        ExperimentRegistrationService service = mock(ExperimentRegistrationService.class);
        ExperimentRegistrationController controller = new ExperimentRegistrationController(service);
        ExperimentRegistrationService.RegistrationOptions expected =
                new ExperimentRegistrationService.RegistrationOptions(List.of("SMS_VERIFIED"));
        when(service.currentRegistrationOptions()).thenReturn(expected);

        Result<ExperimentRegistrationService.RegistrationOptions> response = controller.options();

        assertEquals(expected, response.getData());
        verify(service).currentRegistrationOptions();
    }

    @Test
    void publicRegistrationSmsUsesTheCurrentExperimentWithoutAStudentSuppliedCode() {
        ExperimentRegistrationService service = mock(ExperimentRegistrationService.class);
        ExperimentRegistrationController controller = new ExperimentRegistrationController(service);

        controller.sendRegistrationSms(new ExperimentDtos.PublicExperimentSmsReq("13800138000"));

        verify(service).sendCurrentRegistrationSms("13800138000");
    }
}
