package com.github.tvbox.osc.cloud;

/** 分享目录内的一项，文件票据仅在解析期间内存持有。 */
public final class CloudFile {
    public final String id;
    public final String parentId;
    public final String name;
    public final boolean directory;
    public final boolean video;
    public final long size;
    public final int page;
    final transient String fileToken;

    CloudFile(String id, String parentId, String name, boolean directory, boolean video, long size, String fileToken, int page) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.directory = directory;
        this.video = video;
        this.size = size;
        this.fileToken = fileToken;
        this.page = page;
    }

    public CloudReference reference(CloudReference parent) {
        CloudReference result = directory ? parent.child(id, "", name) : parent.child(parentId, id, name);
        result.page = directory ? 1 : page;
        return result;
    }
}
