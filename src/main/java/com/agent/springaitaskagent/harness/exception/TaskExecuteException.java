package com.agent.springaitaskagent.harness.exception;

/**
 * 任务执行异常：携带业务错误码，由全局异常处理器统一收敛为 API 响应。
 * 例如：TASK_NOT_FOUND / CONFIRM_NOT_ALLOWED / RETRY_EXHAUSTED / DOC_PARSE_FAILED。
 */
public class TaskExecuteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public TaskExecuteException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TaskExecuteException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
