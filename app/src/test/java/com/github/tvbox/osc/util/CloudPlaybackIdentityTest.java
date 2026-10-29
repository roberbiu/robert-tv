package com.github.tvbox.osc.util;

import com.github.tvbox.osc.cloud.CloudReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudPlaybackIdentityTest {
    private CloudReference file() {
        CloudReference value = new CloudReference();
        value.sourceId = "wogg"; value.provider = "quark"; value.shareId = "share123"; value.fileId = "file456";
        return value;
    }
    @Test public void renameFolderPageAndQualityKeepPosition() {
        CloudReference ref = file(); String key = CloudPlaybackIdentity.progressKey(ref);
        ref.fileName = "第1集更名"; ref.page = 2; ref.folderId = "folder99"; ref.quality = "4k";
        assertEquals(key, CloudPlaybackIdentity.progressKey(ref));
    }
    @Test public void differentFileShareAndProviderHaveDifferentPosition() {
        CloudReference ref = file(); String key = CloudPlaybackIdentity.progressKey(ref);
        ref.fileId = "file789"; assertNotEquals(key, CloudPlaybackIdentity.progressKey(ref));
        ref = file(); ref.shareId = "share789"; assertNotEquals(key, CloudPlaybackIdentity.progressKey(ref));
        ref = file(); ref.provider = "uc"; assertNotEquals(key, CloudPlaybackIdentity.progressKey(ref));
    }
    @Test(expected = IllegalArgumentException.class) public void sharePlaceholderHasNoFileProgress() {
        CloudReference ref = file(); ref.fileId = ""; CloudPlaybackIdentity.progressKey(ref);
    }
}
