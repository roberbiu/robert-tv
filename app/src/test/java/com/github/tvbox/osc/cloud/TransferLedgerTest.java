package com.github.tvbox.osc.cloud;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferLedgerTest {
    private static final String ACCOUNT = "fake_account_a";
    private static final String FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private TransferLedger.Operation saved(TransferLedger ledger) throws Exception {
        TransferLedger.Directory folder = ledger.beginDirectory(CloudProvider.QUARK, ACCOUNT);
        assertNotEquals("TV", folder.name); ledger.confirmDirectory(CloudProvider.QUARK, ACCOUNT, folder.name, "owned_folder");
        TransferLedger.Operation operation = ledger.begin(CloudProvider.QUARK, ACCOUNT, "owned_folder", FINGERPRINT);
        ledger.task(operation, "owned_task"); return ledger.saved(operation, "owned_file");
    }
    private CloudSessionManager.Snapshot session(String account, long epoch) { return new CloudSessionManager.Snapshot(CloudProvider.QUARK, account, epoch, 0, 0, "__pus=fake"); }
    @Test public void onlyExactOwnedFileWithoutActiveLeaseCanBeDeleted() throws Exception {
        TransferLedger ledger = new TransferLedger(new CloudSessionManagerTest.MemoryStore()); TransferLedger.Operation operation = saved(ledger);
        assertTrue(ledger.canDelete(operation, ACCOUNT, 4, session(ACCOUNT, 4)));
        ledger.retain(operation); assertFalse(ledger.canDelete(operation, ACCOUNT, 4, session(ACCOUNT, 4)));
        ledger.release(operation); assertFalse(ledger.canDelete(operation, ACCOUNT, 4, session("fake_account_b", 4)));
        assertFalse(ledger.canDelete(operation, ACCOUNT, 4, session(ACCOUNT, 5)));
        operation.fileId = "user_personal_file"; assertFalse(ledger.canDelete(operation, ACCOUNT, 4, session(ACCOUNT, 4)));
    }
    @Test public void pendingIntentSurvivesRestartAndCannotBeRepeated() throws Exception {
        CloudSessionManagerTest.MemoryStore store = new CloudSessionManagerTest.MemoryStore(); TransferLedger ledger = new TransferLedger(store);
        TransferLedger.Directory directory = ledger.beginDirectory(CloudProvider.QUARK, ACCOUNT);
        ledger.confirmDirectory(CloudProvider.QUARK, ACCOUNT, directory.name, "owned_folder");
        TransferLedger.Operation pending = ledger.begin(CloudProvider.QUARK, ACCOUNT, "owned_folder", FINGERPRINT);
        TransferLedger restored = new TransferLedger(store);
        assertEquals(pending.id, restored.operation(CloudProvider.QUARK, ACCOUNT, FINGERPRINT).id);
        try { restored.begin(CloudProvider.QUARK, ACCOUNT, "owned_folder", FINGERPRINT); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.OWNERSHIP_UNKNOWN, expected.code); }
        assertNull(restored.operation(CloudProvider.UC, ACCOUNT, FINGERPRINT)); assertNull(restored.operation(CloudProvider.QUARK, "fake_account_b", FINGERPRINT));
    }
    @Test public void unknownFolderIsNeverAdoptedByName() throws Exception {
        TransferLedger ledger = new TransferLedger(new CloudSessionManagerTest.MemoryStore());
        try { ledger.begin(CloudProvider.QUARK, ACCOUNT, "a_users_TV_folder", FINGERPRINT); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.OWNERSHIP_UNKNOWN, expected.code); }
        TransferLedger.Directory folder = ledger.beginDirectory(CloudProvider.QUARK, ACCOUNT);
        try { ledger.confirmDirectory(CloudProvider.QUARK, ACCOUNT, "TV", "user_folder"); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.OWNERSHIP_UNKNOWN, expected.code); }
        assertTrue(folder.fid.isEmpty());
    }
    @Test public void damagedProviderLedgerRecoversOnlyAfterExplicitAuthorization() throws Exception {
        CloudSessionManagerTest.MemoryStore store = new CloudSessionManagerTest.MemoryStore(); store.values.put("transfer_ledger_quark", "damaged fixture");
        TransferLedger quark = new TransferLedger(store, CloudProvider.QUARK), uc = new TransferLedger(store, CloudProvider.UC);
        try { quark.directory(CloudProvider.QUARK, ACCOUNT); fail(); } catch (CloudException expected) { assertEquals(CloudException.Code.PRIVATE_STORAGE, expected.code); }
        assertNull(uc.directory(CloudProvider.UC, ACCOUNT));
        quark.recoverAfterAuthorization(); TransferLedger.Directory restored = quark.beginDirectory(CloudProvider.QUARK, ACCOUNT);
        assertTrue(restored.fid.isEmpty()); assertFalse(restored.name.equals("TV")); assertNull(quark.operation(CloudProvider.QUARK, ACCOUNT, FINGERPRINT));
    }
    @Test public void failedDirectoryCreationCannotPermanentlyTrapTheAccount() throws Exception {
        CloudSessionManagerTest.MemoryStore store = new CloudSessionManagerTest.MemoryStore(); TransferLedger ledger = new TransferLedger(store);
        TransferLedger.Directory unknown = ledger.beginDirectory(CloudProvider.QUARK, ACCOUNT);
        ledger.directoryDispatched(CloudProvider.QUARK, ACCOUNT, unknown.name);
        TransferLedger restarted = new TransferLedger(store);
        TransferLedger.Directory retry = restarted.beginDirectory(CloudProvider.QUARK, ACCOUNT);
        assertNotEquals(unknown.name, retry.name); assertEquals(1, restarted.unknownDirectoryCount());
        restarted.confirmDirectory(CloudProvider.QUARK, ACCOUNT, retry.name, "fresh_owned_folder");
        TransferLedger.Operation operation = restarted.begin(CloudProvider.QUARK, ACCOUNT, "fresh_owned_folder", FINGERPRINT);
        assertEquals("fresh_owned_folder", operation.folderId); assertEquals(1, new TransferLedger(store).unknownDirectoryCount());
    }
    @Test public void cancellationBeforeDispatchRollsBackOnlyLocalIntent() throws Exception {
        TransferLedger ledger = new TransferLedger(new CloudSessionManagerTest.MemoryStore());
        TransferLedger.Directory unsent = ledger.beginDirectory(CloudProvider.QUARK, ACCOUNT);
        ledger.cancelUnsentDirectory(CloudProvider.QUARK, ACCOUNT, unsent.name);
        assertNull(ledger.directory(CloudProvider.QUARK, ACCOUNT)); assertEquals(0, ledger.unknownDirectoryCount());
    }
}
