package com.agent.springaitaskagent.rag;

import com.agent.springaitaskagent.skill.DocumentParseSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2 catalog and filesystem, fake embeddings. No model endpoint is contacted. */
class KnowledgeServiceTest {
    @TempDir Path temporary;

    @Test void rejectsUnsupportedEmptyOversizedAndOverlongFilenameBeforeParsing() {
        Fixture fixture = fixture("validation");
        assertThrows(IllegalArgumentException.class, () -> fixture.service.upload(
                new MockMultipartFile("file", "payload.exe", "application/octet-stream", new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.upload(
                new MockMultipartFile("file", "empty.md", "text/markdown", new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.upload(
                new MockMultipartFile("file", "x".repeat(181) + ".md", "text/markdown", new byte[]{1})));
        MultipartFile huge = mock(MultipartFile.class);
        when(huge.isEmpty()).thenReturn(false);
        when(huge.getSize()).thenReturn(10L * 1024 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, () -> fixture.service.upload(huge));
        verifyNoInteractions(fixture.parser);
        verify(fixture.vectors, never()).addDocuments(anyList());
        assertTrue(fixture.service.list().isEmpty());
    }

    @Test void stripsClientPathAndUsesOnlyServerGeneratedTemporaryFile() {
        Fixture fixture = fixture("filename");
        when(fixture.parser.extractText(anyString())).thenAnswer(invocation -> {
            Path serverPath = Path.of(invocation.getArgument(0, String.class));
            assertTrue(serverPath.getFileName().toString().startsWith("zhixing-upload-"));
            assertTrue(Files.isRegularFile(serverPath));
            assertFalse(serverPath.toString().contains("private-folder"));
            return "北区测试办公室访客证有效期为7天。";
        });
        KnowledgeService.KnowledgeDocument document = fixture.service.upload(new MockMultipartFile(
                "file", "C:\\private-folder\\..\\demo.md", "text/markdown", "fictional".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("demo.md", document.name());
        assertEquals("READY", document.status());
        verify(fixture.vectors).addDocuments(argThat(chunks -> chunks.stream().allMatch(chunk ->
                "demo.md".equals(chunk.getMetadata().get("source")) && document.id().equals(chunk.getMetadata().get("documentId")))));
    }

    @Test void failedEmbeddingLeavesExplicitFailedCatalogEntryWithoutCredentialLeak() {
        Fixture fixture = fixture("failure");
        doThrow(new IllegalStateException("provider error sk-fake-never-expose"))
                .when(fixture.vectors).addDocuments(anyList());
        KnowledgeService.KnowledgeDocument document = fixture.service.ingest("fictional.md", "Fictional failure sample");
        assertEquals("FAILED", document.status());
        assertFalse(fixture.service.isReady());
        assertEquals(0, fixture.service.countReady());
        assertNotNull(document.errorMessage());
        assertFalse(document.errorMessage().contains("sk-fake-never-expose"));
        assertFalse(String.valueOf(fixture.service.getLastError()).contains("sk-fake-never-expose"));
        assertEquals(1, fixture.service.list().size());
    }

    @Test void deletingDocumentRemovesItsPersistedVectorIdentifiersAndCatalogRow() throws Exception {
        Fixture fixture = fixture("delete");
        KnowledgeService.KnowledgeDocument document = fixture.service.ingest("fictional.md", "Fictional policy");
        String storedIds = fixture.jdbc.queryForObject("SELECT vector_ids FROM knowledge_documents WHERE id=?", String.class, document.id());
        List<String> ids = new ObjectMapper().readValue(storedIds, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertFalse(ids.isEmpty());
        fixture.service.delete(document.id());
        verify(fixture.vectors).delete(ids);
        assertTrue(fixture.service.list().isEmpty());
        assertFalse(fixture.service.isReady());
        assertThrows(IllegalArgumentException.class, () -> fixture.service.require(document.id()));
    }

    @Test void invalidOrFailedSelectionNeverBecomesUsableKnowledge() {
        Fixture fixture = fixture("selection");
        assertThrows(IllegalArgumentException.class, () -> fixture.service.validateSelection(List.of("doc-missing")));
        doThrow(new IllegalStateException("embedding unavailable")).when(fixture.vectors).addDocuments(anyList());
        KnowledgeService.KnowledgeDocument failed = fixture.service.ingest("failed.md", "Fictional data");
        assertThrows(IllegalArgumentException.class, () -> fixture.service.validateSelection(List.of(failed.id())));
    }

    @Test void restartLoadsVerifiedSnapshotWithoutReembeddingPersistedCatalog() {
        Fixture first = fixture("verified-restart");
        KnowledgeService.KnowledgeDocument stored = first.service.ingest("saved.md", "Fictional durable content");
        DocumentParseSkill restartedParser = parser();
        VectorStoreService restartedVectors = vectorFake("test-embedding-v1");
        KnowledgeService restarted = service(first.jdbc, restartedParser, restartedVectors, first.vectorPath);
        restarted.run();
        assertTrue(restarted.isReady());
        assertEquals(stored.id(), restarted.list().get(0).id());
        verify(restartedVectors).load(first.vectorPath);
        verify(restartedVectors, never()).addDocuments(anyList());
        verifyNoInteractions(restartedParser);
    }

    @Test void changedSnapshotCannotBeTrustedAndIsRebuiltFromAuthoritativeCatalog() throws Exception {
        Fixture first = fixture("tampered-snapshot");
        KnowledgeService.KnowledgeDocument stored = first.service.ingest("saved.md", "Original fictional content");
        Files.writeString(first.vectorPath, "orphaned-vector-not-in-catalog");
        DocumentParseSkill restartedParser = parser();
        VectorStoreService restartedVectors = vectorFake("test-embedding-v1");
        KnowledgeService restarted = service(first.jdbc, restartedParser, restartedVectors, first.vectorPath);
        restarted.run();
        assertTrue(restarted.isReady());
        assertEquals(stored.id(), restarted.list().get(0).id());
        verify(restartedVectors, never()).load(any());
        verify(restartedVectors).clear();
        verify(restartedVectors).addDocuments(anyList());
        verify(restartedParser).parse(null, "Original fictional content");
    }

    @Test void changedEmbeddingIdentityRequiresExplicitRebuild() {
        Fixture first = fixture("model-change");
        first.service.ingest("saved.md", "Fictional content");
        DocumentParseSkill restartedParser = parser();
        VectorStoreService restartedVectors = vectorFake("test-embedding-v2");
        KnowledgeService restarted = service(first.jdbc, restartedParser, restartedVectors, first.vectorPath);
        restarted.run();
        assertFalse(restarted.isReady());
        assertTrue(restarted.getLastError().contains("变化"));
        verify(restartedVectors, never()).load(any());
        verify(restartedVectors, never()).addDocuments(anyList());
        verifyNoInteractions(restartedParser);
        restarted.rebuild();
        assertTrue(restarted.isReady());
        verify(restartedVectors).addDocuments(anyList());
    }

    @Test void deletingAllDocumentsDoesNotReseedDemoOnRestart() {
        Fixture first = fixture("delete-all");
        first.service.run();
        assertEquals(1, first.service.list().size());
        first.service.delete(first.service.list().get(0).id());
        DocumentParseSkill restartedParser = parser();
        VectorStoreService restartedVectors = vectorFake("test-embedding-v1");
        KnowledgeService restarted = service(first.jdbc, restartedParser, restartedVectors, first.vectorPath);
        restarted.run();
        assertTrue(restarted.list().isEmpty());
        assertFalse(restarted.isReady());
        verifyNoInteractions(restartedParser);
        verify(restartedVectors, never()).addDocuments(anyList());
    }

    @Test void explicitRebuildRecoversFailedDocumentWithoutDuplicatingCatalogEntry() {
        Fixture first = fixture("recover-failed");
        doThrow(new IllegalStateException("temporary provider outage")).when(first.vectors).addDocuments(anyList());
        KnowledgeService.KnowledgeDocument failed = first.service.ingest("recover.md", "Fictional retained text");
        assertEquals("FAILED", failed.status());
        DocumentParseSkill recoveredParser = parser();
        VectorStoreService recoveredVectors = vectorFake("test-embedding-v1");
        KnowledgeService recovered = service(first.jdbc, recoveredParser, recoveredVectors, first.vectorPath);
        recovered.run();
        assertFalse(recovered.isReady());
        verify(recoveredVectors, never()).addDocuments(anyList());
        recovered.rebuild();
        assertTrue(recovered.isReady());
        assertEquals(1, recovered.list().size());
        assertEquals(failed.id(), recovered.list().get(0).id());
        assertEquals("READY", recovered.list().get(0).status());
    }

    private Fixture fixture(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        DocumentParseSkill parser = parser();
        VectorStoreService vectors = vectorFake("test-embedding-v1");
        Path path = temporary.resolve(name + ".json");
        KnowledgeService service = service(jdbc, parser, vectors, path);
        return new Fixture(jdbc, parser, vectors, service, path);
    }

    private DocumentParseSkill parser() {
        DocumentParseSkill parser = mock(DocumentParseSkill.class);
        when(parser.parse(isNull(), anyString())).thenAnswer(invocation -> List.of(new Document(invocation.getArgument(1, String.class))));
        return parser;
    }

    private VectorStoreService vectorFake(String identity) {
        VectorStoreService vectors = mock(VectorStoreService.class);
        Set<String> embeddedIds = new HashSet<>();
        when(vectors.getEmbeddingIdentity()).thenReturn(identity);
        when(vectors.getVectorIds()).thenAnswer(invocation -> new HashSet<>(embeddedIds));
        doAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            documents.forEach(document -> embeddedIds.add(document.getId()));
            return null;
        }).when(vectors).addDocuments(anyList());
        doAnswer(invocation -> { List<String> ids = invocation.getArgument(0); embeddedIds.removeAll(ids); return null; })
                .when(vectors).delete(anyList());
        doAnswer(invocation -> { embeddedIds.clear(); return null; }).when(vectors).clear();
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(0, Path.class);
            Files.writeString(destination, String.join("\n", new java.util.TreeSet<>(embeddedIds)));
            return null;
        }).when(vectors).save(any(Path.class));
        when(vectors.load(any(Path.class))).thenAnswer(invocation -> {
            Path source = invocation.getArgument(0, Path.class);
            embeddedIds.clear();
            Files.readAllLines(source).stream().filter(line -> !line.isBlank()).forEach(embeddedIds::add);
            return true;
        });
        return vectors;
    }

    private KnowledgeService service(JdbcTemplate jdbc, DocumentParseSkill parser, VectorStoreService vectors, Path path) {
        return com.agent.springaitaskagent.persistence.PersistenceTestSupport.knowledge(jdbc, new ObjectMapper().findAndRegisterModules(), parser, vectors, path.toString());
    }

    private record Fixture(JdbcTemplate jdbc, DocumentParseSkill parser, VectorStoreService vectors,
                           KnowledgeService service, Path vectorPath) { }
}
