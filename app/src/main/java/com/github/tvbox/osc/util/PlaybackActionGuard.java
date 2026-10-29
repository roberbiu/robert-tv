package com.github.tvbox.osc.util;

/** UI线程使用：将账号/文件选择交互绑定到当前播放动作，避免预览及晚到回调弹窗。 */
public final class PlaybackActionGuard {
    private long generation;
    private Ticket current;

    public Ticket begin(String identity, boolean userInitiated) {
        current = new Ticket(++generation, identity, userInitiated);
        return current;
    }

    public void invalidate() {
        generation++;
        current = null;
    }

    public boolean invalidate(Ticket ticket) {
        if (!isCurrent(ticket)) return false;
        invalidate();
        return true;
    }

    public boolean isCurrent(Ticket ticket) {
        return ticket != null && current == ticket && ticket.generation == generation;
    }

    public boolean canPrompt(Ticket ticket, boolean visible) {
        return visible && isCurrent(ticket) && ticket.userInitiated
                && ticket.identity != null && !ticket.identity.trim().isEmpty();
    }

    public static final class Ticket {
        private final long generation;
        private final String identity;
        private final boolean userInitiated;

        private Ticket(long generation, String identity, boolean userInitiated) {
            this.generation = generation;
            this.identity = identity;
            this.userInitiated = userInitiated;
        }

        public long generation() {
            return generation;
        }

        public boolean matches(String value) {
            return identity == null ? value == null : identity.equals(value);
        }
    }
}
