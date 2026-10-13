package com.intellitutor.scut.modules.user.dto.req;

import com.intellitutor.scut.modules.session.dto.req.DiscussionInitReq;
import com.intellitutor.scut.modules.session.dto.req.ExplanationInitReq;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class UserInputSecurityValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsHtmlInPersistedProfileAndSessionFields() {
        ChangeUserInfoReq profile = new ChangeUserInfoReq();
        profile.setNewNickname("<img src=x onerror=alert(1)>");

        ExplanationInitReq explanation = new ExplanationInitReq();
        explanation.setProcessedQuestion("<script>alert(1)</script>");

        DiscussionInitReq discussion = new DiscussionInitReq();
        discussion.setProcessedQuestion("<svg onload=alert(1)></svg>");

        assertFalse(validator.validate(profile).isEmpty());
        assertFalse(validator.validate(explanation).isEmpty());
        assertFalse(validator.validate(discussion).isEmpty());
    }

    @Test
    void rejectsExecutableSvgAvatar() {
        ChangeAvatarReq avatar = new ChangeAvatarReq();
        avatar.setAvatar("data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+");

        assertFalse(validator.validate(avatar).isEmpty());
    }
}
