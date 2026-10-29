package com.github.tvbox.osc.cloud;

/** 有界、按所选文件转存。未知结果保留账本，绝不反复POST或扫目录猜ID。 */
public final class TransferService {
    private final CloudSessionManager sessions;
    private final CloudProviderClient api;
    private final TransferLedger ledger;

    public TransferService(CloudSessionManager sessions, CloudProviderClient api, TransferLedger ledger) {
        this.sessions = sessions; this.api = api; this.ledger = ledger;
    }

    public void recoverAfterAuthorization() throws CloudException { ledger.recoverAfterAuthorization(); }

    public synchronized TransferLedger.Operation prepare(CloudReference reference, CloudSessionManager.Snapshot session, Cancellation cancellation) throws CloudException {
        CloudProvider provider = reference.cloudProvider();
        if (session.provider != provider) throw new CloudException(CloudException.Code.CANCELLED, provider);
        verify(session); cancellation.check();
        String fingerprint = CloudProviderClient.digest(reference.shareId + ":" + reference.fileId);
        TransferLedger.Operation existing = ledger.operation(provider, session.accountId, fingerprint);
        if (existing != null) {
            if ("SAVED".equals(existing.status) && !existing.fileId.isEmpty()) return held(existing);
            if (!existing.taskId.isEmpty()) {
                String fid = api.savedFileId(provider, existing.taskId, session, cancellation);
                if (!fid.isEmpty()) return held(ledger.saved(existing, fid));
            }
            // 上次POST是否成功无法证明时，停止自动重存；由明确的恢复流程处理。
            throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        }
        TransferLedger.Directory directory = ledger.directory(provider, session.accountId);
        if (directory == null || directory.fid.isEmpty()) {
            directory = ledger.beginDirectory(provider, session.accountId);
            try { cancellation.check(); }
            catch (CloudException cancelled) { ledger.cancelUnsentDirectory(provider, session.accountId, directory.name); throw cancelled; }
            ledger.directoryDispatched(provider, session.accountId, directory.name);
            String fid = api.createDirectory(provider, directory.name, session, cancellation);
            verify(session);
            ledger.confirmDirectory(provider, session.accountId, directory.name, fid);
        }
        if (directory.fid.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        CloudFile file = api.findFile(reference, session, cancellation);
        if (file.fileToken.isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        verify(session);
        TransferLedger.Operation operation = ledger.begin(provider, session.accountId, directory.fid, fingerprint);
        String task = api.saveFile(reference, file, directory.fid, session, cancellation);
        if (task.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        verify(session);
        ledger.task(operation, task);
        String fid = api.savedFileId(provider, task, session, cancellation);
        if (fid.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        verify(session);
        return held(ledger.saved(operation, fid));
    }

    private TransferLedger.Operation held(TransferLedger.Operation operation) throws CloudException { ledger.retain(operation); return operation; }

    public synchronized void releaseAndCleanup(TransferLedger.Operation operation, long epoch) {
        ledger.release(operation);
        CloudProvider provider = CloudProvider.fromId(operation.provider);
        CloudSessionManager.Snapshot session = sessions.snapshot(provider);
        if (!ledger.canDelete(operation, operation.account, epoch, session)) return;
        try {
            TransferLedger.Operation actual = ledger.operation(provider, operation.account, operation.fingerprint);
            if (actual == null || !actual.id.equals(operation.id)) return;
            try { api.deleteOwnedFile(provider, actual.fileId, session, new Cancellation()); }
            catch (CloudException failure) { if (failure.code != CloudException.Code.NO_MATCH) throw failure; }
            ledger.deleted(actual);
        } catch (CloudException ignored) {
            // 失败保留所有权记录；不以猜测的ID补清理，更不清整个目录。
        }
    }

    private void verify(CloudSessionManager.Snapshot expected) throws CloudException {
        CloudSessionManager.Snapshot current = sessions.require(expected.provider);
        if (current.authEpoch != expected.authEpoch || !current.accountId.equals(expected.accountId))
            throw new CloudException(CloudException.Code.CANCELLED, expected.provider);
    }
}
