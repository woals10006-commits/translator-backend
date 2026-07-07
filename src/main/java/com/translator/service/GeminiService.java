package com.translator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
    private static final int MAX_TOKENS = 8192;

    // Pins the target language. Without it Haiku sometimes emits Japanese for
    // kanji-heavy or ambiguous segments (names, short phrases), since Chinese
    // and Japanese share Han characters. Applied to every call.
    private static final String SYSTEM_PROMPT =
        "당신은 전문 번역가입니다. 입력 텍스트를 자연스러운 한국어로 번역합니다. " +
        "출력은 반드시 한국어(한글)로만 작성하세요. 일본어·중국어·영어 등 다른 언어로 출력하지 마세요. " +
        "원문에 일본어나 다른 언어가 섞여 있어도 모두 한국어로 번역하세요. " +
        "인명·지명 등 고유명사도 한국어로 표기하세요.";

    @Value("${claude.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiService() {
        // Without explicit timeouts a stalled request blocks the worker forever,
        // freezing the whole job. Fail fast and let the retry loop recover.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
    }

    public List<String> translateBatch(List<String> texts, TranslationJob job) throws Exception {
        StringBuilder prompt = new StringBuilder(
            "다음 중국어 텍스트 " + texts.size() + "개를 각각 한국어로 번역하세요.\n" +
            "번역 결과는 반드시 한국어로만 작성하고, 일본어·중국어를 그대로 두지 마세요.\n" +
            "translations 배열에 입력 순서 그대로 " + texts.size() + "개의 번역을 넣으세요. " +
            "문단을 합치거나 나누지 마세요.\n\n"
        );
        for (int i = 0; i < texts.size(); i++) {
            prompt.append("텍스트").append(i + 1).append(": ").append(texts.get(i)).append("\n");
        }

        // Structured outputs: the API constrains the reply to this schema and
        // guarantees valid JSON, eliminating the parse-failure fallbacks that
        // dominated earlier runs. The bare-array root isn't allowed, so the
        // strings live under a "translations" property.
        String responseText = callClaude(prompt.toString(), job, TRANSLATIONS_SCHEMA);

        try {
            JsonNode array = objectMapper.readTree(responseText).path("translations");
            List<String> results = new ArrayList<>();
            for (JsonNode node : array) {
                results.add(node.asText());
            }
            if (results.size() == texts.size()) return results;
            // Structured outputs guarantees valid JSON but not the exact count,
            // so a merged/split response can still mismatch — keep the safety net.
            log.warn("[FALLBACK] 배치 결과 개수 불일치: 기대={} 실제={} → 단락 {}개 개별 재번역",
                    texts.size(), results.size(), texts.size());
        } catch (Exception e) {
            log.warn("[FALLBACK] 배치 JSON 파싱 실패 ({}) → 단락 {}개 개별 재번역",
                    e.getClass().getSimpleName(), texts.size());
        }

        // fallback: translate individually (plain text, no schema)
        List<String> results = new ArrayList<>();
        for (String text : texts) {
            try {
                results.add(callClaude("다음 중국어를 한국어로 번역해주세요. 반드시 한국어로만, 번역문만 출력하세요:\n\n" + text, job, null));
            } catch (Exception e) {
                results.add(text);
            }
        }
        return results;
    }

    // Structured-outputs config: forces a valid JSON object {"translations": [...]}.
    // A bare-array root isn't allowed by json_schema, so strings live under a key.
    private static final Map<String, Object> TRANSLATIONS_SCHEMA = Map.of(
        "format", Map.of(
            "type", "json_schema",
            "schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "translations", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string")
                    )
                ),
                "required", List.of("translations"),
                "additionalProperties", false
            )
        )
    );

    private String callClaude(String prompt, TranslationJob job, Map<String, Object> outputConfig) throws Exception {
        String url = "https://api.anthropic.com/v1/messages";

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "claude-haiku-4-5-20251001");
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", SYSTEM_PROMPT);
        body.put("messages", List.of(message));
        if (outputConfig != null) {
            body.put("output_config", outputConfig);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                Thread.sleep(300);
                // Read the reply as raw bytes and let Jackson decode the UTF-8
                // JSON itself. Receiving it as String.class routes through Spring's
                // StringHttpMessageConverter, whose default charset is ISO-8859-1;
                // the API sends UTF-8 with no charset in Content-Type, so the
                // Korean/Chinese would be decoded wrong and come out as mojibake.
                ResponseEntity<byte[]> response = restTemplate.postForEntity(url, request, byte[].class);
                JsonNode root = objectMapper.readTree(response.getBody());
                // Accumulate token usage for exact per-job cost measurement.
                if (job != null) {
                    JsonNode usage = root.path("usage");
                    job.addTokens(usage.path("input_tokens").asLong(0),
                                  usage.path("output_tokens").asLong(0));
                }
                // DIAGNOSTIC: "max_tokens" means the reply was cut off at the
                // MAX_TOKENS limit. A truncated JSON array then fails to parse in
                // translateBatch and triggers the slow per-paragraph fallback.
                String stopReason = root.path("stop_reason").asText("");
                if ("max_tokens".equals(stopReason)) {
                    log.warn("[TRUNCATED] 응답이 max_tokens({})에서 잘림 — 폴백 위험", MAX_TOKENS);
                }
                return root.path("content").get(0).path("text").asText().trim();
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                // DIAGNOSTIC: 429 = rate limit hit. This is the "API 총량" suspect.
                // Honor the server's retry-after (seconds) instead of a blind 30s
                // stall, which wastes time whenever the real wait is shorter.
                long waitMs = 30_000;
                String retryAfter = e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst("retry-after") : null;
                if (retryAfter != null) {
                    try {
                        waitMs = Math.min(60_000, Long.parseLong(retryAfter.trim()) * 1000);
                    } catch (NumberFormatException ignored) {}
                }
                log.warn("[429] 레이트 리밋 (시도 {}/{}), retry-after={} → {}ms 대기",
                        i + 1, maxRetries, retryAfter, waitMs);
                if (i == maxRetries - 1) throw e;
                Thread.sleep(waitMs);
            }
        }
        throw new RuntimeException("번역 실패");
    }
}
