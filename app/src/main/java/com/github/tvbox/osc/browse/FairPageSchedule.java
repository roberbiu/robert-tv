package com.github.tvbox.osc.browse;

import java.util.ArrayList;
import java.util.List;

/** 每次先推进轮转指针；某叶子一页已满也不会永久占据后续页面。 */
public final class FairPageSchedule {
    private int next;

    public List<Integer> take(List<Boolean> exhausted, int budget) {
        List<Integer> indices = new ArrayList<>();
        int size = exhausted.size();
        if (size == 0 || budget <= 0) return indices;
        int checked = 0;
        while (checked < size && indices.size() < budget) {
            int index = next % size;
            next = (index + 1) % size;
            checked++;
            if (!exhausted.get(index)) indices.add(index);
        }
        return indices;
    }

    public void reset() {
        next = 0;
    }

    public FairPageSchedule copy() {
        FairPageSchedule copy = new FairPageSchedule();
        copy.next = next;
        return copy;
    }

    public void startAt(int index) {
        next = Math.max(0, index);
    }
}
