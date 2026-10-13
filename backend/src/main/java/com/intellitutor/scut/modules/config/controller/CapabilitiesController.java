package com.intellitutor.scut.modules.config.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.config.FeatureProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-sensitive product capabilities used to shape the client UI. */
@RestController
@RequestMapping("/v1/config")
@RequiredArgsConstructor
public class CapabilitiesController {

    private final FeatureProperties features;

    @GetMapping("/capabilities")
    public Result<CapabilitiesVO> capabilities() {
        return Result.ok(new CapabilitiesVO(features.isImageInputEnabled(), features.isExperimentModeEnabled()));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CapabilitiesVO(boolean imageInputEnabled, boolean experimentModeEnabled) {
        public CapabilitiesVO(boolean imageInputEnabled) {
            this(imageInputEnabled, false);
        }
    }
}
