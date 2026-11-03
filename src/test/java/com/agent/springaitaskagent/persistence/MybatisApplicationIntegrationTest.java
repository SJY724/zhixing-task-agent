package com.agent.springaitaskagent.persistence;

import com.agent.springaitaskagent.SpringAiTaskAgentApplication;
import com.agent.springaitaskagent.harness.TaskState;
import com.agent.springaitaskagent.rag.KnowledgeService;
import com.agent.springaitaskagent.persistence.mapper.TaskSnapshotMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Boots the real current application and real mapper scanning, without making cloud calls. */
@SpringBootTest(classes = SpringAiTaskAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=agent", "spring.datasource.url=jdbc:h2:mem:mybatis-application;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.ai.openai.api-key=offline-test-placeholder", "server.address=127.0.0.1"})
class MybatisApplicationIntegrationTest {
    @MockBean KnowledgeService knowledge;
    @Autowired TaskStateRepository repository;
    @Autowired TaskSnapshotMapper snapshots;
    @Autowired TestRestTemplate http;

    @Test void currentApplicationWiresMappersAndPreservesTransactionalTaskWrites() {
        String id = "mapper-app-" + UUID.randomUUID();
        TaskState task = new TaskState(id, "Spring Boot 的真实 Mapper 接线", null, null);
        task.addEvent("SUBMIT", "created");
        repository.save(task);
        assertNotNull(snapshots.selectById(id));
        assertEquals(1L, snapshots.selectCount(new QueryWrapper<com.agent.springaitaskagent.persistence.entity.TaskSnapshotEntity>().eq("task_id", id)));
        task.setStatus(TaskState.Status.RUNNING);
        task.addEvent("X".repeat(100), "must roll back");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> repository.save(task));
        TaskState restored = repository.findAll().stream().filter(row -> id.equals(row.getTaskId())).findFirst().orElseThrow();
        assertEquals(TaskState.Status.PENDING, restored.getStatus());
        assertEquals(1, restored.getEvents().size());
        assertEquals(200, http.getForEntity("/api/agent/tasks", String.class).getStatusCode().value());
    }
}
