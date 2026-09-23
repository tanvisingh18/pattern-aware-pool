package com.college.pap.monitoring;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates monitoring threads inside a named {@link ThreadGroup}
 * (PatternAnalyzer / BackupPreWarmer / Validator workers).
 */
public final class MonitoringThreadFactory implements ThreadFactory {
    private final ThreadGroup group;
    private final AtomicInteger seq = new AtomicInteger();
    private final String prefix;

    public MonitoringThreadFactory(String groupName, String prefix) {
        this.group = new ThreadGroup(groupName);
        this.prefix = prefix;
    }

    public ThreadGroup threadGroup() {
        return group;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(group, r, prefix + "-" + seq.incrementAndGet(), 0);
        t.setDaemon(true);
        return t;
    }
}
