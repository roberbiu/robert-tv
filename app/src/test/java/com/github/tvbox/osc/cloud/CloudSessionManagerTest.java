package com.github.tvbox.osc.cloud;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class CloudSessionManagerTest {
    static final class MemoryStore implements SecretStore {
        final Map<String, String> values = new HashMap<>(); boolean fail;
        @Override public String read(String key) { return values.get(key); }
        @Override public void write(String key, String value) throws Exception { if (fail) throw new Exception("fixture"); values.put(key, value); }
    }
    @Test public void credentialRotationKeepsLeaseEpochAndRejectsOld401() throws Exception {
        MemoryStore store = new MemoryStore(); CloudSessionManager manager = new CloudSessionManager(store);
        assertTrue(manager.complete(manager.beginAuthorization(CloudProvider.QUARK), "fake_account_a", "__pus=fake; __puus=old"));
        CloudSessionManager.Snapshot old = manager.require(CloudProvider.QUARK);
        assertTrue(manager.rotate(old, "__pus=fake; __puus=new"));
        CloudSessionManager.Snapshot rotated = manager.require(CloudProvider.QUARK);
        assertEquals(old.authEpoch, rotated.authEpoch); assertEquals(old.credentialRevision + 1, rotated.credentialRevision);
        assertFalse(manager.expireIfCurrent(old)); assertTrue(manager.hasSession(CloudProvider.QUARK));
        assertFalse(manager.hasSession(CloudProvider.UC));
        assertEquals(rotated.credentialRevision, new CloudSessionManager(store).require(CloudProvider.QUARK).credentialRevision);
    }
    @Test public void cancelledOrLoggedOutAuthorizationCannotReviveSession() throws Exception {
        CloudSessionManager manager = new CloudSessionManager(new MemoryStore());
        CloudSessionManager.Authorization cancelled = manager.beginAuthorization(CloudProvider.QUARK); manager.cancel(cancelled);
        assertFalse(manager.complete(cancelled, "fake_account_a", "__pus=fake"));
        CloudSessionManager.Authorization old = manager.beginAuthorization(CloudProvider.QUARK); manager.logout(CloudProvider.QUARK);
        assertFalse(manager.complete(old, "fake_account_a", "__pus=fake")); assertFalse(manager.hasSession(CloudProvider.QUARK));
        CloudSessionManager.Authorization newer = manager.beginAuthorization(CloudProvider.QUARK);
        assertTrue(manager.complete(newer, "fake_account_b", "__pus=fake_b"));
        assertFalse(manager.complete(old, "fake_account_a", "__pus=fake_a")); assertEquals("fake_account_b", manager.require(CloudProvider.QUARK).accountId);
    }
    @Test public void persistenceFailureNeverActivatesNewAccount() throws Exception {
        MemoryStore store = new MemoryStore(); CloudSessionManager manager = new CloudSessionManager(store);
        assertTrue(manager.complete(manager.beginAuthorization(CloudProvider.UC), "fake_account_a", "__pus=fake"));
        CloudSessionManager.Snapshot first = manager.require(CloudProvider.UC); store.fail = true;
        try { manager.complete(manager.beginAuthorization(CloudProvider.UC), "fake_account_b", "__pus=other"); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.PRIVATE_STORAGE, expected.code); }
        assertEquals(first.accountId, manager.require(CloudProvider.UC).accountId);
        try { manager.logout(CloudProvider.UC); fail(); } catch (CloudException expected) { assertEquals(CloudException.Code.PRIVATE_STORAGE, expected.code); }
        assertFalse(manager.hasSession(CloudProvider.UC));
    }
    @Test public void corruptStateCannotExposeAnAuthorizedSnapshot() {
        MemoryStore store = new MemoryStore(); store.values.put("session_quark", "{\"schema\":1,\"provider\":\"quark\",\"account\":\"bad\",\"epoch\":-1,\"revision\":0,\"updatedAt\":1,\"cookie\":\"fake\"}");
        CloudSessionManager manager = new CloudSessionManager(store); assertFalse(manager.hasSession(CloudProvider.QUARK)); assertFalse(manager.snapshot(CloudProvider.QUARK).isAuthorized());
    }
}
