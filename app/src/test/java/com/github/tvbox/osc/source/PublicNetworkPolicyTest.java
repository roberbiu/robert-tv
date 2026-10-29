package com.github.tvbox.osc.source;

import org.junit.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PublicNetworkPolicyTest {
    @Test public void URLValidationRejectsLocalAddressesCredentialsAndOtherSchemes() {
        for (String url : new String[]{"file:///data/private", "ftp://example.org/", "http://example.org/",
                "https://name:secret@example.org/", "https://localhost/", "https://x.local/", "https://x.internal/",
                "https://127.0.0.1/", "https://10.0.0.1/", "https://172.16.1.2/", "https://192.168.1.2/",
                "https://169.254.169.254/", "https://100.64.0.1/", "https://0.0.0.0/", "https://224.0.0.1/", "https://[::1]/",
                "https://[fd00::1]/", "https://example.org./", "https://example.org/#private"}) {
            try { PublicNetworkPolicy.requirePublicUrl(url, false); fail(url); }
            catch (IllegalArgumentException expected) { assertEquals("上游目标未准入", expected.getMessage()); }
        }
        assertEquals("example.org", PublicNetworkPolicy.requirePublicUrl("https://example.org/video", false).getHost());
        assertEquals("http", PublicNetworkPolicy.requirePublicUrl("http://example.org/", true).getScheme());
    }

    @Test public void connectionDnsRejectsAnyPrivateAnswerWithoutRealNetwork() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{93, (byte)184, (byte)216, 34});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        assertEquals(1, PublicNetworkPolicy.publicDns(host -> Arrays.asList(publicAddress)).lookup("example.org").size());
        try {
            PublicNetworkPolicy.publicDns(host -> Arrays.asList(publicAddress, privateAddress)).lookup("example.org");
            fail("混合DNS结果不能留下私网地址");
        } catch (UnknownHostException expected) { assertEquals("上游地址未准入", expected.getMessage()); }
    }
}
