package com.github.tvbox.osc.source;

/** 只携带受控错误，不保留可能含凭据的原始异常消息。 */
public final class SourceException extends Exception {
    private final DomainError error;
    public SourceException(DomainError error) {
        super(error.getCode().name());
        this.error = error;
    }
    public DomainError getError() { return error; }
}
