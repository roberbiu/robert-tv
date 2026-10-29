package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackActionGuardTest {
    @Test
    public void automaticPreviewCannotRequestAccountUi() {
        PlaybackActionGuard guard = new PlaybackActionGuard();
        PlaybackActionGuard.Ticket preview = guard.begin("source-a/work-1/episode-1", false);
        assertTrue(guard.isCurrent(preview));
        assertFalse(guard.canPrompt(preview, true));
        PlaybackActionGuard.Ticket selected = guard.begin("source-a/work-1/episode-1", true);
        assertTrue(guard.canPrompt(selected, true));
        assertFalse(guard.canPrompt(preview, true));
    }

    @Test
    public void sameIdentityDoesNotReviveAbandonedAction() {
        PlaybackActionGuard guard = new PlaybackActionGuard();
        PlaybackActionGuard.Ticket first = guard.begin("source-a/work-1/episode-1", true);
        guard.begin("source-b/work-2/episode-1", true);
        PlaybackActionGuard.Ticket returned = guard.begin("source-a/work-1/episode-1", true);
        assertTrue(first.matches("source-a/work-1/episode-1"));
        assertTrue(returned.matches("source-a/work-1/episode-1"));
        assertFalse(guard.canPrompt(first, true));
        assertTrue(guard.canPrompt(returned, true));
    }

    @Test
    public void cancelledOrHiddenOwnerCannotPrompt() {
        PlaybackActionGuard guard = new PlaybackActionGuard();
        PlaybackActionGuard.Ticket ticket = guard.begin("source-a/work-1/episode-1", true);
        assertFalse(guard.canPrompt(ticket, false));
        guard.invalidate();
        assertFalse(guard.isCurrent(ticket));
        assertFalse(guard.canPrompt(ticket, true));
        assertFalse(guard.canPrompt(null, true));
    }

    @Test
    public void missingPlaybackOwnerFailsClosed() {
        PlaybackActionGuard guard = new PlaybackActionGuard();
        assertFalse(guard.canPrompt(guard.begin(null, true), true));
        assertFalse(guard.canPrompt(guard.begin("  ", true), true));
    }

    @Test
    public void lateDialogCloseCannotCancelNewPlayback() {
        PlaybackActionGuard guard = new PlaybackActionGuard();
        PlaybackActionGuard.Ticket oldDialog = guard.begin("source-a/work-1/episode-1", true);
        PlaybackActionGuard.Ticket selected = guard.begin("source-a/work-1/episode-2", true);
        assertFalse(guard.invalidate(oldDialog));
        assertTrue(guard.canPrompt(selected, true));
        assertTrue(guard.invalidate(selected));
        assertFalse(guard.canPrompt(selected, true));
    }
}
