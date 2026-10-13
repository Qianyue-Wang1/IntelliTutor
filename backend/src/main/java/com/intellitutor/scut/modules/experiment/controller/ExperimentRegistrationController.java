package com.intellitutor.scut.modules.experiment.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.experiment.service.ExperimentRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth/experiment-registration")
@RequiredArgsConstructor
public class ExperimentRegistrationController {
    private final ExperimentRegistrationService registration;

    @GetMapping("/registration-options")
    public Result<ExperimentRegistrationService.RegistrationOptions> options() {
        return Result.ok(registration.currentRegistrationOptions());
    }

    @PostMapping("/registration-sms")
    public Result<Void> sendRegistrationSms(@RequestBody @Valid ExperimentDtos.PublicExperimentSmsReq request) {
        registration.sendCurrentRegistrationSms(request.phoneNumber());
        return Result.ok();
    }

    @PostMapping("/register")
    public Result<ExperimentRegistrationService.RegistrationResult> register(
            @RequestBody @Valid ExperimentDtos.PublicExperimentRegisterReq request) {
        return Result.ok(registration.registerCurrent(new ExperimentRegistrationService.CurrentExperimentRegisterReq(
                request.name(), request.phoneNumber(), request.password(), request.method(), request.proof())));
    }
}
