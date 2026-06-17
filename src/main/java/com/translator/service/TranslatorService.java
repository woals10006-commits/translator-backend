package com.translator.service;

import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    // Claude Haiku 4.5 pricing, USD per 1M tokens (claude-haiku-4-5).
    private static final double USD_PER_M_INPUT = 1.0;
    private static final double USD_PER_M_OUTPUT = 5.0;

    private final GeminiService geminiService;
    private final TranslationJobStore jobStore;

    public TranslatorService(GeminiService geminiService, TranslationJobStore jobStore) {
        this.geminiService = geminiService;
        this.jobStore = jobStore;
    }

    @Async
    public void translateAsync(String jobId, byte[] fileBytes, int maxChapters) {
        TranslationJob job = jobStore.find(jobId);
        try {
            XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));

            long jobStart = System.currentTimeMillis();

            List<XWPFParagraph> toTranslate = collectParagraphs(document, maxChapters);
            job.setTotal(toTranslate.size());

            // Split into chunks up front.
            List<List<XWPFParagraph>> chunks = new ArrayList<>();
            for (int i = 0; i < toTranslate.size(); i += CHUNK_SIZE) {
                chunks.add(new ArrayList<>(toTranslate.subList(i, Math.min(i + CHUNK_SIZE, toTranslate.size()))));
            }

            log.info("[JOB {}] 시작: 단락={}, 청크={}, maxChapters={}, 동시성={}",
                    jobId, toTranslate.size(), chunks.size(), maxChapters, CONCURRENCY);

            // Fire off the API calls concurrently. POI document mutation is NOT
            // thread-safe, so only the network calls run in parallel here; the
            // results are applied to the document sequentially below.
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
            List<List<String>> chunkTexts = new ArrayList<>();
            List<Future<List<String>>> futures = new ArrayList<>();
            for (List<XWPFParagraph> chunk : chunks) {
                List<String> texts = new ArrayList<>();
                for (XWPFParagraph p : chunk) texts.add(p.getText());
                chunkTexts.add(texts);
                futures.add(pool.submit(() -> geminiService.translateBatch(texts, job)));
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

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            document.close();

            job.setResult(out.toByteArray());
            job.setDone(true);

            long elapsed = System.currentTimeMillis() - jobStart;
            long inTok = job.getInputTokens();
            long outTok = job.getOutputTokens();
            double cost = inTok / 1_000_000.0 * USD_PER_M_INPUT
                        + outTok / 1_000_000.0 * USD_PER_M_OUTPUT;
            // Per-chapter normalization so a small sample run extrapolates to 100화.
            double costPerChapter = maxChapters > 0 ? cost / maxChapters : 0;
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

    private List<XWPFParagraph> collectParagraphs(XWPFDocument document, int maxChapters) {
        List<XWPFParagraph> result = new ArrayList<>();
        int chapterCount = 0;

        for (XWPFParagraph p : document.getParagraphs()) {
            if (isChapterHeading(p, chapterCount + 1)) {
                chapterCount++;
                if (chapterCount > maxChapters) break;
            }
            if (chapterCount > maxChapters) break;
            if (!isBlank(p.getText())) {
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
        // Unambiguous chapter markers: 第N章 / Chapter N / N화 / N장.
        if (text.matches("^(第?\\d+[章话話集回]|Chapter\\s*\\d+|\\d+화|\\d+장).*")) return true;
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
