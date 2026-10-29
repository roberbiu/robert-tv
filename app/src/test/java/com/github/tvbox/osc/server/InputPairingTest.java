package com.github.tvbox.osc.server;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputPairingTest {
    private static final String ORIGIN = "http://192.168.1.20:9978";
    private static final String HOST = "192.168.1.20:9978";
    private static final String MIME = "application/x-www-form-urlencoded;charset=UTF-8";
    private static class FakeClock implements InputPairing.Clock {
        long now = 1000;
        @Override public long nowMillis() { return now; }
    }
    private static class Tokens implements InputPairing.TokenSource {
        int number;
        @Override public String next() { return String.format(java.util.Locale.ROOT, "%048x", ++number); }
    }

    @Test public void pageRequiresActiveTokenAndExactHost() {
        InputPairing pairing = new InputPairing(new FakeClock(), new Tokens());
        assertFalse(pairing.allowPage(HOST, ""));
        String token = InputPairing.tokenFromAddress(pairing.open(ORIGIN));
        assertTrue(pairing.allowPage(HOST, token));
        assertFalse(pairing.allowPage("attacker.example:9978", token));
        assertFalse(pairing.allowPage(HOST + ".attacker.example", token));
        assertFalse(pairing.allowPage(HOST, token + "x"));
        assertFalse(pairing.allowPage(HOST, null));
    }

    @Test public void searchPostRejectsCrossSiteAndUnboundedBodies() {
        InputPairing pairing = new InputPairing(new FakeClock(), new Tokens());
        String token = InputPairing.tokenFromAddress(pairing.open(ORIGIN));
        assertTrue(pairing.allowInput(HOST, ORIGIN, token, 30, MIME, false));
        assertFalse(pairing.allowInput(HOST, "https://attacker.example", token, 30, MIME, false));
        assertFalse(pairing.allowInput(HOST, null, token, 30, MIME, false));
        assertFalse(pairing.allowInput("localhost:9978", ORIGIN, token, 30, MIME, false));
        assertFalse(pairing.allowInput(HOST, ORIGIN, "", 30, MIME, false));
        assertFalse(pairing.allowInput(HOST, ORIGIN, token, -1, MIME, false));
        assertFalse(pairing.allowInput(HOST, ORIGIN, token, 0, MIME, false));
        assertFalse(pairing.allowInput(HOST, ORIGIN, token, 2049, MIME, false));
        assertFalse(pairing.allowInput(HOST, ORIGIN, token, 30, MIME, true));
        assertFalse(pairing.allowInput(HOST, ORIGIN, token, 30, "application/json", false));
        assertFalse(pairing.allowInput(HOST, ORIGIN, token, 30, "application/x-www-form-urlencoded-extra", false));
    }

    @Test public void openingNewWindowRevokesOldButOldCloseCannotCloseNew() {
        InputPairing pairing = new InputPairing(new FakeClock(), new Tokens());
        String oldAddress = pairing.open(ORIGIN);
        String address = pairing.open(ORIGIN);
        pairing.close(oldAddress);
        assertFalse(pairing.allowPage(HOST, InputPairing.tokenFromAddress(oldAddress)));
        assertTrue(pairing.allowPage(HOST, InputPairing.tokenFromAddress(address)));
        pairing.close(address);
        assertFalse(pairing.allowPage(HOST, InputPairing.tokenFromAddress(address)));
    }

    @Test public void monotonicTtlAndPerWindowRateLimitAreEnforced() {
        FakeClock clock = new FakeClock();
        InputPairing pairing = new InputPairing(clock, new Tokens());
        String token = InputPairing.tokenFromAddress(pairing.open(ORIGIN));
        for (int i = 0; i < 20; i++) assertTrue(pairing.allowInput(HOST, ORIGIN, token, 30, MIME, false));
        assertFalse(pairing.allowInput(HOST, ORIGIN, token, 30, MIME, false));
        clock.now += 60_000;
        assertTrue(pairing.allowInput(HOST, ORIGIN, token, 30, MIME, false));
        clock.now = 1000 + InputPairing.TTL_MILLIS;
        assertFalse(pairing.allowPage(HOST, token));
        assertFalse(pairing.allowInput(HOST, ORIGIN, token, 30, MIME, false));
    }

    @Test public void pairingCannotBeCreatedForAnArbitraryPageAndTextIsBounded() {
        InputPairing pairing = new InputPairing(new FakeClock(), new Tokens());
        for (String origin : new String[]{"https://192.168.1.20:9978", ORIGIN + "/file", ORIGIN + "?x=1", "http://name:secret@192.168.1.20:9978"}) {
            assertEquals("", pairing.open(origin));
        }
        assertEquals("流浪 地球", InputPairing.normalizeText(" \n流浪\t地球  "));
        assertEquals("", InputPairing.normalizeText("片名\u0000secret"));
        assertEquals("", InputPairing.normalizeText(new String(new char[161]).replace('\0', '片')));
        assertEquals("", InputPairing.normalizeText(null));
        assertEquals("", InputPairing.tokenFromAddress(ORIGIN + "/?pair=bad&other=1"));
    }
}
