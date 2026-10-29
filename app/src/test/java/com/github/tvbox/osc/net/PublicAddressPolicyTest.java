package com.github.tvbox.osc.net;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.NetworkPolicy;
import com.github.tvbox.osc.source.PublicNetworkPolicy;
import org.junit.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PublicAddressPolicyTest {
    @Test public void bothBoundariesRejectFakePrivateDocumentationAndTunnelAddresses() throws Exception {
        for (String value : Arrays.asList("198.18.0.1", "198.19.255.254", "192.0.2.3", "198.51.100.2", "203.0.113.3",
                "192.88.99.1", "100.64.1.2", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "0.0.0.0", "224.0.0.1", "240.1.1.1", "::1", "fc00::1", "fe80::1", "2001:db8::1",
                "2001::1", "2001:10::1", "2001:20::1", "2002:0808:0808::1", "64:ff9b::808:808", "3fff::1")) {
            InetAddress address = InetAddress.getByName(value);
            assertFalse(value, PublicAddressPolicy.isPublic(address));
            assertFalse(value, NetworkPolicy.isPublic(address));
            try { PublicNetworkPolicy.requirePublicAddress(address); fail(value); } catch (UnknownHostException expected) { }
            String url = "https://" + (value.contains(":") ? "[" + value + "]" : value) + "/";
            try { PublicNetworkPolicy.requirePublicUrl(url, false); fail(value); } catch (IllegalArgumentException expected) { }
            try { NetworkPolicy.requireUrl(url, null, true); fail(value); } catch (CloudException expected) { }
        }
    }

    @Test public void literalParserCannotPerformHostnameLookupsOrAcceptAmbiguousIp() throws Exception {
        for (String value : Arrays.asList("metadata.example.org", "127.1", "2130706433", "010.0.0.1", "::ffff:8.8.8.8", "fe80::1%eth0")) {
            try { PublicAddressPolicy.literal(value); fail(value); } catch (UnknownHostException expected) { }
        }
        for (String value : Arrays.asList("8.8.8.8", "223.5.5.5", "2606:4700:4700::1111", "2001:4860:4860::8888")) {
            assertTrue(value, PublicAddressPolicy.isPublic(PublicAddressPolicy.literal(value)));
        }
    }

    @Test public void onlyCanonicalPublicDomainCanBeSentToHttpsDns() throws Exception {
        assertEquals("www.wogg.live", PublicAddressPolicy.publicHostname("WWW.WOGG.LIVE"));
        for (String value : Arrays.asList("localhost", "nas", "a.local", "a.internal", "router.lan", "a.home", "a.in-addr.arpa",
                "a.onion", "a.test", "a.invalid", "a.example", "example.org.", "a..org", "-a.org", "user@a.org", "8.8.8.8", "0x7f.0.0.1")) {
            try { PublicAddressPolicy.publicHostname(value); fail(value); } catch (UnknownHostException expected) { }
        }
    }

    @Test public void mixedAnswersAreRejectedRatherThanSilentlyKeepingPublicSubset() throws Exception {
        try {
            PublicAddressPolicy.requirePublicAnswers(Arrays.asList(PublicAddressPolicy.literal("8.8.8.8"), PublicAddressPolicy.literal("198.18.1.1")));
            fail();
        } catch (UnknownHostException expected) { }
    }
}
