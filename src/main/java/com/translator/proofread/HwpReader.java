package com.translator.proofread;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 한글(HWP) 원고에서 본문 글자만 뽑아낸다.
 *
 * 편집자들이 워드가 아니라 한글로 원고를 주고받기 때문에 필요하다. 한글 파일은
 * 두 가지 형식이 있어 각각 다르게 읽는다.
 *
 * - .hwpx : 속이 zip 인 XML 묶음. 워드(.docx)와 비슷하게 풀어서 태그만 걷어낸다.
 * - .hwp  : 한글 5.0 이진 형식. 오래된 오피스 파일과 같은 통(OLE) 안에
 *           본문이 구역별로 압축돼 들어 있고, 그 안이 다시 기록(record) 단위다.
 *
 * 서식·표·그림은 버리고 문단 글자만 남긴다. 교정교열은 글자만 보면 되기 때문이다.
 */
final class HwpReader {

    private static final Logger log = LoggerFactory.getLogger(HwpReader.class);

    private HwpReader() {}

    static boolean isHwp(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".hwp") || n.endsWith(".hwpx");
    }

    static String extract(byte[] bytes, String fileName) throws Exception {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".hwpx")
                ? extractHwpx(bytes)
                : extractHwp(bytes);
    }

    // ---------------- .hwpx (zip + XML) ----------------

    private static String extractHwpx(byte[] bytes) throws Exception {
        // 본문은 Contents/section0.xml, section1.xml ... 에 순서대로 들어 있다.
        List<String> names = new ArrayList<>();
        List<String> xmls = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("contents/section") && name.endsWith(".xml")) {
                    names.add(e.getName());
                    xmls.add(new String(zin.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        if (xmls.isEmpty()) throw new IllegalStateException("한글 파일(.hwpx)에서 본문을 찾지 못했습니다.");

        // section10 이 section2 앞에 오지 않도록 번호로 정렬한다.
        Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(sectionNo(names.get(a)), sectionNo(names.get(b))));

        StringBuilder sb = new StringBuilder();
        for (int idx : order) {
            String xml = xmls.get(idx)
                    .replaceAll("(?i)</hp:p>", "\n")   // 문단 끝 = 줄바꿈
                    .replaceAll("(?i)<hp:lineBreak[^>]*/?>", "\n")
                    .replaceAll("<[^>]+>", "");
            sb.append(unescapeXml(xml)).append("\n");
        }
        return sb.toString();
    }

    private static int sectionNo(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(name);
        int last = 0;
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    // ---------------- .hwp (한글 5.0 이진) ----------------

    private static String extractHwp(byte[] bytes) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            DirectoryEntry root = fs.getRoot();

            boolean compressed = readCompressedFlag(root);

            Entry bodyEntry = root.hasEntry("BodyText") ? root.getEntry("BodyText") : null;
            if (!(bodyEntry instanceof DirectoryEntry body)) {
                throw new IllegalStateException("한글 파일에서 본문(BodyText)을 찾지 못했습니다.");
            }

            // Section0, Section1 ... 을 번호 순서대로 모은다.
            List<String> sectionNames = new ArrayList<>();
            for (Iterator<Entry> it = body.getEntries(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.getName().startsWith("Section")) sectionNames.add(e.getName());
            }
            if (sectionNames.isEmpty()) throw new IllegalStateException("한글 파일에 본문 구역이 없습니다.");
            sectionNames.sort((a, b) -> Integer.compare(sectionNo(a), sectionNo(b)));

            StringBuilder sb = new StringBuilder();
            for (String name : sectionNames) {
                byte[] raw = readStream((DocumentEntry) body.getEntry(name));
                byte[] data = compressed ? inflateRaw(raw) : raw;
                sb.append(parseSection(data));
            }
            return sb.toString();
        } catch (IllegalStateException | IllegalArgumentException e) {
            // POIFS 는 OLE 통이 아니면 여기서 걸린다 — 한글 97 이하 옛 형식이 그렇다.
            throw new IllegalStateException(
                    "이 한글 파일은 읽을 수 없습니다(" + e.getMessage() + "). "
                    + "한글에서 파일 → 다른 이름으로 저장 → '한글 문서 (*.hwp)' 또는 '워드 (*.docx)'로 다시 저장한 뒤 넣어 주세요.");
        }
    }

    /** 파일 머리(FileHeader)의 속성 플래그 0번 비트가 서면 본문이 압축돼 있다. */
    private static boolean readCompressedFlag(DirectoryEntry root) throws Exception {
        if (!root.hasEntry("FileHeader")) {
            throw new IllegalStateException("한글 파일 형식이 아닙니다(FileHeader 없음).");
        }
        byte[] header = readStream((DocumentEntry) root.getEntry("FileHeader"));
        if (header.length < 40) throw new IllegalStateException("한글 파일 머리말이 손상되었습니다.");
        String signature = new String(header, 0, 17, StandardCharsets.US_ASCII);
        if (!signature.startsWith("HWP Document File")) {
            throw new IllegalStateException("한글 5.0 형식이 아닙니다.");
        }
        int flags = ByteBuffer.wrap(header, 36, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return (flags & 0x01) != 0;
    }

    private static byte[] readStream(DocumentEntry entry) throws Exception {
        try (DocumentInputStream in = new DocumentInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** 한글은 zlib 머리말 없는 raw deflate 로 압축한다. */
    private static byte[] inflateRaw(byte[] data) throws Exception {
        Inflater inflater = new Inflater(true);
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, data.length * 4));
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                out.write(buf, 0, n);
            }
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    // 본문 구역은 기록(record)들이 줄줄이 이어진 형태다. 각 기록은 4바이트
    // 머리말로 시작하고, 거기에 종류·깊이·길이가 비트로 눌려 담겨 있다.
    private static final int HWPTAG_PARA_TEXT = 0x010 + 51;

    private static String parseSection(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos + 4 <= data.length) {
            int header = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            pos += 4;
            int tagId = header & 0x3FF;
            int size = (header >> 20) & 0xFFF;
            if (size == 0xFFF) {                     // 길이가 4095를 넘으면 뒤 4바이트에 진짜 길이가 온다
                if (pos + 4 > data.length) break;
                size = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                pos += 4;
            }
            if (size < 0 || pos + size > data.length) break;   // 손상된 파일이면 여기서 멈춘다
            if (tagId == HWPTAG_PARA_TEXT) {
                sb.append(paragraphText(data, pos, size)).append('\n');
            }
            pos += size;
        }
        return sb.toString();
    }

    /**
     * 문단 글자는 UTF-16 두 바이트씩 늘어서 있는데, 32보다 작은 값은 글자가
     * 아니라 표·그림·각주 같은 조판 표시다. 그중 일부는 뒤에 딸린 정보까지
     * 합쳐 여덟 칸을 차지하므로 그만큼 건너뛰어야 글자가 밀리지 않는다.
     */
    private static String paragraphText(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder();
        int i = off;
        int end = off + len;
        while (i + 2 <= end) {
            int ch = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
            i += 2;
            switch (ch) {
                // 한 칸짜리 표시. 줄바꿈·문단끝만 살리고 나머지는 버린다.
                case 0, 24, 25, 26, 27, 28, 29, 30, 31 -> { }
                case 10, 13 -> sb.append('\n');
                // 여덟 칸짜리 표시 (앞뒤 2 + 가운데 정보 12바이트)
                case 1, 2, 3, 11, 12, 14, 15, 16, 17, 18, 21, 22, 23,
                     4, 5, 6, 7, 8, 9, 19, 20 -> i += 14;
                default -> sb.append((char) ch);
            }
        }
        return sb.toString();
    }
}
