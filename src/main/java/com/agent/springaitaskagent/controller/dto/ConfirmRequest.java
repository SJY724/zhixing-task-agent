package com.agent.springaitaskagent.controller.dto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人工确认请求：approved=true 放行继续执行；false 取消任务。
 */
public class ConfirmRequest {

    @NotNull(message="必须明确提供 approved=true 或 false")
    private Boolean approved;
    @Size(max=500, message="审批备注不能超过500字符")
    private String comment;

    public boolean isApproved() {
        return Boolean.TRUE.equals(approved);
    }

    public void setApproved(Boolean approved) {
        this.approved = approved;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
