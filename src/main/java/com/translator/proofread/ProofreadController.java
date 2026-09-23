package com.translator.proofread;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 교정교열 화면이 쓰는 API. 작품(프로젝트) 등록·수정, 작품 폴더의 원고 목록,
 * 작업 시작과 진행 상황 조회.
 */
@RestController
@RequestMapping("/api/proofread")
@CrossOrigin(origins = "http://localhost:5173")
public class ProofreadController {

    // 편집자들이 워드(.docx)와 한글(.hwp/.hwpx)을 섞어 쓰고, 텍스트만 뽑아 둔
    // 원고도 있어서 네 가지를 모두 받는다.
    private static final List<String> MANUSCRIPT_TYPES = List.of(".docx", ".txt", ".hwp", ".hwpx");

    private final WorkStore workStore;
    private final ProofreadService service;
    private final ProofreadJobStore jobStore;
    private final PromptProvider prompts;

    public ProofreadController(WorkStore workStore, ProofreadService service,
                               ProofreadJobStore jobStore, PromptProvider prompts) {
        this.workStore = workStore;
        this.service = service;
        this.jobStore = jobStore;
        this.prompts = prompts;
    }

    /** 화면이 프롬프트 입력칸 기본값으로 쓸 공통 프롬프트. */
    @GetMapping("/common-prompt")
    public Map<String, String> commonPrompt() {
        return Map.of("prompt", prompts.common());
    }

    @GetMapping("/works")
    public List<Work> works() {
        return workStore.findAll();
    }

    @PostMapping("/works")
    public ResponseEntity<?> saveWork(@RequestBody Work work) {
        if (work.getName() == null || work.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "작품명을 입력해 주세요."));
        }
        if (work.getFolder() == null || work.getFolder().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "원고가 들어 있는 폴더 경로를 입력해 주세요."));
        }
        Path folder = Path.of(work.getFolder().trim());
        if (!Files.isDirectory(folder)) {
            return ResponseEntity.badRequest().body(Map.of("error", "그런 폴더가 없습니다: " + work.getFolder()));
        }
        work.setFolder(folder.toAbsolutePath().normalize().toString());
        return ResponseEntity.ok(workStore.save(work));
    }

    @DeleteMapping("/works/{id}")
    public ResponseEntity<Void> deleteWork(@PathVariable String id) {
        workStore.delete(id);
        return ResponseEntity.ok().build();
    }

    /** 작품 폴더 안의 원고 파일 목록. 결과 TXT 는 원고와 섞이지 않게 걸러 낸다. */
    @GetMapping("/works/{id}/files")
    public ResponseEntity<?> files(@PathVariable String id) {
        Work work = workStore.find(id);
        if (work == null) return ResponseEntity.notFound().build();
        try (Stream<Path> list = Files.list(Path.of(work.getFolder()))) {
            List<String> names = new ArrayList<>();
            list.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                String lower = n.toLowerCase(Locale.ROOT);
                boolean manuscript = MANUSCRIPT_TYPES.stream().anyMatch(lower::endsWith);
                boolean isResult = n.contains("AI교정교열 결과");
                // 워드가 파일을 열어 두면 만드는 임시 파일(~$이름.docx)은 원고가 아니다.
                boolean isTemp = n.startsWith("~$");
                if (manuscript && !isResult && !isTemp) names.add(n);
            });
            names.sort(String::compareTo);
            return ResponseEntity.ok(names);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "폴더를 읽지 못했습니다: " + e.getMessage()));
        }
    }

    /**
     * 파일 하나에 화가 몇 개 있는지 — 화 범위 입력칸을 채워 주기 위한 것.
     *
     * 파일명을 쿼리 문자열(?file=...)로 받으면 한글 이름이 깨져 들어온다.
     * (폴더 경로의 한글은 멀쩡한데 파라미터만 깨진다.) 원고 파일명은 거의 다
     * 한글이므로 JSON 본문으로 받는다 — 본문은 UTF-8 로 제대로 들어온다.
     */
    @PostMapping("/works/{id}/chapters")
    public ResponseEntity<?> chapters(@PathVariable String id, @RequestBody Map<String, String> req) {
        Work work = workStore.find(id);
        if (work == null) return ResponseEntity.notFound().build();
        String fileName = req.get("fileName");
        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "원고 파일을 선택해 주세요."));
        }
        try {
            return ResponseEntity.ok(Map.of("chapters", service.countChapters(work, fileName)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 끌어다 놓은 원고에 화가 몇 개 있는지 미리 세어 본다. 작품을 등록하지 않고
     * 바로 검토할 때 쓰는 길이다.
     */
    @PostMapping("/inspect")
    public ResponseEntity<?> inspect(@RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename();
        if (!isManuscript(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "워드(.docx), 한글(.hwp/.hwpx), 텍스트(.txt) 파일만 넣을 수 있습니다."));
        }
        try {
            return ResponseEntity.ok(Map.of("chapters", service.countChapters(file.getBytes(), name)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", message(e)));
        }
    }

    /**
     * 프롬프트를 파일로 넣을 때 쓴다. 담당자들이 프롬프트를 워드나 한글 문서로
     * 주고받기 때문에, 글자만 뽑아 화면의 입력칸에 채워 준다.
     */
    @PostMapping("/extract-text")
    public ResponseEntity<?> extractText(@RequestParam("file") MultipartFile file) {
        try {
            String text = service.extractPlainText(file.getBytes(), file.getOriginalFilename());
            if (text == null || text.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "이 파일에서 읽어 올 글자가 없습니다."));
            }
            return ResponseEntity.ok(Map.of("text", text));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", message(e)));
        }
    }

    /** 끌어다 놓은 원고를 바로 검토한다. 결과는 다운로드 폴더에 저장된다. */
    @PostMapping("/start-upload")
    public ResponseEntity<?> startUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "workId", required = false) String workId,
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestParam(value = "startChapter", defaultValue = "1") int startChapter,
            @RequestParam(value = "endChapter", defaultValue = "1") int endChapter) {

        String name = file.getOriginalFilename();
        if (!isManuscript(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "워드(.docx), 한글(.hwp/.hwpx), 텍스트(.txt) 파일만 넣을 수 있습니다."));
        }

        // 작품을 골랐으면 그 프롬프트·용어집을 쓰고, 안 골랐으면 공통 프롬프트에
        // 파일명을 작품명으로 넣어 임시 작품처럼 다룬다.
        Work work = (workId == null || workId.isBlank()) ? null : workStore.find(workId);
        if (work == null) {
            work = new Work();
            work.setName(name.replaceAll("(?i)\\.(docx|txt|hwp|hwpx)$", ""));
        }

        if (startChapter < 1) startChapter = 1;
        if (endChapter < startChapter) endChapter = startChapter;

        try {
            String jobId = UUID.randomUUID().toString();
            jobStore.save(new ProofreadJob(jobId));
            service.proofreadUploadAsync(jobId, work, name, file.getBytes(), startChapter, endChapter, prompt);
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", message(e)));
        }
    }

    private boolean isManuscript(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return MANUSCRIPT_TYPES.stream().anyMatch(lower::endsWith);
    }

    private String message(Exception e) {
        return (e.getMessage() == null || e.getMessage().isBlank())
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> req) {
        String workId = String.valueOf(req.get("workId"));
        String fileName = String.valueOf(req.get("fileName"));
        Work work = workStore.find(workId);
        if (work == null) return ResponseEntity.badRequest().body(Map.of("error", "작품을 먼저 선택해 주세요."));
        if (fileName == null || fileName.isBlank() || "null".equals(fileName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "원고 파일을 선택해 주세요."));
        }

        int start = toInt(req.get("startChapter"), 1);
        int end = toInt(req.get("endChapter"), 1);
        if (start < 1) start = 1;
        if (end < start) end = start;

        String prompt = req.get("prompt") == null ? null : String.valueOf(req.get("prompt"));

        String jobId = UUID.randomUUID().toString();
        jobStore.save(new ProofreadJob(jobId));
        service.proofreadAsync(jobId, work, fileName, start, end, prompt);
        return ResponseEntity.ok(Map.of("jobId", jobId));
    }

    @GetMapping("/progress/{jobId}")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable String jobId) {
        ProofreadJob job = jobStore.find(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        Map<String, Object> r = new HashMap<>();
        r.put("progress", job.isDone() && job.getError() == null ? 100 : job.getProgress());
        r.put("done", job.isDone());
        r.put("error", job.getError());
        r.put("errorCount", job.getErrorCount());
        r.put("status", job.getStatus());
        r.put("total", job.getTotal());
        r.put("completed", job.getCompleted());
        r.put("savedPath", job.getSavedPath());
        r.put("notes", job.getNotes());
        double cost = job.getInputTokens() / 1_000_000.0 * ProofreadClaudeClient.USD_PER_M_INPUT
                    + job.getOutputTokens() / 1_000_000.0 * ProofreadClaudeClient.USD_PER_M_OUTPUT;
        r.put("cost", String.format("%.2f", cost));
        return ResponseEntity.ok(r);
    }

    private int toInt(Object v, int fallback) {
        if (v == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
