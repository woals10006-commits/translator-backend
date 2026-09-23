package com.translator.proofread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 교정교열 프롬프트를 고르는 곳.
 *
 * 공통 프롬프트는 프로그램에 들어 있고, 담당자가 작품에 자기 프롬프트를 등록해
 * 두면 그쪽이 쓰인다. 어느 쪽이든 {{작품명}} 자리에는 등록한 작품 이름이
 * 들어가므로, 작품마다 프롬프트를 통째로 복사할 필요가 없다.
 */
@Component
public class PromptProvider {

    private static final Logger log = LoggerFactory.getLogger(PromptProvider.class);
    private static final String RESOURCE = "교정교열_공통.txt";

    private final String common;

    public PromptProvider() {
        String loaded = "";
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            loaded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("[PROMPT] 공통 프롬프트 불러옴 ({}자)", loaded.length());
        } catch (Exception e) {
            log.error("[PROMPT] 공통 프롬프트를 읽지 못했습니다: {}", e.getMessage());
        }
        this.common = loaded;
    }

    /** 화면의 프롬프트 입력칸 기본값으로 내려 줄 공통 프롬프트. */
    public String common() {
        return common;
    }

    /** 이 작품에 실제로 쓸 프롬프트 (작품 전용 → 없으면 공통). */
    public String resolve(Work work) {
        return resolve(work, null);
    }

    /**
     * 이번 검토에 실제로 쓸 프롬프트를 고른다.
     * 화면에서 직접 적어 넣은 것 → 작품에 등록해 둔 것 → 공통 프롬프트 순서다.
     */
    public String resolve(Work work, String override) {
        String prompt = (override != null && !override.isBlank()) ? override
                : (work.getPrompt() != null && !work.getPrompt().isBlank())
                ? work.getPrompt() : common;
        if (prompt.isBlank()) {
            throw new IllegalStateException("교정교열 프롬프트가 비어 있습니다. 작품 설정에서 프롬프트를 등록해 주세요.");
        }
        String name = (work.getName() == null || work.getName().isBlank()) ? "이 작품" : work.getName();
        return prompt.replace("{{작품명}}", name);
    }
}
