package com.agent.springaitaskagent.skill;

import com.agent.springaitaskagent.rag.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Skill-2 信息检索：基于用户问题在向量知识库中做相似度检索，拼装为可供大模型引用的上下文。
 */
@Component
public class InformationRetrieveSkill {

    private static final Logger log = LoggerFactory.getLogger(InformationRetrieveSkill.class);

    private final VectorStoreService vectorStoreService;

    public InformationRetrieveSkill(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    public String retrieve(String question) {
        return format(question, null);
    }

    public String retrieve(String question, List<Document> taskDocuments) {
        return format(question, taskDocuments);
    }

    public String retrieveSelected(String question, List<String> documentIds) {
        if (!StringUtils.hasText(question)) return "";
        return render(vectorStoreService.searchSelected(question, documentIds));
    }

    private String format(String question, List<Document> taskDocuments) {
        if (!StringUtils.hasText(question)) {
            return "";
        }
        List<Document> hits = taskDocuments == null || taskDocuments.isEmpty()
                ? vectorStoreService.search(question) : vectorStoreService.search(question, taskDocuments);
        return render(hits);
    }

    private String render(List<Document> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        return hits.stream()
                .map(doc -> "- 来源[" + doc.getMetadata().getOrDefault("source", "unknown")
                        + "]: " + doc.getContent())
                .collect(Collectors.joining("\n"));
    }
}
