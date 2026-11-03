package com.agent.springaitaskagent.rag;

import com.agent.springaitaskagent.skill.DocumentParseSkill;
import com.agent.springaitaskagent.persistence.DatabaseDialect;
import com.agent.springaitaskagent.persistence.DatabaseSchema;
import com.agent.springaitaskagent.persistence.entity.KnowledgeDocumentEntity;
import com.agent.springaitaskagent.persistence.entity.KnowledgeSettingEntity;
import com.agent.springaitaskagent.persistence.mapper.KnowledgeDocumentMapper;
import com.agent.springaitaskagent.persistence.mapper.KnowledgeSettingMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.ai.document.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** Catalog is authoritative. A dirty marker plus content/file/model fingerprints guard the vector cache. */
@Service
public class KnowledgeService implements CommandLineRunner {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeService.class);
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeSettingMapper settings;
    private final ObjectMapper json;
    private final DocumentParseSkill parser;
    private final VectorStoreService vectors;
    private final Path vectorPath;
    private final DatabaseDialect dialect;
    private volatile boolean ready;
    private volatile boolean consistent = true;
    private volatile String lastError;
    @Value("${task-agent.rag.chunk-size:800}") private int chunkSize = 800;
    @Value("${task-agent.rag.chunk-overlap:120}") private int chunkOverlap = 120;

    public KnowledgeService(DatabaseSchema schema, KnowledgeDocumentMapper documents, KnowledgeSettingMapper settings,
                            ObjectMapper json, DocumentParseSkill parser, VectorStoreService vectors,
                            @Value("${task-agent.knowledge.vector-path:./data/knowledge-vectors.json}") String vectorPath) {
        this.documents = documents; this.settings = settings; this.json = json; this.parser = parser; this.vectors = vectors;
        this.vectorPath = Path.of(vectorPath);
        this.dialect = schema.dialect();
    }

    @Override
    public synchronized void run(String... args) {
        consistent = false; ready = false; vectors.setAvailable(false);
        try {
            documents.update(null, new UpdateWrapper<KnowledgeDocumentEntity>().eq("status", "INDEXING")
                    .set("status", "FAILED").set("error_message", "索引过程中服务中断，可点击重建知识索引恢复"));
            boolean firstSeed = !"true".equals(setting("seeded"));
            if (firstSeed) {
                // Persist before seeding, so failed initialization cannot add duplicate demo documents on every restart.
                putSetting("seeded", "true");
                if (list().isEmpty()) {
                    try (var input = new ClassPathResource("knowledge/sample-policy.md").getInputStream()) {
                        insert("sample-policy.md", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    rebuildInternal(true);
                    return;
                }
            }
            Manifest manifest = readManifest();
            if (manifest != null && !Objects.equals(manifest.embeddingIdentity(), embeddingIdentity())) {
                lastError = "向量模型、接入地址或分块配置已变化，请点击重建知识索引，避免混用不同模型的向量";
                return;
            }
            if (manifest != null && Objects.equals(manifest.catalogHash(), catalogHash())
                    && Files.isRegularFile(vectorPath) && Objects.equals(manifest.fileHash(), fileHash(vectorPath))) {
                try {
                    if (vectors.load(vectorPath) && expectedVectorIds().equals(vectors.getVectorIds())) {
                        publishHealthy();
                        return;
                    }
                } catch (RuntimeException invalidSnapshot) {
                    log.warn("Knowledge snapshot could not be loaded; rebuilding from catalog: {}", invalidSnapshot.getClass().getSimpleName());
                }
            }
            // A crash between SQL and file writes leaves a dirty/mismatched manifest. Rebuild only committed READY docs.
            rebuildInternal(false);
        } catch (Exception error) { unavailable("知识索引恢复失败，请检查模型权限、网络及磁盘后点击重建知识索引", error); }
    }

    public synchronized KnowledgeDocument upload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空文档");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("文档不能超过10MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("document.txt").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank() || name.length() > 180) throw new IllegalArgumentException("文件名为空或过长");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".pdf") || lower.endsWith(".docx")))
            throw new IllegalArgumentException("支持 TXT、Markdown、PDF、DOCX 文档");
        Path temp = null;
        try {
            temp = Files.createTempFile("zhixing-upload-", lower.substring(lower.lastIndexOf('.')));
            file.transferTo(temp);
            // Extract original text exactly once. Joining overlapping chunks would duplicate document contents on rebuild.
            return ingest(name, parser.extractText(temp.toString()));
        } catch (java.io.IOException error) { throw new IllegalArgumentException("读取上传文档失败"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { } }
    }

    public synchronized KnowledgeDocument ingest(String name, String content) {
        if (!consistent) throw new IllegalStateException("知识索引尚未恢复，请先点击重建知识索引");
        if (name == null || name.isBlank() || name.length() > 180) throw new IllegalArgumentException("文件名为空或过长");
        if (content == null || content.isBlank() || content.length() > 200_000)
            throw new IllegalArgumentException("文档文本为空或超过20万字符");
        if (list().size() >= 100) throw new IllegalArgumentException("本地演示最多保存100份文档，请先清理不需要的资料");
        putSetting("seeded", "true");
        markDirty();
        String id = insert(name, content);
        try {
            index(id, name, content);
            checkpoint();
        } catch (Exception failure) {
            // index() cleans partial vectors and records FAILED. Never serve uncertain vectors after a disk/catalog error.
            try { checkpoint(); }
            catch (Exception checkpointFailure) { unavailable("索引保存失败，请检查磁盘后重建知识索引", checkpointFailure); }
            if (consistent) lastError = "最近一次文档索引失败，可在知识空间重建索引重试";
            log.warn("Knowledge ingest failed: {}", failure.getClass().getSimpleName());
        }
        return require(id);
    }

    private String insert(String name, String content) {
        String id = "doc-" + UUID.randomUUID();
        KnowledgeDocumentEntity row = new KnowledgeDocumentEntity();
        row.setId(id); row.setName(name); row.setContent(content); row.setCreatedAt(LocalDateTime.now().toString());
        row.setChunkCount(0); row.setStatus("INDEXING"); row.setVectorIds("[]");
        documents.insert(row);
        return id;
    }

    private void index(String id, String name, String text) {
        List<String> ids = List.of();
        try {
            List<Document> chunks = parser.parse(null, text);
            if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("文档没有可索引内容");
            chunks.forEach(document -> { document.getMetadata().put("source", name); document.getMetadata().put("documentId", id); });
            ids = chunks.stream().map(Document::getId).toList();
            vectors.addDocuments(chunks);
            documents.update(null, new UpdateWrapper<KnowledgeDocumentEntity>().eq("id", id)
                    .set("chunk_count", chunks.size()).set("status", "READY").set("error_message", null)
                    .set("vector_ids", json.writeValueAsString(ids)));
        } catch (Exception error) {
            boolean cleaned = true;
            try { vectors.delete(ids); } catch (RuntimeException cleanupFailure) { cleaned = false; }
            documents.update(null, new UpdateWrapper<KnowledgeDocumentEntity>().eq("id", id)
                    .set("status", "FAILED").set("chunk_count", 0).set("vector_ids", "[]")
                    .set("error_message", "向量化失败，请检查模型密钥、额度与网络后点击重建知识索引"));
            if (!cleaned) throw new IllegalStateException("部分索引清理失败，必须重建知识索引", error);
            throw new IllegalStateException("知识文档索引失败", error);
        }
    }

    public synchronized void delete(String id) {
        require(id);
        try {
            List<String> ids = json.readValue(documents.selectById(id).getVectorIds(), new TypeReference<>() { });
            markDirty();
            // SQL is authoritative. If the process stops after this delete, startup rebuild excludes this document.
            documents.deleteById(id);
            vectors.delete(ids);
            checkpoint();
        } catch (Exception error) {
            unavailable("知识文档删除后的索引尚未同步，请点击重建知识索引", error);
            throw new IllegalStateException("删除操作未能完成索引同步，请重建知识索引", error);
        }
    }

    /** Explicit recovery also retries FAILED built-in/uploads after a model/key/network issue is fixed. */
    public synchronized List<KnowledgeDocument> rebuild() {
        putSetting("seeded", "true");
        try { rebuildInternal(true); }
        catch (Exception error) {
            unavailable("知识索引重建失败，请检查模型权限、网络及磁盘后重试", error);
            throw new IllegalStateException(lastError, error);
        }
        return list();
    }

    private void rebuildInternal(boolean includeFailed) throws Exception {
        markDirty();
        vectors.clear();
        List<KnowledgeDocument> documents = list();
        for (KnowledgeDocument document : documents) {
            if (document.status().equals("READY") || includeFailed) {
                try { index(document.id(), document.name(), content(document.id())); }
                catch (RuntimeException failure) {
                    // The failed document remains visible; a later explicit rebuild can retry it.
                    log.warn("Knowledge document could not be rebuilt: {}", document.id());
                }
            }
        }
        checkpoint();
    }

    private void markDirty() {
        putSetting("manifest", "DIRTY");
        consistent = false; ready = false; vectors.setAvailable(false);
    }
    private void checkpoint() throws Exception {
        // Exact ID equality is an additional guard against orphaned partial/deleted document chunks.
        if (!expectedVectorIds().equals(vectors.getVectorIds())) throw new IllegalStateException("向量目录与文档目录不一致，必须重建索引");
        vectors.save(vectorPath);
        Manifest manifest = new Manifest(1, catalogHash(), fileHash(vectorPath), embeddingIdentity());
        putSetting("manifest", json.writeValueAsString(manifest));
        publishHealthy();
    }
    private void publishHealthy() {
        consistent = true; vectors.setAvailable(true); ready = countReady() > 0;
        lastError = list().stream().anyMatch(document -> document.status().equals("FAILED"))
                ? "部分文档索引失败，可点击重建知识索引重试" : null;
    }
    private void unavailable(String message, Exception error) {
        consistent = false; ready = false; vectors.setAvailable(false); lastError = message;
        log.warn("{} ({})", message, error.getClass().getSimpleName());
    }
    private Manifest readManifest() {
        try {
            String value = setting("manifest");
            if (value == null || value.equals("DIRTY")) return null;
            Manifest manifest = json.readValue(value, Manifest.class);
            return manifest.version() == 1 ? manifest : null;
        } catch (Exception malformed) { return null; }
    }
    private String embeddingIdentity() {
        return String.valueOf(vectors.getEmbeddingIdentity()) + "|chunk=" + chunkSize + "|overlap=" + chunkOverlap;
    }
    private Set<String> expectedVectorIds() throws Exception {
        Set<String> ids = new HashSet<>();
        for (KnowledgeDocumentEntity row : documents.selectList(new QueryWrapper<KnowledgeDocumentEntity>()
                .select("vector_ids").eq("status", "READY")))
            ids.addAll(json.readValue(row.getVectorIds(), new TypeReference<List<String>>() { }));
        return ids;
    }
    private String catalogHash() throws Exception {
        List<List<String>> catalog = documents.selectList(new QueryWrapper<KnowledgeDocumentEntity>().orderByAsc("id"))
                .stream().map(row -> Arrays.asList(row.getId(), row.getName(), row.getContent(), row.getCreatedAt(),
                        row.getChunkCount() == null ? null : row.getChunkCount().toString(), row.getStatus(), row.getVectorIds())).toList();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(catalog)));
    }
    private String fileHash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private String setting(String key) {
        KnowledgeSettingEntity row = settings.selectById(key);
        return row == null ? null : row.getSettingValue();
    }
    private void putSetting(String key, String value) {
        KnowledgeSettingEntity row = new KnowledgeSettingEntity();
        row.setSettingKey(key); row.setSettingValue(value); settings.upsert(row);
    }
    public List<KnowledgeDocument> list() {
        return documents.selectList(new QueryWrapper<KnowledgeDocumentEntity>()
                .select("id", "name", "chunk_count", "created_at", "status", "error_message").orderByDesc("created_at"))
                .stream().map(row -> new KnowledgeDocument(row.getId(), row.getName(), row.getChunkCount(),
                        row.getCreatedAt(), row.getStatus(), row.getErrorMessage())).toList();
    }
    public KnowledgeDocument require(String id) {
        return list().stream().filter(document -> document.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("知识文档不存在"));
    }
    public void validateSelection(List<String> ids) {
        if (ids == null) return;
        for (String id : ids) if (!require(id).status().equals("READY")) throw new IllegalArgumentException("所选文档尚未就绪");
    }
    private String content(String id) { return documents.selectById(id).getContent(); }
    public int countReady() { return Math.toIntExact(documents.selectCount(new QueryWrapper<KnowledgeDocumentEntity>().eq("status", "READY"))); }
    public boolean isReady() { return ready; }
    public String storageName() { return dialect.displayName(); }
    public String getLastError() { return lastError; }
    public record KnowledgeDocument(String id, String name, int chunkCount, String createdAt, String status, String errorMessage) { }
    private record Manifest(int version, String catalogHash, String fileHash, String embeddingIdentity) { }
}
