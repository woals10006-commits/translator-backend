package com.translator.controller;

import com.translator.service.TranslationJob;
import com.translator.service.TranslationJobStore;
import com.translator.service.TranslatorService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class TranslatorController {

    private final TranslatorService translatorService;
    private final TranslationJobStore jobStore;

    public TranslatorController(TranslatorService translatorService, TranslationJobStore jobStore) {
        this.translatorService = translatorService;
        this.jobStore = jobStore;
    }

    @PostMapping("/translate")
    public ResponseEntity<Map<String, String>> startTranslation(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startChapter", defaultValue = "1") int startChapter,
            @RequestParam(value = "endChapter", defaultValue = "100") int endChapter,
            @RequestParam(value = "customPrompt", required = false, defaultValue = "") String customPrompt) throws Exception {

        // Guard against inverted or non-positive input.
        if (startChapter < 1) startChapter = 1;
        if (endChapter < startChapter) endChapter = startChapter;

        String jobId = UUID.randomUUID().toString();
        TranslationJob job = new TranslationJob(jobId);
        jobStore.save(job);

        translatorService.translateAsync(jobId, file.getBytes(), startChapter, endChapter,
                customPrompt, file.getOriginalFilename());

        Map<String, String> response = new HashMap<>();
        response.put("jobId", jobId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/progress/{jobId}")
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable String jobId) {
        TranslationJob job = jobStore.find(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        Map<String, Object> response = new HashMap<>();
        response.put("progress", job.isDone() ? 100 : job.getProgress());
        response.put("done", job.isDone());
        response.put("error", job.getError());
        response.put("errorCount", job.getErrorCount());
        response.put("savedPath", job.getSavedPath());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/download/{jobId}")
    public ResponseEntity<byte[]> download(@PathVariable String jobId) {
        TranslationJob job = jobStore.find(jobId);
        if (job == null || !job.isDone() || job.getResult() == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "translated.docx");

        jobStore.delete(jobId);
        return ResponseEntity.ok().headers(headers).body(job.getResult());
    }
}
