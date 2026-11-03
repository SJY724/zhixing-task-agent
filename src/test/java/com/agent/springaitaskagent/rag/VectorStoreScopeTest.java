package com.agent.springaitaskagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the installed Spring AI M4 store; metadata filtering cannot be assumed supported. */
class VectorStoreScopeTest {
    @TempDir Path temporary;
    @Test void selectedScopeReturnsOnlySelectedDocumentWithoutReembeddingStoredDocuments() {
        CountingEmbedding embeddings = new CountingEmbedding();
        VectorStoreService service = service(embeddings);
        service.addDocuments(List.of(
                new Document("First fictional policy", Map.of("documentId", "doc-first", "source", "first.md")),
                new Document("Second fictional policy", Map.of("documentId", "doc-second", "source", "second.md"))));
        assertEquals(2, embeddings.documentCalls.get());
        List<Document> hits = service.searchSelected("policy", List.of("doc-second"));
        assertEquals(1, hits.size());
        assertEquals("doc-second", hits.get(0).getMetadata().get("documentId"));
        assertEquals(2, embeddings.documentCalls.get(), "Selecting indexed documents must reuse stored embeddings");
        assertTrue(embeddings.queryCalls.get() >= 1);
    }

    @Test void supplementScopeDoesNotPollutePublicKnowledge() {
        CountingEmbedding embeddings = new CountingEmbedding();
        VectorStoreService service = service(embeddings);
        service.addDocuments(List.of(new Document("Public policy", Map.of("source", "public.md", "documentId", "doc-public"))));
        List<Document> privateHits = service.search("policy", List.of(new Document("Task-only evidence", Map.of("source", "task-only"))));
        assertEquals(1, privateHits.size());
        assertEquals("task-only", privateHits.get(0).getMetadata().get("source"));
        List<Document> publicHits = service.search("policy");
        assertEquals(1, publicHits.size());
        assertEquals("public.md", publicHits.get(0).getMetadata().get("source"));
    }

    @Test void partiallyFailedBatchCannotPublishOrphanedVectors() {
        CountingEmbedding embeddings = new CountingEmbedding();
        VectorStoreService service = service(embeddings);
        Document original = new Document("Committed public policy", Map.of("documentId", "doc-original"));
        service.addDocuments(List.of(original));
        assertThrows(IllegalStateException.class, () -> service.addDocuments(List.of(
                new Document("First fragment in failed batch", Map.of("documentId", "doc-failed")),
                new Document("FAIL embedding", Map.of("documentId", "doc-failed")))));
        assertEquals(java.util.Set.of(original.getId()), service.getVectorIds());
        assertEquals(1, service.search("policy").size());
        assertTrue(service.searchSelected("policy", List.of("doc-failed")).isEmpty());
    }

    @Test void realSnapshotRoundTripRetainsEmbeddingsAndSelectedScope() {
        CountingEmbedding embeddings = new CountingEmbedding();
        VectorStoreService original = service(embeddings);
        Document document = new Document("Durable fictional policy", Map.of("documentId", "doc-saved", "source", "saved.md"));
        original.addDocuments(List.of(document));
        Path snapshot = temporary.resolve("vectors.json");
        original.save(snapshot);
        int callsBeforeRestart = embeddings.documentCalls.get();
        VectorStoreService restarted = service(embeddings);
        assertTrue(restarted.load(snapshot));
        assertEquals(java.util.Set.of(document.getId()), restarted.getVectorIds());
        List<Document> hits = restarted.searchSelected("policy", List.of("doc-saved"));
        assertEquals(1, hits.size());
        assertEquals("saved.md", hits.get(0).getMetadata().get("source"));
        assertEquals(callsBeforeRestart, embeddings.documentCalls.get(), "Loading snapshots must not embed documents again");
    }

    private VectorStoreService service(CountingEmbedding embeddings) {
        VectorStoreService service = new VectorStoreService(new SimpleVectorStore(embeddings), embeddings);
        ReflectionTestUtils.setField(service, "topK", 4);
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.3);
        return service;
    }

    private static final class CountingEmbedding implements EmbeddingModel {
        final AtomicInteger documentCalls = new AtomicInteger();
        final AtomicInteger queryCalls = new AtomicInteger();
        @Override public float[] embed(Document document) {
            documentCalls.incrementAndGet();
            if (document.getContent().startsWith("FAIL")) throw new IllegalStateException("fake embedding failure");
            return new float[]{1, 0, 0};
        }
        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            queryCalls.incrementAndGet();
            List<Embedding> rows = new ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++)
                rows.add(new Embedding(new float[]{1, 0, 0}, index));
            return new EmbeddingResponse(rows);
        }
    }
}
