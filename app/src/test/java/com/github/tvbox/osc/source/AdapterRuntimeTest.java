package com.github.tvbox.osc.source;

import com.github.tvbox.osc.catalog.SourceDescriptor;
import org.junit.Test;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AdapterRuntimeTest {
    @Test public void operationReceivesCapturedIdentityAndCallerHasNoThreadContext() {
        SourceDescriptor source = descriptor("identity");
        RequestContext context = new RequestContext(source, 5, 8).withPlayback(11, true);
        SourceResult<String> result = AdapterRuntime.get().execute(source, context, () -> {
            assertSame(context, AdapterRuntime.currentContext());
            assertEquals(5, AdapterRuntime.currentContext().getRegistryRevision());
            assertEquals(11, AdapterRuntime.currentContext().getPlaybackGeneration());
            assertTrue(AdapterRuntime.currentContext().isExplicitUserAction());
            return "ok";
        });
        assertTrue(result.isSuccess());
        assertEquals("ok", result.getData());
        assertNull(AdapterRuntime.currentContext());
    }

    @Test public void errorsAreClassifiedWithoutExposingCauseMessages() {
        SourceDescriptor source = descriptor("errors");
        SourceResult<String> network = AdapterRuntime.get().execute(source, new RequestContext(source, 1, 1),
                () -> { throw new IOException("Cookie=private"); });
        assertEquals(DomainError.Code.NETWORK, network.getError().getCode());
        assertFalse(network.getError().toJson().toString().contains("private"));
        SourceResult<String> auth = AdapterRuntime.get().execute(source, new RequestContext(source, 1, 2),
                () -> { throw new SourceException(new DomainError(DomainError.Code.AUTH_REQUIRED, "quark")); });
        assertEquals(DomainError.Code.AUTH_REQUIRED, auth.getError().getCode());
        assertEquals("quark", auth.getError().getProvider());
        SourceResult<String> broken = AdapterRuntime.get().execute(source, new RequestContext(source, 1, 3),
                () -> { throw new IllegalStateException("private response"); });
        assertEquals(DomainError.Code.INVALID_RESPONSE, broken.getError().getCode());
        assertFalse(broken.getError().toJson().toString().contains("private"));
    }

    @Test public void cancellationInterruptsInFlightOperationAndIsSharedWithPlaybackCopy() throws Exception {
        SourceDescriptor source = descriptor("cancel");
        RequestContext parent = new RequestContext(source, 1, 1);
        RequestContext playing = parent.withPlayback(9, true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch neverRelease = new CountDownLatch(1);
        FutureTask<SourceResult<String>> call = new FutureTask<>(() -> AdapterRuntime.get().execute(source, playing, () -> {
            entered.countDown();
            try { neverRelease.await(); return "late"; }
            catch (InterruptedException error) { interrupted.countDown(); throw error; }
        }));
        Thread caller = new Thread(call, "test-runtime-caller");
        caller.setDaemon(true);
        caller.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        parent.cancel();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals(DomainError.Code.CANCELLED, call.get(2, TimeUnit.SECONDS).getError().getCode());
        assertTrue(playing.isCancelled());
    }

    @Test public void wrongSourceRevisionDoesNotExecuteOperation() {
        SourceDescriptor source = descriptor("revision1");
        RequestContext other = new RequestContext(descriptor("revision2"), 1, 1);
        SourceResult<String> result = AdapterRuntime.get().execute(source, other, () -> { fail("不能派发错误身份"); return ""; });
        assertEquals(DomainError.Code.SOURCE_UNAVAILABLE, result.getError().getCode());
    }

    private static SourceDescriptor descriptor(String id) {
        return new SourceDescriptor(id, id, "cms", 1, "cms", true, "https://example.org/api.php/provide/vod/",
                Collections.emptyList(), Collections.singletonList("电影"), "none", "none", "unverified", 10, 5);
    }
}
