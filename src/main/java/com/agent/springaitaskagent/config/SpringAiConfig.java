package com.agent.springaitaskagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringAI 核心 Bean 装配。
 * <p>
 * 通义千问 DashScope 通过 OpenAI 兼容协议接入，由 spring-ai-openai 自动装配提供
 * ChatModel / EmbeddingModel；此处在此基础上构建 ChatClient 与内存向量库。
 * </p>
 */
@Configuration
public class SpringAiConfig {

    /** 同步对话客户端（结果生成 Skill 使用）。 */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /** 内存向量库：启动时加载内置知识，任务文档按需写入。 */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return new SimpleVectorStore(embeddingModel);
    }
}
