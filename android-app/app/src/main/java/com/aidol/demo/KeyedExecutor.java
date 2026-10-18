package com.aidol.demo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** FIFO per resource, with independent resources sharing a bounded worker pool. */
final class KeyedExecutor {
    private final ExecutorService pool;
    private final Map<String, ArrayDeque<Runnable>> queues = new HashMap<>();
    private boolean closed;

    KeyedExecutor(int threads) { pool = Executors.newFixedThreadPool(threads); }

    synchronized void execute(String key, Runnable action) {
        if (closed) throw new RejectedExecutionException("Executor is closed");
        ArrayDeque<Runnable> queue = queues.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        queue.add(action);
        if (queue.size() == 1) schedule(key, action);
    }

    private void schedule(String key, Runnable action) {
        pool.execute(() -> {
            try { action.run(); }
            finally { completed(key); }
        });
    }

    private synchronized void completed(String key) {
        ArrayDeque<Runnable> queue = queues.get(key);
        if (queue == null) return;
        queue.remove();
        if (closed || queue.isEmpty()) queues.remove(key);
        else schedule(key, queue.peek());
    }

    synchronized void shutdownNow() {
        closed = true;
        queues.clear();
        pool.shutdownNow();
    }
}
