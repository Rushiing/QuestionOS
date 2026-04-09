package com.questionos.backend.service;

import com.questionos.backend.api.dto.BackgroundDtos;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class BackgroundTextExtractService {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CHARS = 28_000;

    public BackgroundDtos.ExtractResponse extract(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("文件请小于 2MB");
        }
        String ext = extension(filename);
        final String raw;
        try {
            raw = switch (ext) {
                case "txt", "md", "markdown" -> readUtf8Text(bytes);
                case "docx" -> extractDocx(bytes);
                case "doc" -> extractDoc(bytes);
                default -> throw new IllegalArgumentException("仅支持 .txt / .md / .doc / .docx");
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文档：" + e.getMessage());
        }
        String trimmed = raw == null ? "" : raw.strip();
        boolean truncated = trimmed.length() > MAX_CHARS;
        String text =
                truncated ? trimmed.substring(0, MAX_CHARS) + "\n\n…（已截断，最长 " + MAX_CHARS + " 字）" : trimmed;
        return new BackgroundDtos.ExtractResponse(text, truncated);
    }

    private static String extension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int i = filename.lastIndexOf('.');
        return i >= 0 ? filename.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String readUtf8Text(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        return s;
    }

    private static String extractDocx(byte[] bytes) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                XWPFDocument doc = new XWPFDocument(in);
                XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return ex.getText();
        }
    }

    private static String extractDoc(byte[] bytes) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                HWPFDocument doc = new HWPFDocument(in);
                WordExtractor ex = new WordExtractor(doc)) {
            return ex.getText();
        }
    }
}
