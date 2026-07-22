package com.translator.service;

import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TranslatorService {

    private static final Logger log = LoggerFactory.getLogger(TranslatorService.class);

    private static final int CHUNK_SIZE = 5;
    // Number of chunks translated concurrently. Network latency dominates, so
    // running several API calls in parallel is the main throughput win.
    private static final int CONCURRENCY = 5;
    // How many source paragraphs immediately before a chunk are passed to the
    // model as read-only context, so translations flow across chunk boundaries
    // and keep tone/terminology consistent. Small on purpose to limit added cost.
    private static final int CONTEXT_PARAGRAPHS = 2;

    // Claude Opus 4.8 pricing, USD per 1M tokens (claude-opus-4-8).
    private static final double USD_PER_M_INPUT = 5.0;
    private static final double USD_PER_M_OUTPUT = 25.0;

    private final GeminiService geminiService;
    private final TranslationJobStore jobStore;

    public TranslatorService(GeminiService geminiService, TranslationJobStore jobStore) {
        this.geminiService = geminiService;
        this.jobStore = jobStore;
    }

    @Async
    public void translateAsync(String jobId, byte[] fileBytes, int startChapter, int endChapter,
                               String customPrompt, String originalFilename) {
        TranslationJob job = jobStore.find(jobId);
        try {
            XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));

            long jobStart = System.currentTimeMillis();

            List<XWPFParagraph> toTranslate = collectParagraphs(document, startChapter, endChapter);
            job.setTotal(toTranslate.size());

            // Collecting nothing almost always means chapter headings weren't
            // recognized (so the whole doc looks like "chapter 0"). Surface a
            // clear error instead of returning an empty file dressed up as 완료,
            // and dump a sample of the document so we can fix the heading regex.
            if (toTranslate.isEmpty()) {
                dumpHeadingDiagnostics(document);
                document.close();
                job.setError("지정한 범위(" + startChapter + "~" + endChapter + "화)에서 번역할 내용을 찾지 못했습니다. "
                        + "문서의 '화(챕터)' 구분이 인식되지 않았을 수 있습니다.");
                job.setDone(true);
                log.warn("[JOB {}] 수집된 단락 0개 — 화범위={}~{}, 챕터 인식 실패 가능", jobId, startChapter, endChapter);
                return;
            }

            // Extract all source texts up front, before any paragraph is mutated
            // by applyTranslation, so a chunk's preceding-context always reflects
            // the ORIGINAL text (not an already-translated neighbor).
            List<String> allTexts = new ArrayList<>();
            for (XWPFParagraph p : toTranslate) allTexts.add(p.getText());

            // Split into chunks up front. For each chunk, capture the last few
            // source paragraphs before it as flow context (see CONTEXT_PARAGRAPHS).
            List<List<XWPFParagraph>> chunks = new ArrayList<>();
            List<String> chunkContexts = new ArrayList<>();
            for (int i = 0; i < toTranslate.size(); i += CHUNK_SIZE) {
                chunks.add(new ArrayList<>(toTranslate.subList(i, Math.min(i + CHUNK_SIZE, toTranslate.size()))));
                StringBuilder ctx = new StringBuilder();
                for (int k = Math.max(0, i - CONTEXT_PARAGRAPHS); k < i; k++) {
                    ctx.append(allTexts.get(k)).append("\n");
                }
                chunkContexts.add(ctx.toString().trim());
            }

            log.info("[JOB {}] 시작: 단락={}, 청크={}, 화범위={}~{}, 동시성={}",
                    jobId, toTranslate.size(), chunks.size(), startChapter, endChapter, CONCURRENCY);

            // Fire off the API calls concurrently. POI document mutation is NOT
            // thread-safe, so only the network calls run in parallel here; the
            // results are applied to the document sequentially below.
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
            List<List<String>> chunkTexts = new ArrayList<>();
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int c = 0; c < chunks.size(); c++) {
                List<String> texts = new ArrayList<>();
                for (XWPFParagraph p : chunks.get(c)) texts.add(p.getText());
                chunkTexts.add(texts);
                String ctx = chunkContexts.get(c);
                futures.add(pool.submit(() -> geminiService.translateBatch(texts, ctx, customPrompt, job)));
            }

            // A fatal, account-level error (no credits / bad key) would otherwise
            // fail every single chunk. When we recognize one, stop early instead
            // of hammering the API with hundreds of doomed calls.
            String fatalError = null;
            String lastError = null;
            try {
                for (int c = 0; c < chunks.size(); c++) {
                    List<XWPFParagraph> chunk = chunks.get(c);
                    List<String> texts = chunkTexts.get(c);
                    long chunkStart = System.currentTimeMillis();
                    try {
                        List<String> translations = futures.get(c).get();
                        for (int j = 0; j < chunk.size(); j++) {
                            String translated = j < translations.size() ? translations.get(j) : texts.get(j);
                            applyTranslation(chunk.get(j), translated);
                        }
                        log.info("[JOB {}] 청크 {}/{} 완료 ({}단락, {}ms)",
                                jobId, c + 1, chunks.size(), chunk.size(),
                                System.currentTimeMillis() - chunkStart);
                    } catch (Exception e) {
                        job.addErrorCount(chunk.size());
                        lastError = rootMessage(e);
                        log.warn("[JOB {}] 청크 {}/{} 실패 ({}ms): {}",
                                jobId, c + 1, chunks.size(),
                                System.currentTimeMillis() - chunkStart, lastError);
                        String fatal = classifyFatal(e);
                        if (fatal != null) {
                            fatalError = fatal;
                            job.addCompleted(chunk.size());
                            break;
                        }
                    }
                    job.addCompleted(chunk.size());
                }
            } finally {
                // On a fatal error we abandon the in-flight chunks; cancel the
                // queued ones so they don't keep firing failing calls.
                if (fatalError != null) pool.shutdownNow();
                else pool.shutdown();
            }

            // Don't hand back an untranslated file dressed up as "완료".
            // Surface a real error when the run was a config failure or a total wipeout.
            if (fatalError != null) {
                document.close();
                job.setError(fatalError);
                job.setDone(true);
                log.error("[JOB {}] 치명적 오류로 중단: {}", jobId, fatalError);
                return;
            }
            if (toTranslate.size() > 0 && job.getErrorCount() >= toTranslate.size()) {
                document.close();
                job.setError("번역에 모두 실패했습니다" + (lastError != null ? " (" + lastError + ")" : "") + ".");
                job.setDone(true);
                log.error("[JOB {}] 전체 단락 번역 실패", jobId);
                return;
            }

            // Second pass: automatically re-translate any paragraph still left in
            // the source language (a chunk that failed, or a paragraph missed in
            // the first pass), so the saved file has as few untranslated
            // paragraphs as possible. Only the leftovers are sent, so this is cheap.
            retryUntranslated(jobId, toTranslate, customPrompt, job);

            // Report the accurate final count: paragraphs still in Chinese after
            // the retry pass (more precise than the chunk-level error tally).
            int stillUntranslated = 0;
            for (XWPFParagraph p : toTranslate) {
                if (hasUntranslatedChinese(p.getText())) stillUntranslated++;
            }
            job.setErrorCount(stillUntranslated);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            document.close();

            byte[] resultBytes = out.toByteArray();
            job.setResult(resultBytes);
            // Persist to disk immediately so the result survives even if the
            // browser tab is closed or the backend stops before download.
            String savedPath = saveResultToDisk(resultBytes, originalFilename, startChapter, endChapter);
            job.setSavedPath(savedPath);
            job.setDone(true);

            long elapsed = System.currentTimeMillis() - jobStart;
            long inTok = job.getInputTokens();
            long outTok = job.getOutputTokens();
            double cost = inTok / 1_000_000.0 * USD_PER_M_INPUT
                        + outTok / 1_000_000.0 * USD_PER_M_OUTPUT;
            // Per-chapter normalization so a small sample run extrapolates to 100화.
            int numChapters = endChapter - startChapter + 1;
            double costPerChapter = numChapters > 0 ? cost / numChapters : 0;
            log.info("[JOB {}] 완료: {}단락, 오류={}, 총 {}초 ({}ms/단락)",
                    jobId, toTranslate.size(), job.getErrorCount(),
                    elapsed / 1000,
                    toTranslate.isEmpty() ? 0 : elapsed / toTranslate.size());
            log.info("[JOB {}] 비용: 입력 {}토큰 + 출력 {}토큰 = ${} (화당 ${}, 100화 환산 ${})",
                    jobId, inTok, outTok,
                    String.format("%.4f", cost),
                    String.format("%.4f", costPerChapter),
                    String.format("%.2f", costPerChapter * 100));

        } catch (Exception e) {
            job.setError(e.getMessage());
            job.setDone(true);
            log.error("[JOB {}] 작업 실패: {}", jobId, e.getMessage(), e);
        }
    }

    // Unwrap ExecutorService's ExecutionException to the real cause's message.
    private String rootMessage(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        return cause.getMessage();
    }

    // Recognize account-level failures that will affect EVERY chunk, and turn
    // them into a clear user-facing message. Returns null for transient errors
    // (timeouts, occasional 5xx) that are fine to treat as per-chunk failures.
    private String classifyFatal(Throwable t) {
        String msg = rootMessage(t);
        if (msg == null) return null;
        if (msg.contains("credit balance is too low")) {
            return "Anthropic API 크레딧이 부족합니다. console.anthropic.com의 Plans & Billing에서 크레딧을 충전해 주세요.";
        }
        if (msg.contains("invalid x-api-key") || msg.contains("authentication_error")
                || msg.contains("401")) {
            return "Anthropic API 키가 유효하지 않습니다. 키를 확인해 주세요.";
        }
        return null;
    }

    // Re-translate paragraphs that are still in the source language after the
    // first pass. These are the chunks/paragraphs that failed transiently; a
    // single retry (often when the API is less loaded) recovers almost all of
    // them. Best-effort: whatever still fails is left as original and reported.
    private void retryUntranslated(String jobId, List<XWPFParagraph> paragraphs,
                                   String customPrompt, TranslationJob job) {
        List<XWPFParagraph> leftover = new ArrayList<>();
        List<String> leftoverTexts = new ArrayList<>();
        for (XWPFParagraph p : paragraphs) {
            String t = p.getText();
            if (hasUntranslatedChinese(t)) { leftover.add(p); leftoverTexts.add(t); }
        }
        if (leftover.isEmpty()) return;

        log.info("[JOB {}] 자동 재시도: 미번역 {}문단 재번역 시도", jobId, leftover.size());
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<List<XWPFParagraph>> chunks = new ArrayList<>();
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int i = 0; i < leftover.size(); i += CHUNK_SIZE) {
                int end = Math.min(i + CHUNK_SIZE, leftover.size());
                chunks.add(new ArrayList<>(leftover.subList(i, end)));
                List<String> texts = new ArrayList<>(leftoverTexts.subList(i, end));
                futures.add(pool.submit(() -> geminiService.translateBatch(texts, "", customPrompt, job)));
            }
            int fixed = 0;
            for (int c = 0; c < chunks.size(); c++) {
                List<XWPFParagraph> chunk = chunks.get(c);
                try {
                    List<String> translations = futures.get(c).get();
                    for (int j = 0; j < chunk.size(); j++) {
                        // Apply only if the retry actually produced Korean text.
                        if (j < translations.size()) {
                            String tr = translations.get(j);
                            if (tr != null && !tr.isBlank() && !hasUntranslatedChinese(tr)) {
                                applyTranslation(chunk.get(j), tr);
                                fixed++;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[JOB {}] 재시도 청크 {}/{} 실패: {}", jobId, c + 1, chunks.size(), rootMessage(e));
                }
            }
            log.info("[JOB {}] 자동 재시도 완료: {}/{} 문단 채움", jobId, fixed, leftover.size());
        } finally {
            pool.shutdown();
        }
    }

    // A paragraph is considered untranslated if it still contains Chinese (CJK
    // ideographs) but no Korean (Hangul syllables). Since the output is Korean,
    // a Chinese-only paragraph reliably marks a failed/missed translation.
    private boolean hasUntranslatedChinese(String text) {
        if (text == null) return false;
        boolean hasChinese = false, hasKorean = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) hasChinese = true;      // CJK Unified Ideographs
            else if (c >= 0xAC00 && c <= 0xD7A3) hasKorean = true;  // Hangul syllables
        }
        return hasChinese && !hasKorean;
    }

    // Auto-save the finished document to disk so a completed job is never lost
    // to a closed browser tab or a backend restart. Saved under the user's
    // Downloads/번역결과 folder with the chapter range + timestamp in the name so
    // separate batches never overwrite each other. Returns the absolute path, or
    // null if saving failed (the in-memory result / browser download still work).
    private String saveResultToDisk(byte[] bytes, String originalFilename, int startChapter, int endChapter) {
        try {
            String base = (originalFilename == null || originalFilename.isBlank()) ? "translated" : originalFilename;
            base = base.replaceAll(".*[/\\\\]", "");                 // strip any path
            if (base.toLowerCase().endsWith(".docx")) base = base.substring(0, base.length() - 5);
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String name = "translated_" + base + "_" + startChapter + "-" + endChapter + "_" + ts + ".docx";
            Path dir = Path.of(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(dir);
            Path outPath = dir.resolve(name);
            Files.write(outPath, bytes);
            log.info("[SAVE] 결과 디스크 저장 완료: {}", outPath);
            return outPath.toString();
        } catch (Exception e) {
            log.error("[SAVE] 결과 디스크 저장 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    // Diagnostic: write the first non-blank paragraphs (raw text, style, and
    // whether isChapterHeading matched) to a UTF-8 file so the actual chapter
    // heading format can be inspected without console mojibake.
    private void dumpHeadingDiagnostics(XWPFDocument document) {
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (XWPFParagraph p : document.getParagraphs()) {
                String t = p.getText();
                if (isBlank(t)) continue;
                boolean h = isChapterHeading(p, 1);
                sb.append(String.format("[%02d] heading=%s style=%s | %s%n",
                        n, h, p.getStyle(), t));
                if (++n >= 30) break;
            }
            Path out = Path.of(System.getProperty("java.io.tmpdir"), "translator-heading-diag.txt");
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            log.warn("[DIAG] 헤딩 진단 파일 기록: {}", out);
        } catch (Exception e) {
            log.warn("[DIAG] 진단 기록 실패: {}", e.getMessage());
        }
    }

    // Collect only paragraphs whose chapter falls within [startChapter, endChapter]
    // (1-based, inclusive). Chapters are counted from the document's real first
    // chapter, so paragraphs before startChapter are skipped and we stop once we
    // pass endChapter — letting the user translate e.g. only 20화~50화.
    private List<XWPFParagraph> collectParagraphs(XWPFDocument document, int startChapter, int endChapter) {
        List<XWPFParagraph> result = new ArrayList<>();
        int chapterCount = 0;

        for (XWPFParagraph p : document.getParagraphs()) {
            if (isChapterHeading(p, chapterCount + 1)) {
                chapterCount++;
                if (chapterCount > endChapter) break;
            }
            if (chapterCount > endChapter) break;
            // Only keep paragraphs at or after the start chapter.
            if (chapterCount >= startChapter && !isBlank(p.getText())) {
                result.add(p);
            }
        }
        return result;
    }

    // The source documents use non-breaking (U+00A0) / ideographic (U+3000)
    // spaces for blank spacer lines. String.trim() only strips chars <= U+0020,
    // so those lines look non-empty and would be sent to the API needlessly,
    // roughly doubling the work. Normalize them before the emptiness check.
    private boolean isBlank(String text) {
        if (text == null) return true;
        // Character.isSpaceChar covers Unicode spaces that String.trim() misses,
        // notably non-breaking (U+00A0) and ideographic (U+3000) — used here as
        // blank spacer lines. Without this they'd be sent to the API needlessly.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }

    // Chapter numbers run sequentially; only a "N." line whose number equals the
    // next expected chapter counts as a heading, so body sentences starting with
    // a number (e.g. "3. ..." / "2019. ...") aren't mistaken for chapter breaks.
    // The space class includes U+00A0/U+3000 because \s doesn't match those.
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^(\\d+)\\.[\\s\\u00a0\\u3000]");

    private boolean isChapterHeading(XWPFParagraph paragraph, int expectedNumber) {
        String style = paragraph.getStyle();
        if (style != null && (style.toLowerCase().startsWith("heading"))) return true;
        // Normalize non-breaking ( ) and ideographic/full-width (　)
        // spaces to a regular space. Headings here look like "1.  제목",
        // and \s does NOT match   in Java regex — without this the "\d+\."
        // heading pattern never fires, chapterCount stays 0, maxChapters is
        // ignored, and the entire book gets translated instead of N chapters.
        String text = paragraph.getText().replace(' ', ' ').replace('　', ' ').trim();
        // Unambiguous chapter markers. N may be Arabic (第1章), full-width (第１章),
        // or Chinese numerals (第一章 / 第二十章). Spaces are allowed between 第, the
        // number, and 章 because some sources write "第 1 章" (this document does).
        if (text.matches("^(第\\s*[一二三四五六七八九十百千零〇两\\d０-９]+\\s*[章回話话節节集卷篇]|\\d+\\s*[章话話集回]|Chapter\\s*\\d+|\\d+\\s*화|\\d+\\s*장).*")) return true;
        // "1.  제목" style headings are ambiguous, so accept one only when its
        // number is the next expected chapter (sequential numbering).
        Matcher m = NUMBERED_HEADING.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1)) == expectedNumber;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private void applyTranslation(XWPFParagraph paragraph, String translated) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (!runs.isEmpty()) {
            runs.get(0).setText(translated, 0);
            for (int i = runs.size() - 1; i > 0; i--) {
                paragraph.removeRun(i);
            }
        }
    }
}
