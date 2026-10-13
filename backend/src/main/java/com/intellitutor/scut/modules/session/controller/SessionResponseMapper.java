package com.intellitutor.scut.modules.session.controller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps internal Python session responses to the public Java session contract.
 *
 * <p>The browser must only ever receive the Java {@code t_session.id}. Python's
 * in-memory session ID is an implementation detail and is only used while
 * proxying requests from Java to Python.</p>
 */
final class SessionResponseMapper {

    private SessionResponseMapper() {
    }

    static Map<String, Object> exposeJavaSessionId(
            Map<String, Object> response,
            String javaSessionId) {
        if (response == null || javaSessionId == null || javaSessionId.isBlank()) {
            return response;
        }

        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return response;
        }

        Map<String, Object> publicData = new LinkedHashMap<>();
        dataMap.forEach((key, value) -> publicData.put(String.valueOf(key), value));
        publicData.put("session_id", javaSessionId);

        Map<String, Object> publicResponse = new LinkedHashMap<>(response);
        publicResponse.put("data", publicData);
        return publicResponse;
    }
}
