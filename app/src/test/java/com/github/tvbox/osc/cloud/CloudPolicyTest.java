package com.github.tvbox.osc.cloud;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import org.junit.Test;
import java.net.InetAddress;
import java.util.Arrays;
import static org.junit.Assert.*;

public class CloudPolicyTest {
    @Test public void publicShareIdentityContainsNoEphemeralCredential() throws Exception {
        CloudReference reference = CloudReference.fromShareUrl("https://pan.quark.cn/s/fakeshare12 提取码：aB12", "fty_wogg", "/voddetail/12.html");
        CloudReference decoded = CloudReference.decode(reference.child("fake_folder", "fake_file", "第01集.mkv").encode());
        assertEquals("fakeshare12", decoded.shareId); assertEquals("aB12", decoded.passcode); assertEquals("fake_file", decoded.fileId);
        assertFalse(decoded.encode().contains("stoken")); assertFalse(decoded.toString().contains("fakeshare12"));
        for (String url : Arrays.asList("https://pan.quark.cn.evil.example/s/fakeshare12", "file:///s/fakeshare12", "https://user:secret@pan.quark.cn/s/fakeshare12", "https://127.0.0.1/s/fakeshare12")) {
            try { CloudReference.fromShareUrl(url, "fty_wogg", "12"); fail(url); } catch (CloudException expected) { assertEquals(CloudException.Code.UNSUPPORTED, expected.code); }
        }
    }
    @Test public void shareExpiryAndNetworkFailureDoNotMeanLogout() {
        JsonObject expired = new JsonObject(); expired.addProperty("code", 401); expired.addProperty("message", "stoken expired");
        assertEquals(CloudException.Code.NO_MATCH, CloudErrors.classify(401, expired));
        assertEquals(CloudException.Code.NETWORK, CloudErrors.classify(503, new JsonObject()));
        JsonObject entitlement = new JsonObject(); entitlement.addProperty("message", "需要会员权益");
        assertEquals(CloudException.Code.ENTITLEMENT_REQUIRED, CloudErrors.classify(403, entitlement));
        JsonObject login = new JsonObject(); login.addProperty("message", "登录已过期");
        assertEquals(CloudException.Code.SESSION_EXPIRED, CloudErrors.classify(401, login));
    }
    @Test public void upstreamCannotReachLocalOrReservedNetworks() throws Exception {
        for (String address : Arrays.asList("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.1.1", "192.0.2.1", "198.51.100.3", "203.0.113.9", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::")) {
            assertFalse(address, NetworkPolicy.isPublic(InetAddress.getByName(address)));
            try { NetworkPolicy.requireUrl("https://" + (address.contains(":") ? "[" + address + "]" : address) + "/", null, true); fail(address); } catch (CloudException expected) { }
        }
        assertTrue(NetworkPolicy.isPublic(InetAddress.getByName("8.8.8.8"))); assertTrue(NetworkPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }
    @Test public void cookieRotationRejectsInjectedHeadersAndPreservesOtherFields() throws Exception {
        assertEquals("__pus=fake; __puus=new", CookieValues.merge("__pus=fake; __puus=old", Arrays.asList("__puus=new; Path=/; Secure")));
        try { CookieValues.merge("__pus=fake\r\nHost:evil", java.util.Collections.emptyList()); fail(); } catch (CloudException expected) { }
    }
    @Test public void mediaUsesLatestCredentialButStripsItAcrossHosts() throws Exception {
        CloudSessionManager manager = new CloudSessionManager(new CloudSessionManagerTest.MemoryStore());
        manager.complete(manager.beginAuthorization(CloudProvider.QUARK), "fake_account_a", "__pus=fake; __puus=old");
        CloudSessionManager.Snapshot leaseIdentity = manager.require(CloudProvider.QUARK);
        manager.rotate(leaseIdentity, "__pus=fake; __puus=new");
        assertEquals("__pus=fake; __puus=new", CloudRuntime.headersForMedia(manager, leaseIdentity, "https://download.quark.cn/a", "https://download.quark.cn/b").get("Cookie"));
        for (String redirected : Arrays.asList("https://another.quark.cn/b", "https://media.example.org/b"))
            assertFalse(CloudRuntime.headersForMedia(manager, leaseIdentity, "https://download.quark.cn/a", redirected).containsKey("Cookie"));
        try { CloudRuntime.headersForMedia(manager, leaseIdentity, "https://download.quark.cn/a", "https://download.quark.cn.evil.example/b"); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.INVALID_RESPONSE, expected.code); }
        manager.complete(manager.beginAuthorization(CloudProvider.QUARK), "fake_account_b", "__pus=other");
        try { CloudRuntime.headersForMedia(manager, leaseIdentity, "https://download.quark.cn/a", "https://download.quark.cn/b"); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.CANCELLED, expected.code); }
    }
    @Test public void incidentalJsonSerializationCannotExposePrivateTickets() throws Exception {
        CloudFile file = new CloudFile("fake_file", "0", "合成视频.mkv", false, true, 100, "private_file_ticket", 1);
        assertFalse(new Gson().toJson(file).contains("private_file_ticket"));
        CloudProviderClient.Stream stream = new CloudProviderClient.Stream("https://media.example.org/video?signature=private_stream", "high", Arrays.asList("high"));
        assertFalse(new Gson().toJson(stream).contains("private_stream"));
        CloudProviderClient.LoginTicket ticket = new CloudProviderClient.LoginTicket(CloudProvider.QUARK, "private_login_ticket", "__pus=private_login_cookie");
        String safe = new Gson().toJson(ticket); assertFalse(safe.contains("private_login_ticket")); assertFalse(safe.contains("private_login_cookie"));
    }
}
