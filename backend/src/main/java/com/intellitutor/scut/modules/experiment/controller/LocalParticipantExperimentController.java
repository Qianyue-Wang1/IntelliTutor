package com.intellitutor.scut.modules.experiment.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.experiment.service.LocalParticipantExperimentService;
import com.intellitutor.scut.modules.user.dto.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth/local-experiment")
@RequiredArgsConstructor
public class LocalParticipantExperimentController {
    private final LocalParticipantExperimentService participants;

    @PostMapping("/start")
    public Result<LoginVO> start(@RequestBody @Valid ExperimentDtos.LocalParticipantStartReq request) {
        return Result.ok(participants.start(new LocalParticipantExperimentService.StartReq(request.name(), request.phoneNumber())));
    }
}
