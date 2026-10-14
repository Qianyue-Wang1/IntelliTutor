package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticipantConversationExportTest {
    private final ParticipantConversationExport export = new ParticipantConversationExport(new ObjectMapper());

    @Test
    void exportsVisibleDiscussionTurnsAndChoicesWithoutRepeatingHistory() {
        var initial = row(1, "discussion", "DISCUSSION_INIT", "tutor", "init", """
                {"data":{"messages_history":[{"type":"system","content":"步骤1：计算距离","time":"2026-10-14T10:00:00"}],
                "initial_step":"步骤1：计算距离","current_options":["√25","√37"],
                "core_knowledge":[{"name":"欧氏距离","content":"两点间直线距离"}],
                "learning_path":[{"step_number":1,"title":"距离计算","knowledge_points":["欧氏距离"],"connection":"先理解距离"}]}}
                """);
        var feedback = row(1, "discussion", "DISCUSSION_FEEDBACK", "tutor", "answer", """
                {"data":{"messages_history":[{"type":"system","content":"步骤1：计算距离","time":"2026-10-14T10:00:00"},
                {"type":"user","content":"√25","time":"2026-10-14T10:00:10"},
                {"type":"system","content":"回答正确。","time":"2026-10-14T10:00:11"},
                {"type":"system","content":"步骤2：下一题","time":"2026-10-14T10:00:11"}],
                "step":"步骤2：下一题","current_options":["选项甲","选项乙"],"correctness":"true"}}
                """);

        var timeline = export.timeline(List.of(initial, feedback));
        var material = export.learningMaterials(List.of(initial, feedback));

        assertEquals(List.of("步骤1：计算距离", "√25", "回答正确。", "步骤2：下一题"),
                timeline.stream().map(row -> row.get("content")).toList());
        assertEquals("[\"√25\",\"√37\"]", timeline.get(0).get("options_json"));
        assertEquals("[\"选项甲\",\"选项乙\"]", timeline.get(3).get("options_json"));
        assertEquals("answer", timeline.get(1).get("turn_id"));
        assertTrue(material.stream().anyMatch(row -> "知识点解析".equals(row.get("section")) && "两点间直线距离".equals(row.get("content"))));
        assertTrue(material.stream().anyMatch(row -> "学习指引".equals(row.get("section")) && String.valueOf(row.get("content")).contains("先理解距离")));
    }

    @Test
    void exportsExplanationAndDirectOutputsAndHidesAssignedModelPrompt() {
        var step = row(3, "explanation", "EXPLANATION_GETSTEP", "tutor", "step", """
                {"data":{"step_number":2,"analysis":"讲解正文","options":["正确选项","干扰项"]}}
                """);
        var answer = row(3, "explanation", "EXPLANATION_COMMIT_ANSWER", "student", "commit:input", "A");
        var result = row(3, "explanation", "EXPLANATION_COMMIT_ANSWER", "tutor", "commit", """
                {"data":{"current_step":2,"is_correct":true,"correct_option":"A","answer_explanation":"判断解析"}}
                """);
        var direct = row(4, "direct", "DIRECT_FOLLOWUP", "tutor", "followup", """
                {"data":{"messages":[{"kind":"assigned_question","role":"user","content":"内部提示词"},
                {"kind":"initial_explanation","role":"assistant","content":"完整讲解"},
                {"kind":"followup","role":"user","content":"用户追问"},
                {"kind":"followup_reply","role":"assistant","content":"模型回复"}]}}
                """);

        var timeline = export.timeline(List.of(step, answer, result, direct));

        assertTrue(timeline.stream().anyMatch(row -> "讲解正文".equals(row.get("content")) && "[\"正确选项\",\"干扰项\"]".equals(row.get("options_json"))));
        assertTrue(timeline.stream().anyMatch(row -> "判断解析".equals(row.get("content"))));
        assertTrue(timeline.stream().anyMatch(row -> "完整讲解".equals(row.get("content"))));
        assertTrue(timeline.stream().anyMatch(row -> "用户追问".equals(row.get("content"))));
        assertTrue(timeline.stream().anyMatch(row -> "模型回复".equals(row.get("content"))));
        assertFalse(timeline.stream().anyMatch(row -> "内部提示词".equals(row.get("content"))));
    }

    private Map<String, Object> row(int round, String mode, String type, String role, String key, String content) {
        return Map.of("round_no", round, "mode", mode, "session_id", "session-" + round,
                "message_type", type, "role", role, "message_key", key, "content", content,
                "server_timestamp", OffsetDateTime.parse("2026-10-14T10:00:00+08:00"));
    }
}
