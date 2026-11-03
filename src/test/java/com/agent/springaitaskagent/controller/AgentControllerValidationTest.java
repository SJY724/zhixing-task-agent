package com.agent.springaitaskagent.controller;

import com.agent.springaitaskagent.common.GlobalExceptionHandler;
import com.agent.springaitaskagent.common.TaskLogService;
import com.agent.springaitaskagent.harness.TaskHarness;
import com.agent.springaitaskagent.harness.TaskState;
import com.agent.springaitaskagent.rag.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Boundary tests never construct a cloud client or read an API key. */
class AgentControllerValidationTest {
    private TaskHarness harness;
    private TaskLogService logs;
    private KnowledgeService knowledge;
    private MockMvc mvc;
    private ObjectMapper mapper;
    private LocalValidatorFactoryBean validator;

    @BeforeEach void setUp() {
        harness = mock(TaskHarness.class);
        logs = mock(TaskLogService.class);
        knowledge = mock(KnowledgeService.class);
        when(knowledge.isReady()).thenReturn(true);
        when(knowledge.storageName()).thenReturn("H2");
        AgentController controller = new AgentController(harness, logs, knowledge);
        ReflectionTestUtils.setField(controller, "chatModel", "qwen-plus");
        ReflectionTestUtils.setField(controller, "embeddingModel", "text-embedding-v2");
        ReflectionTestUtils.setField(controller, "apiKey", "sk-test-not-a-real-key");
        mapper = new ObjectMapper().findAndRegisterModules();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator).build();
    }

    @AfterEach void closeValidator() { validator.close(); }

    @Test void deletionRecoveryRoutesAreExplicitAndDeletedListIsNotATaskId() throws Exception {
        TaskState history = new TaskState("task-test", "history", null, null);
        history.setStatus(TaskState.Status.SUCCEEDED);
        when(harness.listDeletedTasks()).thenReturn(List.of(history));
        when(harness.restore("task-test")).thenReturn(history);
        mvc.perform(delete("/api/agent/tasks/task-test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true)).andExpect(jsonPath("$.data.recoverable").value(true));
        verify(harness).delete("task-test");
        mvc.perform(get("/api/agent/tasks/deleted")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].taskId").value("task-test"));
        verify(harness, never()).requireTask("deleted");
        mvc.perform(post("/api/agent/tasks/task-test/restore")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("task-test"));
    }

    @Test void permanentDeletionHasASeparateExplicitRoute() throws Exception {
        mvc.perform(delete("/api/agent/tasks/task-test/permanent")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.recoverable").value(false));
        verify(harness).permanentlyDelete("task-test");
        verify(harness, never()).delete(anyString());
    }

    @Test void approvalMustBeExplicitAndMissingValueCannotCancelTask() throws Exception {
        mvc.perform(post("/api/agent/tasks/task-test/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"review\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("approved")));
        verifyNoInteractions(harness);
    }

    @Test void malformedJsonReturns400InsteadOfSystemError() throws Exception {
        mvc.perform(post("/api/agent/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(harness);
    }

    @Test void blankAndOversizedQuestionsNeverReachTaskExecution() throws Exception {
        for (String question : List.of(" ", "x".repeat(4001))) {
            mvc.perform(post("/api/agent/tasks").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("question", question))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(harness);
    }

    @Test void publicApiCannotReadArbitraryLocalFilePath() throws Exception {
        mvc.perform(post("/api/agent/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("question", "read", "documentPath", "C:\\Users\\someone\\private.txt"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("本机文件路径")));
        verifyNoInteractions(harness);
    }

    @Test void selectionAndSupplementCannotBeMixedSilently() throws Exception {
        mvc.perform(post("/api/agent/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("question", "limit", "documentIds", List.of("doc-123"), "textContent", "private evidence"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请选择一种")));
        verifyNoInteractions(harness);
        verify(knowledge, never()).validateSelection(anyList());
    }

    @Test void unknownOrUnreadySelectedDocumentsRejectBeforeTaskCreation() throws Exception {
        doThrow(new IllegalArgumentException("知识文档不存在")).when(knowledge).validateSelection(List.of("doc-missing"));
        mvc.perform(post("/api/agent/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("question", "limit", "documentIds", List.of("doc-missing")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("知识文档不存在"));
        verifyNoInteractions(harness);
    }

    @Test void taskLogsExcludeOtherTasksAndBootstrapRecords() throws Exception {
        TaskState state = new TaskState("task-test", "question", null, null);
        when(harness.requireTask("task-test")).thenReturn(state);
        LocalDateTime time = LocalDateTime.of(2026, 11, 1, 12, 0);
        when(logs.list()).thenReturn(List.of(
                new TaskLogService.LogRecord(time, "task-other", "trace-other", "SKILL", "other task"),
                new TaskLogService.LogRecord(time, "task-test", "trace-test", "SKILL", "this task"),
                new TaskLogService.LogRecord(time, "BOOTSTRAP", null, "SKILL", "startup")));
        mvc.perform(get("/api/agent/tasks/task-test/logs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].taskId").value("task-test"))
                .andExpect(jsonPath("$.data[0].message").value("this task"));
        verify(harness).requireTask("task-test");
    }

    @Test void systemReturnsReadinessAndModelsWithoutExposingCredential() throws Exception {
        when(knowledge.countReady()).thenReturn(2);
        mvc.perform(get("/api/agent/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("cloud"))
                .andExpect(jsonPath("$.data.chatModel").value("qwen-plus"))
                .andExpect(jsonPath("$.data.storage").value("H2"))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.knowledgeReady").value(true))
                .andExpect(jsonPath("$.data.documentCount").value(2))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(content().string(not(containsString("sk-test-not-a-real-key"))));
    }

    @Test void uploadValidationIsReportedAs400() throws Exception {
        doThrow(new IllegalArgumentException("支持 TXT、Markdown、PDF、DOCX 文档"))
                .when(knowledge).upload(any());
        MockMultipartFile file = new MockMultipartFile("file", "payload.exe", "application/octet-stream", new byte[]{1});
        mvc.perform(multipart("/api/agent/knowledge/upload").file(file))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(harness);
    }

    @Test void systemReportsConnectedDatabaseInsteadOfHardcodedH2() throws Exception {
        when(knowledge.storageName()).thenReturn("MySQL");
        mvc.perform(get("/api/agent/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storage").value("MySQL"));
    }
}
