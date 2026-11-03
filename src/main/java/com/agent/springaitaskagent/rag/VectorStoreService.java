package com.agent.springaitaskagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.agent.springaitaskagent.harness.exception.TaskExecuteException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 向量知识库服务：分片写入与相似度检索，默认基于 SimpleVectorStore（内存向量库）。
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final Map<String, Document> indexedDocuments = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private volatile boolean available = true;
    @Value("${spring.ai.openai.embedding.options.model:text-embedding-v2}")
    private String embeddingModelName = "text-embedding-v2";
    @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String embeddingEndpoint = "https://dashscope.aliyuncs.com/compatible-mode";

    @Value("${task-agent.rag.top-k:4}")
    private int topK;

    @Value("${task-agent.rag.similarity-threshold:0.3}")
    private double similarityThreshold;

    public VectorStoreService(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    /** 写入文档分片（自动向量化）。 */
    public synchronized void addDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        // Embed into an unpublished candidate. A partially failed add never leaks orphaned chunks into live queries.
        ScopedVectorStore candidate = new ScopedVectorStore(embeddingModel, indexedDocuments);
        candidate.add(documents);
        this.vectorStore = candidate;
        documents.forEach(document -> indexedDocuments.put(document.getId(), document));
        log.info("向量库写入分片 {}", documents.size());
    }

    /** 相似度检索，返回 Top-K 命中文档。 */
    public synchronized List<Document> search(String query) {
        requireAvailable();
        SearchRequest request = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(similarityThreshold);
        return vectorStore.similaritySearch(request);
    }

    public synchronized List<Document> searchSelected(String query, List<String> documentIds) {
        requireAvailable();
        if (documentIds == null || documentIds.isEmpty()) return search(query);
        if (documentIds.stream().anyMatch(id -> !id.matches("doc-[a-zA-Z0-9-]+")))
            throw new IllegalArgumentException("文档编号格式无效");
        // M4 SimpleVectorStore rejects metadata filters. Reuse the selected stored embeddings instead.
        Set<String> selected = new HashSet<>(documentIds);
        Map<String, Document> scopedDocuments = new LinkedHashMap<>();
        indexedDocuments.forEach((id, document) -> {
            if (selected.contains(String.valueOf(document.getMetadata().get("documentId")))) scopedDocuments.put(id, document);
        });
        if (scopedDocuments.isEmpty()) return List.of();
        ScopedVectorStore scoped = new ScopedVectorStore(embeddingModel, scopedDocuments);
        return scoped.similaritySearch(SearchRequest.query(query).withTopK(topK).withSimilarityThreshold(similarityThreshold));
    }

    /** 补充文本仅对当前任务生效，不污染公共知识库。 */
    public List<Document> search(String query, List<Document> taskDocuments) {
        if (taskDocuments == null || taskDocuments.isEmpty()) return search(query);
        SimpleVectorStore scoped = new SimpleVectorStore(embeddingModel);
        scoped.add(taskDocuments);
        return scoped.similaritySearch(SearchRequest.query(query).withTopK(topK)
                .withSimilarityThreshold(similarityThreshold));
    }

    public synchronized void delete(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            ids.forEach(indexedDocuments::remove);
            vectorStore = new ScopedVectorStore(embeddingModel, indexedDocuments);
        }
    }

    public synchronized void save(java.nio.file.Path path) {
        try {
            java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
            java.nio.file.Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            ((SimpleVectorStore) vectorStore).save(temp.toFile());
            try {
                java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("保存知识索引失败", e); }
    }

    public synchronized boolean load(java.nio.file.Path path) {
        if (!java.nio.file.Files.isRegularFile(path)) return false;
        try {
            Map<String, Document> documents = json.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, Document>>() { });
            if (documents == null || documents.values().stream().anyMatch(document -> document == null
                    || document.getEmbedding() == null || document.getEmbedding().length == 0)) {
                throw new IllegalStateException("向量快照缺少有效向量，请重建索引");
            }
            // Publish only after the complete file has parsed successfully.
            this.vectorStore = new ScopedVectorStore(embeddingModel, documents);
            indexedDocuments.clear(); indexedDocuments.putAll(documents);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("向量快照损坏，请重建索引", invalid); }
        return true;
    }

    public synchronized void clear() {
        vectorStore = new SimpleVectorStore(embeddingModel);
        indexedDocuments.clear();
    }
    public void setAvailable(boolean available) { this.available = available; }
    public synchronized Set<String> getVectorIds() { return Set.copyOf(indexedDocuments.keySet()); }
    public String getEmbeddingIdentity() { return "spring-ai-M4|" + embeddingModelName + "|" + embeddingEndpoint; }
    private void requireAvailable() {
        if (!available) throw new TaskExecuteException("KNOWLEDGE_UNAVAILABLE", "知识索引尚未就绪或正在重建，请查看系统状态并重建知识库");
    }
    private static final class ScopedVectorStore extends SimpleVectorStore {
        ScopedVectorStore(EmbeddingModel model, Map<String, Document> documents) {
            super(model);
            this.store = new java.util.concurrent.ConcurrentHashMap<>(documents);
        }
    }
}
