package com.agent.springaitaskagent.aspect;

import com.agent.springaitaskagent.common.TaskLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

/**
 * 日志采集切面：自动记录三个 Skill 的调用开始 / 完成 / 失败与耗时，写入 TaskLogService。
 */
@Aspect
@Component
public class TaskLogAspect {

    private final TaskLogService taskLogService;

    public TaskLogAspect(TaskLogService taskLogService) {
        this.taskLogService = taskLogService;
    }

    @Pointcut("execution(public * com.agent.springaitaskagent.skill..*(..))")
    public void skillMethods() {
    }

    @Around("skillMethods()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        String method = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        String taskId = MDC.get("taskId");
        if (taskId == null) { taskId = MDC.get("traceId") == null ? "BOOTSTRAP" : "REQUEST"; }
        taskLogService.record(taskId, "SKILL", "调用开始: " + method);
        try {
            Object result = pjp.proceed();
            long cost = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            taskLogService.record(taskId, "SKILL", "调用完成: " + method + "，耗时 " + cost + "ms");
            return result;
        } catch (Throwable t) {
            long cost = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            taskLogService.record(taskId, "SKILL",
                    "调用失败: " + method + "，耗时 " + cost + "ms，原因: " + t.getMessage());
            throw t;
        }
    }
}
