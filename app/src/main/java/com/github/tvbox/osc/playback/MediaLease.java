package com.github.tvbox.osc.playback;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.Cancellation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import okhttp3.Call;
import okhttp3.Response;

/** 单次播放能力，公开部分没有上游签名URL或Cookie。 */
public final class MediaLease {
    public interface Validator { boolean isValid(); }
    public interface Headers { Map<String, String> forTarget(String target) throws CloudException; }
    public interface Refresher { String refresh(Cancellation cancellation) throws CloudException; }

    public final String sourceId;
    public final String adapterId;
    public final long playbackGeneration;
    public final String provider;
    public final String accountId;
    public final long authEpoch;
    final transient String token;
    final transient String localUrl;
    final boolean hls;
    final transient Validator validator;
    final transient Headers headers;
    final transient Refresher refresher;
    final transient Runnable onRelease;
    final transient Map<String, ProxyRouter.Resource> resources = new LinkedHashMap<>();
    final transient Map<String, String> semanticIds = new LinkedHashMap<>();
    final transient Set<Call> activeCalls = new HashSet<>();
    final transient Set<Response> activeResponses = new HashSet<>();
    final transient Cancellation cancellation = new Cancellation();
    final transient LeaseRefresh refresh = new LeaseRefresh();
    long resourceUrlBytes;
    long mediaRevision;
    volatile transient String upstream;
    volatile transient String memoryPlaylist;
    volatile CloudException lastFailure;
    volatile boolean closed;
    volatile long lastAccess = System.nanoTime();
    int references = 1;
    boolean claimed = true;
    transient AutoCloseable pendingDeliveryLink;

    MediaLease(String sourceId, String adapterId, long generation, String provider, String accountId, long epoch,
               String token, String localUrl, String upstream, boolean hls, Validator validator,
               Headers headers, Refresher refresher, Runnable onRelease) {
        this.sourceId = sourceId; this.adapterId = adapterId; this.playbackGeneration = generation;
        this.provider = provider; this.accountId = accountId; this.authEpoch = epoch;
        this.token = token; this.localUrl = localUrl; this.upstream = upstream; this.hls = hls;
        this.validator = validator; this.headers = headers; this.refresher = refresher; this.onRelease = onRelease;
    }

    public String getLocalUrl() { return localUrl; }
    public CloudException getLastFailure() { return lastFailure; }
    public boolean isValid() { return !closed && validator.isValid(); }
    @Override public String toString() { return "MediaLease(" + sourceId + ",redacted)"; }
}
