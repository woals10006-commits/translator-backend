package com.translator.proofread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 교정교열용 Claude 호출. 번역기와 달리 한 번에 한 화만 보내고, 담당자가
 * 등록한 프롬프트를 그대로 system 으로 쓴다.
 */
@Service
public class ProofreadClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ProofreadClaudeClient.class);

    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-5";
    private static final int MAX_TOKENS = 16000;
    // 교정 목록이 max_tokens 에서 잘리면 뒷부분 수정 사항이 통째로 사라진다.
    // 사람이 다음 이라고 치는 대신 프로그램이 이어받아 최대 이 횟수만큼 더 받는다.
    private static final int MAX_CONTINUATIONS = 4;

    // 요금 계산용 (Claude Opus 5: 입력 $5 / 출력 $25 per 1M tokens)
    public static final double USD_PER_M_INPUT = 5.0;
    public static final double USD_PER_M_OUTPUT = 25.0;

    @Value("${claude.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    // 안전 폴백(server-side fallback)은 계정에 따라 아직 열려 있지 않을 수 있다.
    // 400 이 한 번 나면 이 플래그를 내리고 그 뒤로는 빼고 보낸다.
    private volatile boolean useFallbacks = true;

    public ProofreadClaudeClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // 한 화를 통째로 읽고 검토하는 요청이라 번역 한 덩어리보다 오래 걸린다.
        factory.setReadTimeout(300_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 프롬프트(system)와 사용자 메시지 한 건을 보내고, 응답이 출력 한도에서
     * 잘리면 이어받아 전체를 하나의 문자열로 돌려준다.
     */
    public String ask(String systemPrompt, String userMessage, ProofreadJob job) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", userMessage));

        StringBuilder full = new StringBuilder();
        for (int round = 0; round <= MAX_CONTINUATIONS; round++) {
            Reply reply = call(systemPrompt, messages, job);
            full.append(reply.text());

            if (!"max_tokens".equals(reply.stopReason())) {
                if ("refusal".equals(reply.stopReason())) {
                    throw new IllegalStateException("모델이 이 화의 검토를 거부했습니다(refusal). 원고 내용을 확인해 주세요.");
                }
                return full.toString().trim();
            }
            // 이어받기: 지금까지 받은 응답을 그대로 대화에 넣고 계속을 요청한다.
            log.info("[PROOF] 응답이 길어 이어받기 {}회차", round + 1);
            messages.add(message("assistant", reply.text()));
            messages.add(message("user",
                    "출력 한도에서 끊겼습니다. 방금 목록의 바로 다음 항목부터 같은 형식으로 이어서 작성하세요. "
                    + "이미 낸 항목을 다시 쓰거나 요약하지 말고, 번호도 이어서 매기세요."));
            full.append("\n");
        }
        log.warn("[PROOF] 이어받기 {}회를 넘겼습니다 — 목록이 잘렸을 수 있습니다", MAX_CONTINUATIONS);
        full.append("\n[프로그램 알림] 응답이 매우 길어 일부가 잘렸을 수 있습니다. 이 화는 다시 돌려 확인해 주세요.\n");
        return full.toString().trim();
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private record Reply(String text, String stopReason) {}

    private Reply call(String systemPrompt, List<Map<String, Object>> messages, ProofreadJob job) throws Exception {
        boolean withFallbacks = useFallbacks;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        // 교정교열은 문맥을 따져 판단하는 작업이라 사고(thinking)를 켜 둔다.
        body.put("thinking", Map.of("type", "adaptive"));
        // 프롬프트는 모든 화에서 글자 하나 안 바뀌므로 캐시해 두면 2화부터는
        // 같은 내용을 다시 계산하지 않는다.
        body.put("system", List.of(Map.of(
                "type", "text",
                "text", systemPrompt,
                "cache_control", Map.of("type", "ephemeral"))));
        body.put("messages", messages);
        if (withFallbacks) body.put("fallbacks", "default");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        if (withFallbacks) headers.set("anthropic-beta", "server-side-fallback-2026-07-01");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        int maxRetries = 6;
        for (int i = 0; i < maxRetries; i++) {
            try {
                // 응답을 byte[] 로 받아 Jackson 이 직접 UTF-8 로 풀게 한다.
                // String.class 로 받으면 Spring 이 ISO-8859-1 로 읽어 한글이 깨진다.
                ResponseEntity<byte[]> response = restTemplate.postForEntity(URL, request, byte[].class);
                JsonNode root = mapper.readTree(response.getBody());

                JsonNode usage = root.path("usage");
                if (job != null) {
                    job.addTokens(usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0));
                }

                // thinking 을 켜 두면 content 의 첫 블록이 본문이 아닐 수 있어,
                // type 이 text 인 블록만 골라 이어 붙인다.
                StringBuilder text = new StringBuilder();
                for (JsonNode block : root.path("content")) {
                    if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
                }
                return new Reply(text.toString(), root.path("stop_reason").asText(""));

            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                long waitMs = backoffMs(i, 20_000);
                String retryAfter = e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst("retry-after") : null;
                if (retryAfter != null) {
                    try {
                        waitMs = Math.min(60_000, Long.parseLong(retryAfter.trim()) * 1000);
                    } catch (NumberFormatException ignored) {}
                }
                log.warn("[429] 레이트리밋 (시도 {}/{}) → {}ms 대기", i + 1, maxRetries, waitMs);
                if (i == maxRetries - 1) throw e;
                Thread.sleep(waitMs);
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                long waitMs = backoffMs(i, 5_000);
                log.warn("[{}] 서버오류/과부하 (시도 {}/{}) → {}ms 대기",
                        e.getStatusCode().value(), i + 1, maxRetries, waitMs);
                if (i == maxRetries - 1) throw e;
                Thread.sleep(waitMs);
            } catch (org.springframework.web.client.ResourceAccessException e) {
                long waitMs = backoffMs(i, 5_000);
                log.warn("[NET] 네트워크/타임아웃 (시도 {}/{}): {} → {}ms 대기",
                        i + 1, maxRetries, e.getMessage(), waitMs);
                if (i == maxRetries - 1) throw e;
                Thread.sleep(waitMs);
            } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
                // 안전 폴백 기능이 아직 안 열린 계정이면 여기서 400 이 난다.
                // 그 경우 그 항목만 빼고 한 번 더 시도한다 — 교정 자체는 영향 없다.
                String bodyText = e.getResponseBodyAsString();
                if (withFallbacks && bodyText.contains("fallback")) {
                    log.warn("[PROOF] 안전 폴백 기능을 쓸 수 없어 빼고 재시도합니다: {}", bodyText);
                    useFallbacks = false;
                    return call(systemPrompt, messages, job);
                }
                log.warn("[400] 요청 오류(재시도 안함): {}", bodyText);
                throw e;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                log.warn("[{}] 클라이언트오류(재시도 안함): {}", e.getStatusCode().value(), e.getResponseBodyAsString());
                throw e;
            }
        }
        throw new RuntimeException("교정교열 요청 실패 (최대 재시도 초과)");
    }

    private long backoffMs(int attempt, long baseMs) {
        return Math.min(60_000L, baseMs * (1L << Math.min(attempt, 10)));
    }

    /** 결과 파일 머리말에 어떤 모델로 돌렸는지 남기기 위해 쓴다. */
    public String modelName() { return MODEL; }
}
