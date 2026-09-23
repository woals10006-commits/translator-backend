package com.translator.proofread;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 등록된 작품 목록을 사용자 홈의 JSON 파일에 보관한다. 담당자가 한 번 등록해
 * 두면 백엔드를 껐다 켜도 그대로 남는다.
 */
@Component
public class WorkStore {

    private static final Logger log = LoggerFactory.getLogger(WorkStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file = Path.of(System.getProperty("user.home"), ".webnovel-tool", "works.json");
    private final List<Work> works = new ArrayList<>();

    public WorkStore() {
        load();
    }

    private synchronized void load() {
        try {
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                works.clear();
                works.addAll(mapper.readValue(json, new TypeReference<List<Work>>() {}));
                log.info("[WORK] 작품 {}개 불러옴: {}", works.size(), file);
            }
        } catch (Exception e) {
            // 파일이 깨졌다고 서버가 못 뜨면 안 되므로 빈 목록으로 시작한다.
            log.error("[WORK] 작품 목록 읽기 실패 ({}) — 빈 목록으로 시작합니다", e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(works),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[WORK] 작품 목록 저장 실패: {}", e.getMessage(), e);
        }
    }

    public synchronized List<Work> findAll() {
        return new ArrayList<>(works);
    }

    public synchronized Work find(String id) {
        return works.stream().filter(w -> w.getId().equals(id)).findFirst().orElse(null);
    }

    /** id가 없으면 새 작품으로 추가하고, 있으면 그 자리를 덮어쓴다. */
    public synchronized Work save(Work work) {
        if (work.getId() == null || work.getId().isBlank()) {
            work.setId(UUID.randomUUID().toString());
            works.add(work);
        } else {
            boolean replaced = false;
            for (int i = 0; i < works.size(); i++) {
                if (works.get(i).getId().equals(work.getId())) {
                    works.set(i, work);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) works.add(work);
        }
        persist();
        return work;
    }

    public synchronized void delete(String id) {
        works.removeIf(w -> w.getId().equals(id));
        persist();
    }
}
