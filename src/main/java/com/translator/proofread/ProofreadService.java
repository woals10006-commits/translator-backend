package com.translator.proofread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


/**
 * 교정교열 작업의 본체.
 *
 * 파일 하나를 통째로 맡기면 정확도가 떨어지므로, 원고를 화 단위로 잘라 한 화당
 * 한 번씩 따로 요청한다. 각 요청은 서로 이어지지 않는 별개의 대화라서 다른 화나
 * 다른 소설의 내용이 섞이지 않는다. 화를 나눠 보내면 놓치게 되는 "앞뒤 화의
 * 표기 일관성"은 작품 용어집과 맨 끝의 일관성 점검 한 번으로 메운다.
 */
@Service
public class ProofreadService {

    private static final Logger log = LoggerFactory.getLogger(ProofreadService.class);

    // 화 단위 요청을 몇 개까지 동시에 보낼지. 너무 높이면 레이트리밋에 걸리고,
    // 1이면 15화짜리 파일에 한참 걸린다.
    private static final int CONCURRENCY = 3;

    private final ChapterReader reader;
    private final ProofreadClaudeClient claude;
    private final ProofreadJobStore jobStore;
    private final PromptProvider prompts;

    public ProofreadService(ChapterReader reader, ProofreadClaudeClient claude,
                            ProofreadJobStore jobStore, PromptProvider prompts) {
        this.reader = reader;
        this.claude = claude;
        this.jobStore = jobStore;
        this.prompts = prompts;
    }

    /** 등록한 작품 폴더에 있는 원고를 검토한다. 결과는 그 폴더에 저장된다. */
    @Async
    public void proofreadAsync(String jobId, Work work, String fileName,
                               int startChapter, int endChapter, String promptOverride) {
        run(jobId, work, fileName, null, startChapter, endChapter, promptOverride);
    }

    /**
     * 화면에서 끌어다 놓아 올린 원고를 검토한다. 브라우저는 그 파일이 어느
     * 폴더에서 왔는지 알려주지 않으므로, 결과는 다운로드 폴더에 저장한다.
     */
    @Async
    public void proofreadUploadAsync(String jobId, Work work, String fileName, byte[] uploaded,
                                     int startChapter, int endChapter, String promptOverride) {
        run(jobId, work, fileName, uploaded, startChapter, endChapter, promptOverride);
    }

    /** 원고를 화 단위로 나누지 않고 글자만 뽑는다 (프롬프트를 파일로 넣을 때). */
    public String extractPlainText(byte[] bytes, String fileName) throws Exception {
        return reader.extractPlainText(bytes, fileName);
    }

    private void run(String jobId, Work work, String fileName, byte[] uploaded,
                     int startChapter, int endChapter, String promptOverride) {
        ProofreadJob job = jobStore.find(jobId);
        long jobStart = System.currentTimeMillis();
        ExecutorService pool = null;
        try {
            List<ChapterReader.Chapter> all;
            Path outDir;
            if (uploaded != null) {
                all = reader.read(uploaded, fileName);
                outDir = Path.of(System.getProperty("user.home"), "Downloads");
                Files.createDirectories(outDir);
            } else {
                Path folder = Path.of(work.getFolder()).toAbsolutePath().normalize();
                Path file = folder.resolve(fileName).normalize();
                if (!file.startsWith(folder)) throw new IllegalArgumentException("작품 폴더 밖의 파일은 열 수 없습니다.");
                if (!Files.exists(file)) throw new IllegalArgumentException("원고 파일을 찾지 못했습니다: " + fileName);
                all = reader.read(file);
                outDir = folder;
            }
            int lastChapter = all.size();
            if (endChapter > lastChapter) endChapter = lastChapter;
            if (startChapter > lastChapter) {
                throw new IllegalArgumentException("이 파일에는 " + lastChapter + "화까지만 있습니다.");
            }
            final int from = startChapter, to = endChapter;

            List<ChapterReader.Chapter> targets = all.stream()
                    .filter(c -> c.number() >= from && c.number() <= to)
                    .sorted(Comparator.comparingInt(ChapterReader.Chapter::number))
                    .toList();

            job.setTotal(targets.size());
            job.setStatus("0/" + targets.size() + "화 검토 중");
            log.info("[PROOF {}] 시작: 작품={}, 파일={}, {}~{}화 (파일 전체 {}화), 동시={}",
                    jobId, work.getName(), fileName, from, to, lastChapter, CONCURRENCY);

            String systemPrompt = prompts.resolve(work, promptOverride);

            // 화별 결과를 화 번호 순서대로 담아 둘 자리. 병렬로 채우되 출력은
            // 원고 순서를 유지해야 담당자가 원고를 따라가며 볼 수 있다.
            String[] results = new String[targets.size()];
            pool = Executors.newFixedThreadPool(CONCURRENCY);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                final int idx = i;
                final ChapterReader.Chapter chapter = targets.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        results[idx] = claude.ask(systemPrompt,
                                buildChapterMessage(work, fileName, chapter), job);
                    } catch (Exception e) {
                        String msg = rootMessage(e);
                        results[idx] = "[이 화는 검토에 실패했습니다: " + msg + "]\n다시 실행해 주세요.";
                        job.addErrorCount();
                        job.addNote(chapter.number() + "화 검토 실패: " + msg);
                        log.error("[PROOF {}] {}화 실패: {}", jobId, chapter.number(), msg);
                    } finally {
                        job.addCompleted();
                        job.setStatus(job.getCompleted() + "/" + targets.size() + "화 검토 중");
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
            pool.shutdown();

            job.setStatus("표기 일관성 점검 중");
            String consistency = checkConsistency(work, targets, results, job);

            job.setStatus("결과 파일 저장 중");
            String report = buildReport(work, fileName, from, to, lastChapter, targets, results, consistency);
            Path saved = writeReport(outDir, fileName, from, to, lastChapter, report);
            job.setSavedPath(saved.toString());
            job.setDone(true);

            long elapsed = System.currentTimeMillis() - jobStart;
            double cost = job.getInputTokens() / 1_000_000.0 * ProofreadClaudeClient.USD_PER_M_INPUT
                        + job.getOutputTokens() / 1_000_000.0 * ProofreadClaudeClient.USD_PER_M_OUTPUT;
            log.info("[PROOF {}] 완료: {}화, 실패={}, {}초, 비용 ${} (화당 ${})",
                    jobId, targets.size(), job.getErrorCount(), elapsed / 1000,
                    String.format("%.4f", cost),
                    String.format("%.4f", targets.isEmpty() ? 0 : cost / targets.size()));

        } catch (Exception e) {
            job.setError(rootMessage(e));
            job.setDone(true);
            log.error("[PROOF {}] 작업 실패: {}", jobId, e.getMessage(), e);
        } finally {
            if (pool != null && !pool.isShutdown()) pool.shutdownNow();
        }
    }

    /**
     * 한 화에 딸려 나가는 사용자 메시지. 프롬프트는 "파일 전체를 검토하라"고
     * 되어 있으므로, 이번 요청의 검토 범위가 이 화 하나임을 분명히 해 준다.
     * 위치 표기에 쓸 파일명과 회차도 여기서 알려 주기 때문에 모델이 회차를
     * 짐작할 필요가 없다.
     */
    private String buildChapterMessage(Work work, String fileName, ChapterReader.Chapter chapter) {
        StringBuilder sb = new StringBuilder();
        sb.append("[이번 검토 대상]\n")
          .append("작품: ").append(work.getName()).append("\n")
          .append("파일명: ").append(fileName).append("\n")
          .append("회차: ").append(chapter.number()).append("화\n")
          .append("회차 제목: ").append(chapter.title()).append("\n\n")
          .append("이번 요청에서 검토할 범위는 아래 ").append(chapter.number())
          .append("화 전문입니다. 이 화의 처음부터 끝까지 한 문장도 건너뛰지 말고 전수 검토하세요.\n")
          .append("각 항목의 위치는 \"").append(fileName).append(" / ")
          .append(chapter.number()).append("화\" 로 적으세요.\n")
          // 결과는 메모장으로 여는 TXT 파일이라 굵게·제목 같은 마크다운 기호가
          // 그대로 글자로 남아 읽기 나빠진다. 처음부터 쓰지 않게 한다.
          .append("출력은 메모장에서 그대로 읽을 순수 텍스트입니다. ")
          .append("**굵게**, ## 제목, 표 같은 마크다운 기호를 쓰지 말고 지정된 형식 그대로만 쓰세요.\n\n");

        if (notBlank(work.getGlossary())) {
            // 화를 나눠 보내면 모델이 앞 화를 못 보므로, 확정된 표기는 용어집으로
            // 매번 함께 보낸다. 프롬프트 1-6 항목이 용어집을 최우선으로 삼는다.
            sb.append("[이 작품의 인명·호칭·용어집 — 최우선으로 따를 것]\n")
              .append(work.getGlossary().trim()).append("\n\n");
        }

        sb.append("--- ").append(chapter.number()).append("화 원문 시작 ---\n")
          .append(chapter.text()).append("\n")
          .append("--- ").append(chapter.number()).append("화 원문 끝 ---\n");
        return sb.toString();
    }

    /**
     * 화별로 따로 검토하면 "3화에서 이름 표기가 바뀐 것"을 잡을 수 없다. 각 화
     * 결과에서 인명·호칭·말투 항목만 모아 마지막에 한 번 대조시킨다.
     */
    private String checkConsistency(Work work, List<ChapterReader.Chapter> targets, String[] results, ProofreadJob job) {
        if (targets.size() < 2) return null;

        StringBuilder collected = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            String picked = pickEntries(results[i], "인명", "호칭", "말투");
            if (!picked.isBlank()) {
                collected.append("[").append(targets.get(i).number()).append("화]\n").append(picked).append("\n");
            }
        }
        if (collected.length() == 0) return null;

        StringBuilder msg = new StringBuilder();
        msg.append("아래는 《").append(work.getName()).append("》의 ")
           .append(targets.get(0).number()).append("화~")
           .append(targets.get(targets.size() - 1).number())
           .append("화를 각각 따로 교정하며 나온 인명·호칭·말투 관련 지적입니다.\n")
           .append("각 화를 따로 봤기 때문에 화와 화 사이의 불일치는 아직 아무도 보지 못했습니다. ")
           .append("이 목록만 놓고 화를 가로질러 대조하여, 같은 인물의 이름이나 호칭이 화마다 다르게 적힌 경우, ")
           .append("말투가 이유 없이 바뀐 경우만 골라 주세요.\n")
           .append("확실하지 않으면 단정하지 말고 [확인 필요]로 표시하고, 불일치가 없으면 ")
           .append("\"화 사이 표기 불일치 없음\" 이라고만 적으세요. 화별 지적을 다시 나열하지 마세요.\n\n");
        if (notBlank(work.getGlossary())) {
            msg.append("[작품 용어집 — 이 표기가 기준입니다]\n").append(work.getGlossary().trim()).append("\n\n");
        }
        msg.append("[화별 지적 모음]\n").append(collected);

        try {
            return claude.ask("당신은 웹소설 교정 결과를 화 단위로 대조하는 편집 검수자입니다. "
                    + "요청받은 대조 결과만 간결하게 출력하세요.", msg.toString(), job);
        } catch (Exception e) {
            log.warn("[PROOF] 표기 일관성 점검 실패: {}", rootMessage(e));
            job.addNote("표기 일관성 점검 실패: " + rootMessage(e));
            return null;
        }
    }

    /** 결과 목록에서 특정 유형([인명] 등)의 항목 블록만 잘라 낸다. */
    private String pickEntries(String result, String... types) {
        if (result == null) return "";
        String[] lines = result.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (String raw : lines) {
            // 모델이 항목 머리를 **[3] [인명]** 처럼 굵게 감싸 내놓는 경우가
            // 있어, 장식 문자를 걷어낸 뒤에 항목 머리인지 판단한다.
            String line = raw.replaceAll("[*#`]", "").trim();
            boolean isHeader = line.matches("^\\[\\d+\\].*");
            if (isHeader) {
                inside = false;
                for (String t : types) {
                    if (line.contains("[" + t + "]")) { inside = true; break; }
                }
            }
            if (inside) out.append(line).append("\n");
        }
        return out.toString().trim();
    }

    /** 최종 결과 TXT 본문. */
    private String buildReport(Work work, String fileName, int from, int to, int lastChapter,
                               List<ChapterReader.Chapter> targets, String[] results, String consistency) {
        String bar = "=".repeat(60);
        StringBuilder sb = new StringBuilder();
        sb.append(bar).append("\n")
          .append("작품: ").append(work.getName()).append("\n")
          .append("원본 파일: ").append(fileName).append("\n")
          .append("검토 범위: ").append(from).append("화 ~ ").append(to).append("화")
          .append(" (파일 전체 ").append(lastChapter).append("화)\n")
          .append("작성 시각: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n")
          .append("검토 모델: ").append(claude.modelName()).append(" (화 단위로 나누어 검토)\n")
          .append(bar).append("\n\n");

        for (int i = 0; i < targets.size(); i++) {
            ChapterReader.Chapter c = targets.get(i);
            sb.append(bar).append("\n")
              .append(c.number()).append("화  ").append(c.title()).append("\n")
              .append(bar).append("\n\n")
              .append(results[i] == null ? "(결과 없음)" : results[i].trim()).append("\n\n");
        }

        if (notBlank(consistency)) {
            sb.append(bar).append("\n")
              .append("「화 사이 표기 일관성 점검」\n")
              .append(bar).append("\n\n")
              .append(consistency.trim()).append("\n\n");
        }

        String issues = scanIssueWords(work, targets);
        if (notBlank(issues)) {
            sb.append(bar).append("\n")
              .append("「자동 검사: 등록된 이슈단어」\n")
              .append(bar).append("\n\n")
              .append(issues).append("\n");
        }
        return sb.toString();
    }

    /**
     * 프롬프트 2-10 항목(저고리·설날 같은 한국 전통 요소)은 AI가 놓칠 수 있어,
     * 작품에 등록해 둔 이슈단어로 원고를 기계적으로 한 번 더 훑는다. 여기서
     * 나온 것은 확실히 원고에 있는 단어다.
     */
    private String scanIssueWords(Work work, List<ChapterReader.Chapter> targets) {
        if (!notBlank(work.getIssueWords())) return null;

        Set<String> words = new LinkedHashSet<>();
        for (String w : work.getIssueWords().split("[\\r\\n,]+")) {
            if (!w.isBlank()) words.add(w.trim());
        }
        if (words.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        int found = 0;
        for (String word : words) {
            StringBuilder hits = new StringBuilder();
            int totalCount = 0;
            for (ChapterReader.Chapter c : targets) {
                int n = countOccurrences(c.text(), word);
                if (n > 0) {
                    totalCount += n;
                    if (hits.length() > 0) hits.append(", ");
                    hits.append(c.number()).append("화 ").append(n).append("회");
                }
            }
            if (totalCount > 0) {
                found++;
                sb.append("· ").append(word).append(" — 총 ").append(totalCount).append("회 (")
                  .append(hits).append(")\n");
            }
        }
        if (found == 0) return "등록된 이슈단어 " + words.size() + "개 중 원고에 나온 것 없음.";
        return "등록된 이슈단어 " + words.size() + "개 중 " + found + "개가 원고에 있습니다.\n\n" + sb;
    }

    private int countOccurrences(String text, String word) {
        if (text == null || word.isEmpty()) return 0;
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = word.toLowerCase(Locale.ROOT);
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * 결과를 원고와 같은 폴더에 저장한다. 담당자가 원고를 찾아간 자리에서 결과도
     * 바로 보이도록 하기 위한 것이라, 다운로드 폴더로 보내지 않는다.
     */
    private Path writeReport(Path folder, String fileName, int from, int to, int lastChapter, String report)
            throws Exception {
        String base = fileName.replaceAll("(?i)\\.(docx|txt|hwp|hwpx)$", "");
        // 파일 전체를 돌린 경우엔 파일명 그대로, 일부만 돌린 경우엔 범위를 덧붙여
        // 같은 원고의 다른 범위 결과가 서로 덮어쓰지 않게 한다.
        boolean whole = (from == 1 && to >= lastChapter);
        String name = base + (whole ? "" : " " + from + "-" + to + "화") + " AI교정교열 결과.TXT";

        Path out = folder.resolve(name);
        for (int n = 2; Files.exists(out); n++) {
            out = folder.resolve(base + (whole ? "" : " " + from + "-" + to + "화")
                    + " AI교정교열 결과 (" + n + ").TXT");
        }
        // 메모장에서 바로 열어도 한글이 깨지지 않도록 UTF-8 BOM 을 붙인다.
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = report.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);
        Files.write(out, bytes);
        log.info("[PROOF] 결과 저장: {}", out);
        return out;
    }

    /** 끌어다 놓은 원고에 화가 몇 개 있는지 (화 범위 입력칸 기본값용). */
    public int countChapters(byte[] bytes, String fileName) throws Exception {
        return reader.read(bytes, fileName).size();
    }

    /** 파일 안에 화가 몇 개 있는지만 미리 알려 준다 (화 범위 입력칸 기본값용). */
    public int countChapters(Work work, String fileName) throws Exception {
        Path folder = Path.of(work.getFolder()).toAbsolutePath().normalize();
        Path file = folder.resolve(fileName).normalize();
        if (!file.startsWith(folder)) throw new IllegalArgumentException("작품 폴더 밖의 파일은 열 수 없습니다.");
        return reader.read(file).size();
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** 스레드풀을 거치면 진짜 원인이 감싸여 오므로 벗겨 낸다. */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getMessage() == null) t = t.getCause();
        if (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
