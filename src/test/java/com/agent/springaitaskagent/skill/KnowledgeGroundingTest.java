package com.agent.springaitaskagent.skill;

import com.agent.springaitaskagent.rag.VectorStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeGroundingTest {

    @Test
    void missingKnowledgeDoesNotFallBackToModelKnowledge() {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.search("制度没有规定的问题")).thenReturn(List.of());
        String context = new InformationRetrieveSkill(store).retrieve("制度没有规定的问题");
        ChatModel model = mock(ChatModel.class);
        ResultGenerateSkill generator = new ResultGenerateSkill(model);

        assertThat(context).isEmpty();
        assertThat(generator.generate("制度没有规定的问题", context))
                .contains("未找到相关依据", "无法确认答案");
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void blankContextDoesNotCallModel() {
        ChatModel model = mock(ChatModel.class);
        ResultGenerateSkill generator = new ResultGenerateSkill(model);

        assertThat(generator.generate("问题", null)).contains("无法确认答案");
        assertThat(generator.generate("问题", "  ")).contains("无法确认答案");
        verify(model, never()).call(any(Prompt.class));
    }
}
