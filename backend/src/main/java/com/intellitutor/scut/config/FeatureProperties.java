package com.intellitutor.scut.config;

import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime product capability flags controlled by deployment configuration. */
@Component
@ConfigurationProperties(prefix = "intellitutor.features")
public class FeatureProperties {

    /** Whether users may create new questions from images. */
    private String imageInputEnabled = "false";

    /** Whether the formal human-study orchestration layer is active. */
    private String experimentModeEnabled = "false";

    /** Invalid or missing values intentionally fail closed. */
    public boolean isImageInputEnabled() {
        return "true".equalsIgnoreCase(imageInputEnabled == null ? "" : imageInputEnabled.trim());
    }

    public void setImageInputEnabled(String imageInputEnabled) {
        this.imageInputEnabled = imageInputEnabled;
    }

    public boolean isExperimentModeEnabled() {
        return "true".equalsIgnoreCase(experimentModeEnabled == null ? "" : experimentModeEnabled.trim());
    }

    public void setExperimentModeEnabled(String experimentModeEnabled) {
        this.experimentModeEnabled = experimentModeEnabled;
    }

    public void requireImageInputEnabled() {
        if (!isImageInputEnabled()) {
            throw new BusinessException(ResultCode.FEATURE_DISABLED, "图片提问当前未启用");
        }
    }
}
