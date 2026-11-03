package com.agent.springaitaskagent.rag;

import com.agent.springaitaskagent.skill.DocumentParseSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;

import java.util.List;

/**
 * 知识库模块：应用启动时把内置知识文档解析、分块并写入向量库，保证开箱即可检索。
 * <p>
 * 默认加载 classpath:knowledge 目录（内置差旅 / 采购制度样例）。
 * </p>
 */
// 索引初始化由 KnowledgeService 统一管理，避免每次启动重复向量化。
public class DocumentLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentLoader.class);

    private final DocumentParseSkill documentParseSkill;
    private final VectorStoreService vectorStoreService;

    @Value("${task-agent.document.path:classpath:knowledge}")
    private String documentPath;

    public DocumentLoader(DocumentParseSkill documentParseSkill, VectorStoreService vectorStoreService) {
        this.documentParseSkill = documentParseSkill;
        this.vectorStoreService = vectorStoreService;
    }

    @Override
    public void run(String... args) {
        try {
            List<Document> docs = documentParseSkill.parse(documentPath, null);
            if (docs.isEmpty()) {
                log.warn("知识库目录未解析到文档: {}", documentPath);
                return;
            }
            vectorStoreService.addDocuments(docs);
            log.info("知识库加载完成，分片数={}，来源目录={}", docs.size(), documentPath);
        } catch (Exception e) {
            // 加载失败不阻塞应用启动，仅记录日志
            log.error("知识库加载失败（应用继续启动）: {}", e.getMessage());
        }
    }
}
