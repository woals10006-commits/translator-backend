package com.translator.proofread;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원고 파일 하나를 화(회차) 단위로 쪼갠다. 파일 전체를 한 번에 교정 요청하면
 * 정확도가 떨어지므로, 여기서 나눈 화가 각각 독립된 요청 한 건이 된다.
 *
 * 원고는 등록한 작품 폴더에서 읽어 올 수도 있고, 화면에서 끌어다 놓아 올린
 * 것일 수도 있어 파일과 바이트 두 가지 입구를 둔다.
 *
 * .docx 는 문단 스타일과 본문을, .txt 는 줄 단위로 훑어 제목 줄을 찾는다.
 */
@Component
public class ChapterReader {

    private static final Logger log = LoggerFactory.getLogger(ChapterReader.class);

    /** 화 하나. number 는 파일 안에서 몇 번째 화인지(1부터), title 은 제목 줄. */
    public record Chapter(int number, String title, String text) {}

    public List<Chapter> read(Path file) throws Exception {
        return read(Files.readAllBytes(file), file.getFileName().toString());
    }

    public List<Chapter> read(byte[] bytes, String fileName) throws Exception {
        boolean docx = fileName.toLowerCase(Locale.ROOT).endsWith(".docx");
        // 한글 파일은 글자만 뽑아내면 그 뒤로는 txt 와 똑같이 다룰 수 있다.
        String plain = docx ? null
                : HwpReader.isHwp(fileName) ? HwpReader.extract(bytes, fileName)
                : decodeText(bytes);

        List<Chapter> chapters = docx ? readDocx(bytes) : readTxt(plain);

        // 제목 줄을 하나도 못 찾으면 파일 전체를 1화로 본다. 화 구분이 없는
        // 원고(1화짜리 파일)도 있으므로 오류로 막지 않고 그대로 진행한다.
        if (chapters.isEmpty()) {
            String all = docx ? joinDocxText(bytes) : plain.trim();
            if (all.isBlank()) {
                throw new IllegalStateException("원고에서 읽을 내용을 찾지 못했습니다: " + fileName);
            }
            log.warn("[CHAPTER] 화 구분을 찾지 못해 파일 전체를 1화로 처리합니다: {}", fileName);
            chapters = List.of(new Chapter(1, "(화 구분 없음)", all));
        }
        return chapters;
    }

    /**
     * 화로 나누지 않고 글자만 통째로 뽑는다. 프롬프트를 파일로 넣을 때 쓴다.
     * 확장자를 모르는 파일은 일단 글자 파일로 보고 읽어 본다.
     */
    public String extractPlainText(byte[] bytes, String fileName) throws Exception {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) return joinDocxText(bytes);
        if (HwpReader.isHwp(lower)) return HwpReader.extract(bytes, lower);
        return decodeText(bytes).trim();
    }

    private List<Chapter> readDocx(byte[] bytes) throws Exception {
        List<Chapter> chapters = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder buf = new StringBuilder();
            String title = null;
            int count = 0;
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (isBlank(text)) continue;
                boolean headingStyle = p.getStyle() != null && p.getStyle().toLowerCase().startsWith("heading");
                if (headingStyle || isChapterHeading(text, count + 1)) {
                    if (title != null) chapters.add(new Chapter(count, title, buf.toString().trim()));
                    count++;
                    title = normalize(text);
                    buf.setLength(0);
                    continue;
                }
                if (title != null) buf.append(text).append("\n");
            }
            if (title != null) chapters.add(new Chapter(count, title, buf.toString().trim()));
        }
        return chapters;
    }

    private List<Chapter> readTxt(String content) {
        List<Chapter> chapters = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String title = null;
        int count = 0;
        for (String line : content.split("\\r?\\n")) {
            if (!isBlank(line) && isChapterHeading(line, count + 1)) {
                if (title != null) chapters.add(new Chapter(count, title, buf.toString().trim()));
                count++;
                title = normalize(line);
                buf.setLength(0);
                continue;
            }
            if (title != null) buf.append(line).append("\n");
        }
        if (title != null) chapters.add(new Chapter(count, title, buf.toString().trim()));
        return chapters;
    }

    private String joinDocxText(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (!isBlank(p.getText())) sb.append(p.getText()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 한글 윈도우에서 만든 txt 는 UTF-8 과 CP949(EUC-KR)가 섞여 있다. 먼저
     * UTF-8 로 읽고 깨진 글자가 보이면 CP949 로 다시 읽는다 — 이슈단어 도구가
     * 쓰던 것과 같은 방식이다.
     */
    private String decodeText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('�') >= 0) {
            return new String(bytes, Charset.forName("x-windows-949"));
        }
        // UTF-8 BOM 은 첫 글자로 남아 제목 인식을 방해하므로 떼어 낸다.
        return utf8.startsWith("﻿") ? utf8.substring(1) : utf8;
    }

    private String normalize(String text) {
        return text.replace(' ', ' ').replace('　', ' ').trim();
    }

    private boolean isBlank(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }

    // "1. 제목" 형태는 본문 문장과 헷갈리므로, 번호가 다음에 올 화 번호와
    // 맞을 때만 제목으로 인정한다. (번역기 쪽과 같은 판단 기준)
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^(\\d+)\\.[\\s\\u00a0\\u3000]");

    // 번호 뒤에 바로 제목이 붙는 형태(1一朝穿越 / 1 사라진 신부). 짧은 줄일 때만.
    private static final Pattern NUMBER_TITLE_HEADING =
            Pattern.compile("^\\d{1,4}\\s*[\\u4E00-\\u9FFF\\uAC00-\\uD7A3]");

    // 번역된 원고라 한국어 표기(1화 / 제1화 / 3장)가 주로 쓰이지만, 원문 표기가
    // 남아 있는 파일도 있어 둘 다 받는다.
    private static final Pattern CHAPTER_MARKER = Pattern.compile(
            "^(제\\s*\\d+\\s*[화장회부]"
            + "|\\d+\\s*[화장회]"
            + "|第\\s*[一二三四五六七八九十百千零〇两\\d０-９]+\\s*[章回話话節节集卷篇]"
            + "|\\d+\\s*[章话話集回]"
            + "|Chapter\\s*\\d+).*");

    boolean isChapterHeading(String raw, int expectedNumber) {
        String text = normalize(raw);
        if (text.isEmpty()) return false;
        if (CHAPTER_MARKER.matcher(text).matches()) return true;

        Matcher m = NUMBERED_HEADING.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1)) == expectedNumber;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return text.length() <= 30 && NUMBER_TITLE_HEADING.matcher(text).find();
    }
}
