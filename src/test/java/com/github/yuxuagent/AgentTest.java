package com.github.yuxuagent;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest {

    private static final Gson gson = new Gson();

    // ========== executeBash 测试 ==========

    @Test
    void executeBash_simpleCommand() {
        String result = Agent.executeBash("echo hello");
        assertEquals("hello", result);
    }

    @Test
    void executeBash_multiLineOutput() {
        String result = Agent.executeBash("echo 'line1'; echo 'line2'");
        assertEquals("line1\nline2", result);
    }

    @Test
    void executeBash_stderrMerged() {
        String result = Agent.executeBash("echo error >&2");
        assertEquals("error", result);
    }

    @Test
    void executeBash_emptyOutput() {
        String result = Agent.executeBash("true");
        assertEquals("(no output)", result);
    }

    @Test
    void executeBash_invalidCommand() {
        String result = Agent.executeBash("nonexistent_command_xyz_123");
        assertTrue(result.contains("not found") || result.contains("No such file"),
                "Expected error message, got: " + result);
    }

    @Test
    void executeBash_exitCode() {
        String result = Agent.executeBash("echo 'fail' && exit 1");
        assertEquals("fail", result);
    }

    // ========== Agent 循环测试（mock ModelClient，OpenAI 格式）==========

    /**
     * 构造一个 OpenAI 格式的 choice：模型直接回复文本，不调用工具
     */
    private Map<String, Object> textChoice(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text);
        return Map.of("finish_reason", "stop", "message", message);
    }

    /**
     * 构造一个 OpenAI 格式的 choice：模型请求调用一个工具
     */
    private Map<String, Object> toolCallChoice(String callId, String command) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);  // tool_calls 时 content 为 null
        message.put("tool_calls", List.of(Map.of(
                "id", callId,
                "type", "function",
                "function", Map.of(
                        "name", "bash",
                        "arguments", gson.toJson(Map.of("command", command))
                )
        )));

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("finish_reason", "tool_calls");
        choice.put("message", message);
        return choice;
    }

    /**
     * 最简单的场景：模型直接回复文本，不调用任何工具。
     */
    @Test
    void agentLoop_noToolCall_returnsImmediately() throws IOException {
        Agent.ModelClient mockClient = messages -> textChoice("Hello!");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "hi"));

        Agent.agentLoop(messages, mockClient);

        // messages: user + assistant
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("Hello!", messages.get(1).get("content"));
    }

    /**
     * 模型调用一次工具后给出最终回答。
     */
    @Test
    void agentLoop_oneToolCall_thenFinalAnswer() throws IOException {
        var callCount = new int[]{0};

        Agent.ModelClient mockClient = messages -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return toolCallChoice("call_001", "echo 'hello world'");
            } else {
                return textChoice("The output is: hello world");
            }
        };

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "run echo hello world"));

        Agent.agentLoop(messages, mockClient);

        assertEquals(2, callCount[0]);

        // messages: user -> assistant(tool_calls) -> tool(result) -> assistant(text)
        assertEquals(4, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("tool", messages.get(2).get("role"));
        assertEquals("assistant", messages.get(3).get("role"));

        // 验证 tool result
        assertEquals("call_001", messages.get(2).get("tool_call_id"));
        assertEquals("hello world", messages.get(2).get("content"));
    }

    /**
     * 模型连续调用两次工具后给出最终回答。
     */
    @Test
    void agentLoop_multipleToolCalls_messagesAccumulate() throws IOException {
        var callCount = new int[]{0};

        Agent.ModelClient mockClient = messages -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return toolCallChoice("call_001", "pwd");
            } else if (callCount[0] == 2) {
                return toolCallChoice("call_002", "echo done");
            } else {
                return textChoice("All done.");
            }
        };

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "do two things"));

        Agent.agentLoop(messages, mockClient);

        assertEquals(3, callCount[0]);
        // user -> assistant(tool) -> tool(result) -> assistant(tool) -> tool(result) -> assistant(text)
        assertEquals(6, messages.size());
    }

    /**
     * 验证模型每轮都能看到完整的消息历史。
     */
    @Test
    void agentLoop_modelReceivesFullHistory() throws IOException {
        List<List<Map<String, Object>>> captured = new ArrayList<>();
        var callCount = new int[]{0};

        Agent.ModelClient mockClient = messages -> {
            captured.add(new ArrayList<>(messages));
            callCount[0]++;
            if (callCount[0] == 1) {
                return toolCallChoice("t1", "echo hi");
            } else {
                return textChoice("Done");
            }
        };

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "test"));

        Agent.agentLoop(messages, mockClient);

        // 第一次调用：[user]
        assertEquals(1, captured.get(0).size());

        // 第二次调用：[user, assistant(tool_calls), tool(result)]
        assertEquals(3, captured.get(1).size());
        assertEquals("user", captured.get(1).get(0).get("role"));
        assertEquals("assistant", captured.get(1).get(1).get("role"));
        assertEquals("tool", captured.get(1).get(2).get("role"));
    }

    /**
     * 验证工具实际执行了 bash 命令并返回正确结果。
     */
    @Test
    void agentLoop_toolActuallyExecutes() throws IOException {
        var callCount = new int[]{0};

        Agent.ModelClient mockClient = messages -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return toolCallChoice("t1", "echo agent_test_marker_12345");
            } else {
                return textChoice("done");
            }
        };

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "test"));

        Agent.agentLoop(messages, mockClient);

        // tool result 应该包含实际的 bash 输出
        String toolOutput = (String) messages.get(2).get("content");
        assertEquals("agent_test_marker_12345", toolOutput);
    }
}
