package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscussionFeedbackReqTest {

    @Test
    void preservesSubmissionTypeWhenReplacingSessionId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DiscussionFeedbackReq request = mapper.readValue(
                "{\"session_id\":\"java-session\",\"feedback\":\"理解了\",\"submission_type\":\"option\"}",
                DiscussionFeedbackReq.class
        );

        DiscussionFeedbackReq forwarded = request.withSessionId("python-session");
        String json = mapper.writeValueAsString(forwarded);

        assertThat(json).contains("\"session_id\":\"python-session\"");
        assertThat(json).contains("\"submission_type\":\"option\"");
    }
}
