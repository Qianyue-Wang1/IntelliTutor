package com.intellitutor.scut.modules.config.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.config.FeatureProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilitiesControllerTest {

    @Test
    void reportsRuntimeImageCapability() {
        FeatureProperties features = new FeatureProperties();
        CapabilitiesController controller = new CapabilitiesController(features);

        Result<CapabilitiesController.CapabilitiesVO> disabled = controller.capabilities();
        assertFalse(disabled.getData().imageInputEnabled());

        features.setImageInputEnabled("true");
        Result<CapabilitiesController.CapabilitiesVO> enabled = controller.capabilities();
        assertTrue(enabled.getData().imageInputEnabled());
    }

    @Test
    void serializesCapabilityUsingPublicSnakeCaseContract() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new CapabilitiesController.CapabilitiesVO(false));

        assertEquals("{\"image_input_enabled\":false,\"experiment_mode_enabled\":false}", json);
    }

    @Test
    void invalidConfigurationFailsClosed() {
        FeatureProperties features = new FeatureProperties();
        features.setImageInputEnabled("not-a-boolean");

        assertFalse(features.isImageInputEnabled());
    }
}
