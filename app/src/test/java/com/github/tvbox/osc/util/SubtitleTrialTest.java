package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SubtitleTrialTest {
    @Test
    public void metadataSuccessKeepsOriginalUntilTargetReallyStarts() {
        SubtitleTrial<String> trial = new SubtitleTrial<>();
        assertTrue(trial.begin("A:original-url@42000", "B:episode-1"));
        assertTrue(trial.isPending());
        assertFalse(trial.confirmStarted("A:episode-1"));
        assertTrue(trial.isPending());
        assertEquals("A:original-url@42000", trial.rollback("B:episode-1"));
        assertFalse(trial.isPending());
    }

    @Test
    public void confirmedTargetDoesNotLaterRollbackToOldSource() {
        SubtitleTrial<String> trial = new SubtitleTrial<>();
        trial.begin("A", "B");
        assertTrue(trial.confirmStarted("B"));
        assertNull(trial.rollback("B"));
    }

    @Test
    public void staleErrorsAndNestedTrialsDoNotReplaceOriginal() {
        SubtitleTrial<String> trial = new SubtitleTrial<>();
        trial.begin("A", "B");
        assertFalse(trial.begin("B", "C"));
        assertNull(trial.rollback("C"));
        assertTrue(trial.isPending());
        assertEquals("A", trial.rollback("B"));
        assertNull(trial.rollback("B"));
    }

    @Test
    public void choosingAnotherSourceOrEpisodeInvalidatesOldTrial() {
        SubtitleTrial<String> trial = new SubtitleTrial<>();
        trial.begin("A", "B:episode-1");
        long firstTicket = trial.generation();
        assertTrue(trial.invalidateForPlayback("C:episode-1"));
        assertNull(trial.rollback("B:episode-1", firstTicket));

        trial.begin("C", "B:episode-1");
        long secondTicket = trial.generation();
        assertTrue(trial.invalidateForPlayback("B:episode-2"));
        assertNull(trial.rollback("B:episode-1", secondTicket));
        assertFalse(trial.isPending());
    }

    @Test
    public void oldBTicketCannotAffectNewBTrialAfterAbaSelection() {
        SubtitleTrial<String> trial = new SubtitleTrial<>();
        trial.begin("A", "B");
        long oldTicket = trial.generation();
        trial.invalidateForPlayback("C");
        trial.begin("C", "B");
        long newTicket = trial.generation();
        assertNull(trial.rollback("B", oldTicket));
        assertFalse(trial.confirmStarted("B", oldTicket));
        assertTrue(trial.isPending());
        assertEquals("C", trial.rollback("B", newTicket));
    }

    @Test
    public void queuedRestoreTicketExpiresOnExplicitCancellation() {
        SubtitleTrial<String> trial = new SubtitleTrial<>();
        trial.begin("A", "B");
        assertEquals("A", trial.rollback("B", trial.generation()));
        long queuedRestoreTicket = trial.generation();
        trial.clear();
        assertFalse(trial.isGenerationCurrent(queuedRestoreTicket));
    }

    @Test
    public void snapshotUsesCurrentCursorAfterUserRewinds() {
        assertEquals(12000L, SubtitleTrial.snapshotPosition(12000L, 42000L));
        assertEquals(0L, SubtitleTrial.snapshotPosition(0L, 42000L));
        assertEquals(42000L, SubtitleTrial.snapshotPosition(-1L, 42000L));
    }
}
