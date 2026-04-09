package com.questionos.backend.api.dto;

/** 背景资料：服务端抽取纯文本 */
public final class BackgroundDtos {
    public record ExtractResponse(String text, boolean truncated) {}

    public record ErrorBody(String message) {}
}
