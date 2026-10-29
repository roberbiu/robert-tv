package com.github.tvbox.osc.cloud;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只认可本应用主动操作并保存的确切ID，不根据远端目录名字推定归属。 */
public final class TransferLedger {
    static final class Directory {
        String provider;
        String account;
        String name;
        String fid = "";
        boolean dispatched = true;
    }
    public static final class Operation {
        public String id;
        public String provider;
        public String account;
        public String folderId;
        public String fingerprint;
        public String taskId = "";
        public String fileId = "";
        public String status = "PENDING";
        public int attempts = 1;
    }
    private static final class Data {
        int schema = 1;
        String installation = UUID.randomUUID().toString().replace("-", "");
        Map<String, Directory> directories = new LinkedHashMap<>();
        List<Directory> orphanDirectories = new ArrayList<>();
        Map<String, Operation> operations = new LinkedHashMap<>();
    }
    private final SecretStore store;
    private final String storageKey;
    private final Gson gson = new Gson();
    private final Map<String, Integer> holds = new LinkedHashMap<>();
    private Data data;
    private boolean unavailable;

    public TransferLedger(SecretStore store) {
        this(store, "transfer_ledger");
    }

    public TransferLedger(SecretStore store, CloudProvider provider) {
        this(store, "transfer_ledger_" + provider.id);
    }

    private TransferLedger(SecretStore store, String storageKey) {
        this.store = store;
        this.storageKey = storageKey;
        data = new Data();
        try {
            String value = store.read(storageKey);
            if (value != null) {
                Data loaded = gson.fromJson(value, Data.class);
                if (loaded == null || loaded.schema != 1 || !loaded.installation.matches("[a-zA-Z0-9]{16,64}")
                        || loaded.directories == null || loaded.directories.size() > 32 || loaded.orphanDirectories == null || loaded.orphanDirectories.size() > 128
                        || loaded.operations == null || loaded.operations.size() > 128) throw new IllegalArgumentException();
                for (Map.Entry<String, Directory> entry : loaded.directories.entrySet()) {
                    Directory directory = entry.getValue();
                    if (directory == null || !entry.getKey().equals(directory.provider + ":" + directory.account)
                            || !directory.account.matches("[A-Za-z0-9_-]{8,128}") || directory.name == null
                            || !directory.name.startsWith("_LiuliuFufuTV_" + loaded.installation.substring(0, 12) + "_")
                            || directory.fid == null || (!directory.fid.isEmpty() && !CloudProviderClient.validId(directory.fid))) throw new IllegalArgumentException();
                    CloudProvider.fromId(directory.provider);
                }
                for (Map.Entry<String, Operation> entry : loaded.operations.entrySet()) {
                    Operation operation = entry.getValue();
                    if (operation == null || !entry.getKey().equals(operation.provider + ":" + operation.account + ":" + operation.fingerprint)
                            || !operation.id.matches("[A-Za-z0-9-]{16,64}") || !operation.fingerprint.matches("[a-f0-9]{64}")
                            || operation.taskId == null || operation.fileId == null || !operation.status.matches("PENDING|SAVED")) throw new IllegalArgumentException();
                    Directory directory = loaded.directories.get(operation.provider + ":" + operation.account);
                    if (directory == null || !directory.fid.equals(operation.folderId)
                            || (!operation.taskId.isEmpty() && !CloudProviderClient.validId(operation.taskId))
                            || (!operation.fileId.isEmpty() && !CloudProviderClient.validId(operation.fileId))
                            || ("SAVED".equals(operation.status) && (operation.taskId.isEmpty() || operation.fileId.isEmpty()))) throw new IllegalArgumentException();
                }
                data = loaded;
            }
        } catch (Exception ignored) { unavailable = true; }
    }

    /** 仅在手机已确认新授权的事务中调用；丢失的远端归属不推测、不补删。 */
    synchronized void recoverAfterAuthorization() throws CloudException {
        if (!unavailable) return;
        data = new Data(); holds.clear();
        try { store.write(storageKey, gson.toJson(data)); unavailable = false; }
        catch (Exception ignored) { throw new CloudException(CloudException.Code.PRIVATE_STORAGE, null); }
    }

    synchronized Directory directory(CloudProvider provider, String account) throws CloudException {
        check();
        return data.directories.get(key(provider, account));
    }

    synchronized Directory beginDirectory(CloudProvider provider, String account) throws CloudException {
        check();
        if (account == null || !account.matches("[A-Za-z0-9_-]{8,128}")) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        Directory previous = data.directories.get(key(provider, account));
        if (previous != null && !previous.fid.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        if (previous == null && data.directories.size() >= 32) throw new CloudException(CloudException.Code.STORAGE_FULL, provider);
        if (previous != null && previous.dispatched) {
            // 创建请求结果未知时只保留orphan记录；本次明确重试用新随机名字，绝不复用/扫描/删除旧目录。
            if (data.orphanDirectories.size() >= 128) throw new CloudException(CloudException.Code.STORAGE_FULL, provider);
            data.orphanDirectories.add(previous);
        }
        Directory directory = new Directory(); directory.provider = provider.id; directory.account = account;
        directory.dispatched = false;
        directory.name = "_LiuliuFufuTV_" + data.installation.substring(0, 12) + "_" + UUID.randomUUID().toString().substring(0, 8);
        data.directories.put(key(provider, account), directory);
        save();
        return directory;
    }

    synchronized int unknownDirectoryCount() { return data.orphanDirectories.size(); }

    synchronized void directoryDispatched(CloudProvider provider, String account, String name) throws CloudException {
        check(); Directory directory = data.directories.get(key(provider, account));
        if (directory == null || !directory.name.equals(name) || !directory.fid.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        directory.dispatched = true; save();
    }

    synchronized void cancelUnsentDirectory(CloudProvider provider, String account, String name) throws CloudException {
        check(); Directory directory = data.directories.get(key(provider, account));
        if (directory != null && directory.name.equals(name) && directory.fid.isEmpty() && !directory.dispatched) { data.directories.remove(key(provider, account)); save(); }
    }

    synchronized void confirmDirectory(CloudProvider provider, String account, String name, String fid) throws CloudException {
        Directory directory = data.directories.get(key(provider, account));
        if (directory == null || !directory.name.equals(name) || !CloudProviderClient.validId(fid)) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        directory.fid = fid;
        directory.dispatched = true;
        save();
    }

    synchronized Operation operation(CloudProvider provider, String account, String fingerprint) throws CloudException {
        check();
        Operation value = data.operations.get(key(provider, account) + ":" + fingerprint);
        return value == null ? null : copy(value);
    }

    synchronized Operation begin(CloudProvider provider, String account, String folder, String fingerprint) throws CloudException {
        check();
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        if (data.operations.size() >= 128) throw new CloudException(CloudException.Code.STORAGE_FULL, provider);
        String key = key(provider, account) + ":" + fingerprint;
        if (data.operations.containsKey(key)) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        Directory directory = data.directories.get(key(provider, account));
        if (directory == null || !directory.fid.equals(folder) || folder.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        Operation operation = new Operation(); operation.id = UUID.randomUUID().toString(); operation.provider = provider.id;
        operation.account = account; operation.folderId = folder; operation.fingerprint = fingerprint;
        data.operations.put(key, operation); save();
        return copy(operation);
    }

    synchronized void task(Operation expected, String taskId) throws CloudException {
        Operation actual = actual(expected);
        if (!CloudProviderClient.validId(taskId) || !actual.fileId.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, CloudProvider.fromId(expected.provider));
        actual.taskId = taskId; save();
    }

    synchronized Operation saved(Operation expected, String fid) throws CloudException {
        Operation actual = actual(expected);
        if (!CloudProviderClient.validId(fid) || actual.taskId.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, CloudProvider.fromId(expected.provider));
        actual.fileId = fid; actual.status = "SAVED"; save();
        return copy(actual);
    }

    public synchronized void retain(Operation expected) throws CloudException {
        Operation actual = actual(expected);
        if (!"SAVED".equals(actual.status) || actual.fileId.isEmpty()) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, CloudProvider.fromId(expected.provider));
        holds.put(actual.id, holds.containsKey(actual.id) ? holds.get(actual.id) + 1 : 1);
    }

    public synchronized void release(Operation expected) {
        Integer count = holds.get(expected.id);
        if (count == null) return;
        if (count <= 1) holds.remove(expected.id); else holds.put(expected.id, count - 1);
    }

    public synchronized boolean canDelete(Operation expected, String account, long activeEpoch, CloudSessionManager.Snapshot session) {
        try {
            Operation actual = actual(expected);
            Directory directory = data.directories.get(key(CloudProvider.fromId(actual.provider), account));
            return actual.account.equals(account) && session.provider.id.equals(actual.provider) && session.isAuthorized() && session.accountId.equals(account)
                    && session.authEpoch == activeEpoch && directory != null && directory.fid.equals(actual.folderId)
                    && "SAVED".equals(actual.status) && !actual.fileId.isEmpty() && actual.fileId.equals(expected.fileId) && !holds.containsKey(actual.id);
        } catch (CloudException ignored) { return false; }
    }

    synchronized void deleted(Operation expected) throws CloudException {
        Operation actual = actual(expected);
        if (holds.containsKey(actual.id)) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, CloudProvider.fromId(expected.provider));
        data.operations.remove(key(CloudProvider.fromId(actual.provider), actual.account) + ":" + actual.fingerprint);
        save();
    }

    private Operation actual(Operation expected) throws CloudException {
        check();
        Operation actual = data.operations.get(expected.provider + ":" + expected.account + ":" + expected.fingerprint);
        if (actual == null || !actual.id.equals(expected.id) || !actual.account.equals(expected.account)
                || !actual.folderId.equals(expected.folderId)) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, CloudProvider.fromId(expected.provider));
        return actual;
    }
    private static String key(CloudProvider provider, String account) { return provider.id + ":" + account; }
    private Operation copy(Operation operation) { return gson.fromJson(gson.toJson(operation), Operation.class); }
    private void check() throws CloudException { if (unavailable) throw new CloudException(CloudException.Code.PRIVATE_STORAGE, null); }
    private void save() throws CloudException {
        try { store.write(storageKey, gson.toJson(data)); }
        catch (Exception ignored) { unavailable = true; throw new CloudException(CloudException.Code.PRIVATE_STORAGE, null); }
    }
}
