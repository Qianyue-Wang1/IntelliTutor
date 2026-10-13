package com.intellitutor.scut.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellitutor.experiment")
public class ExperimentProperties {
    private int inactivityMinutes = 10;
    private int eventBatchSize = 100;
    private int eventPayloadBytes = 16_384;
    private String adminAccount = "";
    private String adminPassword = "";
}
