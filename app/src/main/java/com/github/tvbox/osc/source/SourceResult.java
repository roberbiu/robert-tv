package com.github.tvbox.osc.source;

public final class SourceResult<T> {
    private final T data;
    private final DomainError error;
    private final RequestContext context;

    private SourceResult(T data, DomainError error, RequestContext context) {
        this.data = data;
        this.error = error;
        this.context = context;
    }
    public static <T> SourceResult<T> success(T data, RequestContext context) {
        return new SourceResult<>(data, null, context);
    }
    public static <T> SourceResult<T> failure(DomainError error, RequestContext context) {
        return new SourceResult<>(null, error, context);
    }
    public boolean isSuccess() { return error == null; }
    public T getData() { return data; }
    public DomainError getError() { return error; }
    public RequestContext getContext() { return context; }
}
