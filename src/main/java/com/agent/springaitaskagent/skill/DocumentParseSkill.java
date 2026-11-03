package com.agent.springaitaskagent.skill;

import com.agent.springaitaskagent.harness.exception.TaskExecuteException;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill-1 文档解析：读取文档 / 文本并切分为向量化分片（chunk）。
 * <p>
 * 支持 classpath 目录、本地目录或单文件；txt/md/csv/json 按 UTF-8 直读，其余格式走 Apache Tika。
 * </p>
 */
@Component
public class DocumentParseSkill {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseSkill.class);
    private static final Tika TIKA = new Tika();

    @Value("${task-agent.document.path:classpath:knowledge}")
    private String defaultDocumentPath;

    @Value("${task-agent.rag.chunk-size:800}")
    private int chunkSize;

    @Value("${task-agent.rag.chunk-overlap:120}")
    private int chunkOverlap;

    /**
     * 解析文档并分块。
     *
     * @param documentPath 文档路径（文件 / 目录 / classpath: 前缀），可空
     * @param textContent  用户直接粘贴的文本，优先级最高，可空
     */
    public List<Document> parse(String documentPath, String textContent) {
        List<Document> chunks = new ArrayList<>();
        if (StringUtils.hasText(textContent)) {
            if (textContent.length() > 200_000) throw new IllegalArgumentException("文档文本不能超过20万字符");
            chunks.addAll(split(textContent, "user-input"));
        } else {
            String path = StringUtils.hasText(documentPath) ? documentPath : defaultDocumentPath;
            for (Resource resource : resolveResources(path)) {
                chunks.addAll(split(readResource(resource), resource.getFilename()));
            }
        }
        if (chunks.isEmpty()) {
            throw new TaskExecuteException("DOC_PARSE_EMPTY", "未解析到任何文档内容，请检查文档路径");
        }
        log.info("文档解析完成 chunks={}", chunks.size());
        return chunks;
    }

    /** Extract once without chunk overlap; knowledge catalog keeps this text for later rebuilds. */
    public String extractText(String documentPath) {
        Path path = Paths.get(documentPath);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("请选择有效文档文件");
        String text = readResource(new FileSystemResource(path));
        if (!StringUtils.hasText(text) || text.length() > 200_000) throw new IllegalArgumentException("文档文本为空或超过20万字符");
        return text;
    }

    /** 将路径解析为资源列表（classpath 目录 / 本地目录 / 单文件）。 */
    private List<Resource> resolveResources(String path) {
        List<Resource> resources = new ArrayList<>();
        try {
            if (path.startsWith("classpath:")) {
                PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                String pattern = path.endsWith("*") ? path : path + "/*";
                for (Resource r : resolver.getResources(pattern)) {
                    if (r.exists() && r.getFilename() != null) {
                        resources.add(r);
                    }
                }
            } else {
                Path p = Paths.get(path);
                if (!Files.exists(p)) {
                    throw new TaskExecuteException("DOC_PATH_NOT_FOUND", "文档路径不存在：" + path);
                }
                if (Files.isDirectory(p)) {
                    try (var stream = Files.list(p)) {
                        resources.addAll(stream
                                .filter(Files::isRegularFile)
                                .map(Path::toFile)
                                .map(FileSystemResource::new)
                                .collect(Collectors.toList()));
                    }
                } else {
                    resources.add(new FileSystemResource(p.toFile()));
                }
            }
        } catch (TaskExecuteException e) {
            throw e;
        } catch (IOException e) {
            throw new TaskExecuteException("DOC_RESOLVE_FAILED", "解析文档路径失败：" + e.getMessage(), e);
        }
        return resources;
    }

    /** 读取单个资源为纯文本：纯文本直读 UTF-8，其余用 Tika 解析。 */
    private String readResource(Resource resource) {
        try {
            String filename = resource.getFilename() == null ? "unknown" : resource.getFilename();
            byte[] bytes;
            try (var input = resource.getInputStream()) {
                bytes = input.readNBytes(10 * 1024 * 1024 + 1);
            }
            if (bytes.length > 10 * 1024 * 1024) throw new IllegalArgumentException("文档不能超过10MB");
            if (isPlainText(filename)) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            Tika parser = new Tika();
            parser.setMaxStringLength(200_000);
            String text = parser.parseToString(new ByteArrayInputStream(bytes));
            if (!StringUtils.hasText(text)) {
                throw new TaskExecuteException("DOC_PARSE_EMPTY", "文档解析结果为空：" + filename);
            }
            return text;
        } catch (TaskExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecuteException("DOC_PARSE_FAILED", "文档解析失败：" + e.getMessage(), e);
        }
    }

    private boolean isPlainText(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".csv") || lower.endsWith(".json");
    }

    /** 按 chunk-size / chunk-overlap 切分文本为 Document 分片。 */
    private List<Document> split(String raw, String source) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        int size = Math.max(200, chunkSize);
        int overlap = Math.max(0, Math.min(chunkOverlap, size / 2));
        List<Document> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < raw.length()) {
            int end = Math.min(raw.length(), start + size);
            String piece = raw.substring(start, end).strip();
            if (StringUtils.hasText(piece)) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", source);
                metadata.put("chunkIndex", index);
                chunks.add(new Document(piece, metadata));
                index++;
            }
            if (end >= raw.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }
}
