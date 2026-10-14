package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns stored tutor responses into the content actually presented to participants. */
final class ParticipantConversationExport {
    private static final int CELL_CHARS = 30000;
    private final ObjectMapper mapper;

    ParticipantConversationExport(ObjectMapper mapper) { this.mapper = mapper; }

    List<Map<String, Object>> timeline(List<Map<String, Object>> messages) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> seenHistory = new HashMap<>();
        Map<String, Integer> sequences = new HashMap<>();
        for (Map<String, Object> message : messages) {
            String mode = string(message.get("mode"));
            String role = string(message.get("role"));
            String type = string(message.get("message_type"));
            String session = string(message.get("session_id"));
            String turn = string(message.get("message_key")).replace(":input", "");
            if ("student".equals(role)) {
                if ("explanation".equals(mode)) add(rows, sequences, message, turn, "student", "学生作答", "", string(message.get("content")), "", message.get("correctness"), message.get("server_timestamp"));
                continue;
            }
            if (!"tutor".equals(role)) continue;
            JsonNode data = parse(string(message.get("content"))).path("data");
            if (data.isMissingNode()) continue;
            if ("discussion".equals(mode)) {
                JsonNode history = data.path("messages_history");
                int start = seenHistory.getOrDefault(session, 0);
                if (history.isArray()) {
                    if (history.size() < start) start = 0;
                    for (int index = start; index < history.size(); index++) {
                        JsonNode entry = history.get(index);
                        String content = entry.path("content").asText("");
                        if ("🎉 恭喜！问题研讨已完成。".equals(content.trim())) continue;
                        String entryRole = "user".equals(entry.path("type").asText()) ? "student" : "tutor";
                        String step = data.path("step").asText(data.path("initial_step").asText(""));
                        boolean question = !step.isBlank() && step.equals(content);
                        String options = question ? json(data.path("current_options")) : "";
                        String contentType = "student".equals(entryRole) ? "学生输入" : question ? "讲解与题目" : content.startsWith("回答") ? "判题反馈" : "模型输出";
                        add(rows, sequences, message, turn, entryRole, contentType, "", content, options,
                                "判题反馈".equals(contentType) ? data.path("correctness").asText("") : null,
                                entry.path("time").asText(string(message.get("server_timestamp"))));
                    }
                    seenHistory.put(session, history.size());
                }
                String summary = data.path("summary").asText("").trim();
                if (!summary.isEmpty()) {
                    if (!summary.contains("## 问题总结")) summary = "## 问题总结\n\n" + summary;
                    add(rows, sequences, message, turn, "tutor", "研讨总结", "", summary, "", null, message.get("server_timestamp"));
                }
            } else if ("direct".equals(mode)) {
                JsonNode history = data.path("messages");
                int start = seenHistory.getOrDefault(session, 0);
                if (history.isArray()) {
                    if (history.size() < start) start = 0;
                    for (int index = start; index < history.size(); index++) {
                        JsonNode entry = history.get(index);
                        String kind = entry.path("kind").asText("");
                        if ("assigned_question".equals(kind)) continue;
                        String entryRole = "user".equals(entry.path("role").asText()) ? "student" : "tutor";
                        String contentType = "initial_explanation".equals(kind) ? "完整讲解" : "student".equals(entryRole) ? "学生追问" : "模型回复";
                        add(rows, sequences, message, turn, entryRole, contentType, "", entry.path("content").asText(""), "", null,
                                entry.path("time").asText(string(message.get("server_timestamp"))));
                    }
                    seenHistory.put(session, history.size());
                }
            } else if ("explanation".equals(mode)) {
                if ("EXPLANATION_GETSTEP".equals(type)) {
                    String number = data.path("step_number").asText("");
                    String content = data.path("analysis").asText("");
                    if (content.isBlank()) content = data.path("operation_prefix").asText("");
                    add(rows, sequences, message, turn, "tutor", "逐步讲解与题目", "explanation:" + number,
                            content, json(data.path("options")), null, message.get("server_timestamp"));
                } else if ("EXPLANATION_COMMIT_ANSWER".equals(type)) {
                    String number = data.path("current_step").asText("");
                    boolean correct = data.path("is_correct").asBoolean(false);
                    String feedback = correct ? "回答正确" : "需要再巩固一下";
                    String correctOption = data.path("correct_option").asText("");
                    if (!correctOption.isBlank()) feedback += "\n正确答案：" + correctOption;
                    add(rows, sequences, message, turn, "tutor", "判题反馈", "explanation:" + number,
                            feedback, "", correct, message.get("server_timestamp"));
                    String analysis = data.path("answer_explanation").asText("");
                    if (analysis.isBlank()) analysis = data.path("error_analysis").asText("");
                    if (!analysis.isBlank()) add(rows, sequences, message, turn, "tutor", "判题解析", "explanation:" + number,
                            analysis, "", correct, message.get("server_timestamp"));
                }
            }
        }
        return rows;
    }

    List<Map<String, Object>> learningMaterials(List<Map<String, Object>> messages) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            if (!"tutor".equals(message.get("role")) || !string(message.get("message_type")).endsWith("_INIT")) continue;
            String mode = string(message.get("mode"));
            if (!"discussion".equals(mode) && !"explanation".equals(mode)) continue;
            JsonNode data = parse(string(message.get("content"))).path("data");
            JsonNode knowledge = data.path("core_knowledge");
            boolean hasCore = knowledge.isArray() && !knowledge.isEmpty();
            if (!hasCore) knowledge = data.path("knowledge_points");
            if (knowledge.isArray()) for (int index = 0; index < knowledge.size(); index++) {
                JsonNode item = knowledge.get(index);
                material(rows, message, "知识点解析", index + 1,
                        item.path(hasCore ? "name" : "title").asText(""),
                        item.path(hasCore ? "content" : "description").asText(""));
            }
            JsonNode path = data.path("learning_path");
            if (path.isArray() && !path.isEmpty()) {
                for (JsonNode item : path) {
                    String title = "步骤 " + item.path("step_number").asText("") + " · " + item.path("title").asText("");
                    String content = "本步知识点：" + join(item.path("knowledge_points")) + "\n" + item.path("connection").asText("");
                    material(rows, message, "学习指引", item.path("step_number").asInt(), title, content);
                }
            } else if (hasCore) {
                for (int index = 0; index < knowledge.size(); index++) {
                    JsonNode item = knowledge.get(index);
                    String next = index + 1 < knowledge.size() ? knowledge.get(index + 1).path("name").asText("") : "";
                    String description = item.path("content").asText("");
                    String connection = next.isBlank() ? "结合前面所学，本步讲清" + description + "，完成整体理解。" :
                            "本步讲清" + description + "，为下一步“" + next + "”建立基础。";
                    material(rows, message, "学习指引", index + 1, "步骤 " + (index + 1) + " · " + item.path("name").asText(""),
                            "本步知识点：" + item.path("name").asText("") + "\n" + connection);
                }
            } else {
                JsonNode hint = data.path("problem_solving_hint");
                String[] keys = {"basic_idea", "skill_enlightenment", "core_mathematical_thought", "related_method"};
                String[] titles = {"学习起点", "推荐路径", "知识关联", "关键方法"};
                for (int index = 0; index < keys.length; index++) if (!hint.path(keys[index]).asText("").isBlank())
                    material(rows, message, "学习指引", index + 1, titles[index], hint.path(keys[index]).asText());
            }
        }
        return rows;
    }

    private void add(List<Map<String, Object>> rows, Map<String, Integer> sequences, Map<String, Object> message,
                     String turn, String role, String type, String step, String content, String options,
                     Object correctness, Object timestamp) {
        if (content.isBlank() && options.isBlank()) return;
        String session = string(message.get("session_id"));
        int sequence = sequences.merge(session, 1, Integer::sum);
        int parts = Math.max(1, (content.length() + CELL_CHARS - 1) / CELL_CHARS);
        for (int part = 0; part < parts; part++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round_no", message.get("round_no"));
            row.put("mode", message.get("mode"));
            row.put("session_id", session);
            row.put("sequence_no", sequence);
            row.put("turn_id", turn);
            row.put("role", role);
            row.put("content_type", type);
            row.put("step_key", step);
            row.put("content", content.substring(part * CELL_CHARS, Math.min(content.length(), (part + 1) * CELL_CHARS)));
            row.put("options_json", options);
            row.put("correctness", correctness);
            row.put("timestamp", timestamp);
            row.put("part_no", part + 1);
            rows.add(row);
        }
    }

    private void material(List<Map<String, Object>> rows, Map<String, Object> message, String section,
                          int number, String title, String content) {
        int parts = Math.max(1, (content.length() + CELL_CHARS - 1) / CELL_CHARS);
        for (int part = 0; part < parts; part++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round_no", message.get("round_no"));
            row.put("mode", message.get("mode"));
            row.put("session_id", message.get("session_id"));
            row.put("section", section);
            row.put("item_no", number);
            row.put("title", title);
            row.put("content", content.substring(part * CELL_CHARS, Math.min(content.length(), (part + 1) * CELL_CHARS)));
            row.put("part_no", part + 1);
            row.put("timestamp", message.get("server_timestamp"));
            rows.add(row);
        }
    }

    private JsonNode parse(String content) {
        try { return mapper.readTree(content); }
        catch (Exception ignored) { return mapper.createObjectNode(); }
    }

    private String json(JsonNode node) {
        if (!node.isArray()) return "";
        try { return mapper.writeValueAsString(node); }
        catch (Exception ignored) { return ""; }
    }

    private static String join(JsonNode items) {
        if (!items.isArray()) return "";
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.asText("")));
        return String.join("、", values);
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
}
