package com.agent.springaitaskagent.controller.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 任务提交请求。
 * <p>question 必填；补充文本仅当前任务使用，文档范围可选。</p>
 */
public class AgentTaskRequest {

    @NotBlank(message="问题不能为空")
    @Size(max=4000, message="问题不能超过4000字符")
    private String question;
    private String documentPath;
    @Size(max=20000, message="补充文本不能超过20000字符")
    private String textContent;
    @Size(max=50, message="最多选择50份文档")
    private List<String> documentIds = List.of();
    public List<String> getDocumentIds() { return documentIds == null ? List.of() : documentIds; }
    public void setDocumentIds(List<String> documentIds) { this.documentIds = documentIds; }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getDocumentPath() {
        return documentPath;
    }

    public void setDocumentPath(String documentPath) {
        this.documentPath = documentPath;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }
}
