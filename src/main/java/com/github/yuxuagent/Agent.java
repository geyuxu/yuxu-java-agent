package com.github.yuxuagent;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Day 1: 最小 Agent — 一个 while 循环 + 工具调用
 * <p>
 * 使用 OpenAI Chat Completions API (function calling)
 */
public class Agent {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";
    private static final String SYSTEM_PROMPT = "You are a coding agent. Use the bash tool to solve tasks.";

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private static final Gson gson = new Gson();

    // ========== 工具定义（OpenAI function calling 格式）==========

    static final List<Map<String, Object>> TOOLS = List.of(
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "bash",
                            "description", "Execute a bash shell command and return the output.",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "command", Map.of(
                                                    "type", "string",
                                                    "description", "The bash command to execute"
                                            )
                                    ),
                                    "required", List.of("command")
                            )
                    )
            )
    );

    // ========== 模型调用抽象 ==========

    /**
     * 模型调用接口——方便测试时替换为 mock 实现。
     * <p>
     * 返回 OpenAI choices[0] 格式：
     * { "finish_reason": "stop"|"tool_calls", "message": { "role": "assistant", ... } }
     */
    interface ModelClient {
        Map<String, Object> call(List<Map<String, Object>> messages) throws IOException;
    }

    // ========== 工具执行 ==========

    static String executeBash(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (120s)";
            }
            return output.isBlank() ? "(no output)" : output.trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ========== Agent 核心循环 ==========

    static void agentLoop(List<Map<String, Object>> messages, ModelClient modelClient)
            throws IOException {

        while (true) {

            // 1. 调用模型：把完整的消息历史发给 LLM
            Map<String, Object> choice = modelClient.call(messages);

            String finishReason = (String) choice.get("finish_reason");
            @SuppressWarnings("unchecked")
            Map<String, Object> assistantMsg =
                    (Map<String, Object>) choice.get("message");

            // 2. 把模型的回复追加到消息历史
            messages.add(assistantMsg);

            // 3. 检查退出条件：模型不再调用工具 → 任务完成
            if (!"tool_calls".equals(finishReason)) {
                String content = (String) assistantMsg.get("content");
                if (content != null) {
                    System.out.println("\nAssistant: " + content);
                }
                return;
            }

            // 4. 执行每一个工具调用，收集结果
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls =
                    (List<Map<String, Object>>) assistantMsg.get("tool_calls");

            for (Map<String, Object> toolCall : toolCalls) {
                String callId = (String) toolCall.get("id");
                @SuppressWarnings("unchecked")
                Map<String, Object> function =
                        (Map<String, Object>) toolCall.get("function");
                String arguments = (String) function.get("arguments");

                // 解析函数参数
                @SuppressWarnings("unchecked")
                Map<String, String> args = gson.fromJson(arguments, Map.class);
                String command = args.get("command");

                System.out.println("\n> bash: " + command);
                String output = executeBash(command);
                System.out.println(output);

                // 5. 把工具结果追加到消息历史（OpenAI 格式：role=tool）
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", output
                ));
            }

            // 回到第 1 步
        }
    }

    // ========== API 调用 ==========

    static ModelClient createOpenAIClient(String apiKey) {
        return messages -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL);
            body.put("messages", messages);
            body.put("tools", TOOLS);

            Request request = new Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(
                            gson.toJson(body),
                            MediaType.get("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("API error " + response.code()
                            + ": " + response.body().string());
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> responseBody = gson.fromJson(
                        response.body().string(),
                        new TypeToken<Map<String, Object>>() {}.getType());

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices =
                        (List<Map<String, Object>>) responseBody.get("choices");
                return choices.get(0);
            }
        };
    }

    // ========== 启动入口 ==========

    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set OPENAI_API_KEY environment variable.");
            return;
        }

        ModelClient client = createOpenAIClient(apiKey);

        if (args.length > 0) {
            String userInput = String.join(" ", args);
            System.out.println("User: " + userInput);
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", userInput));
            agentLoop(messages, client);
        } else {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("\nUser: ");
                String userInput = scanner.nextLine().trim();
                if (userInput.equalsIgnoreCase("exit") || userInput.equalsIgnoreCase("quit")) {
                    break;
                }
                if (userInput.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
                messages.add(Map.of("role", "user", "content", userInput));
                agentLoop(messages, client);
            }
            scanner.close();
        }
    }
}
