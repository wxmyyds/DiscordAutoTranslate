package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link Scheduler} 的调度语义：去重、并发限制、过期结果丢弃、可重试错误重试。
 *
 * <p>这些是需求 §5.4 与 §风险 3 的核心行为，也是阶段 2 的验收点。</p>
 *
 * <p>注意：{@link Scheduler.MainThread} 在未安装 Handler 时直接在当前线程执行回调，
 * 因此单测中回调是同步可见的（见 {@code MainThread.post} 的兜底分支）。</p>
 */
public class SchedulerTest {

    /** 恒成功的引擎，记录调用次数。 */
    private static final class CountingEngine implements Scheduler.Engine {
        final AtomicInteger calls = new AtomicInteger();
        final String result;

        CountingEngine(String result) {
            this.result = result;
        }

        @Override
        public String translate(String text) {
            calls.incrementAndGet();
            return result;
        }
    }

    /** 永真接受判定。 */
    private static final Scheduler.Acceptance ALWAYS = (gen, hash) -> true;

    /** 收集回调结果。 */
    private static final class Collector implements Scheduler.Callback {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean accepted;
        volatile String translated;
        volatile Throwable error;

        @Override
        public void onResult(String messageId, boolean accepted, String translated, Throwable error) {
            this.accepted = accepted;
            this.translated = translated;
            this.error = error;
            latch.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("callback not invoked in time", latch.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void successPathDeliversTranslation() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("译文");
            Collector c = new Collector();
            s.submit("1", "h1", "hello", engine, ALWAYS, c);
            c.await();
            assertTrue(c.accepted);
            assertEquals("译文", c.translated);
            assertNull(c.error);
            assertEquals(1, engine.calls.get());
            assertEquals(1, s.completedCount());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void duplicateInFlightIsDeduped() throws Exception {
        Scheduler s = new Scheduler();
        try {
            // 阻塞引擎，保证第二次提交时首个任务仍在途
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine blocking = text -> {
                calls.incrementAndGet();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            Collector c = new Collector();
            s.submit("1", "h1", "hello", blocking, ALWAYS, c);
            // 同 ID 再提交两次，应被去重
            s.submit("1", "h1", "hello", blocking, ALWAYS, new Collector());
            s.submit("1", "h1", "hello", blocking, ALWAYS, new Collector());
            release.countDown();
            c.await();
            assertEquals("duplicate submits must not reach engine", 1, calls.get());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void differentMessageIdsAreNotDeduped() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("t");
            Collector c1 = new Collector();
            Collector c2 = new Collector();
            s.submit("1", "h1", "a", engine, ALWAYS, c1);
            s.submit("2", "h2", "b", engine, ALWAYS, c2);
            c1.await();
            c2.await();
            assertEquals(2, engine.calls.get());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void staleResultIsDiscardedWhenAcceptanceRejects() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("译文");
            Collector c = new Collector();
            // 模拟“消息已被编辑 / 开关关闭 / 已热重载”：
            // 接受判定在任务执行时已失效。
            Scheduler.Acceptance reject = (gen, hash) -> false;
            s.submit("1", "h1", "hello", engine, reject, c);
            c.await();
            assertFalse("stale result must not be accepted", c.accepted);
            assertEquals(0, s.completedCount());
            assertEquals(1, s.discardedCount());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void staleResultIsNotEvenRequested() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("译文");
            Collector c = new Collector();
            // 提交前即已失效：应完全不发请求，避免浪费配额（需求 §5.4）
            s.submit("1", "h1", "hello", engine, (gen, hash) -> false, c);
            c.await();
            assertEquals("engine must not be called for invalidated content", 0, engine.calls.get());
            assertFalse(c.accepted);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void errorIsClassifiedAndReported() throws Exception {
        Scheduler s = new Scheduler();
        try {
            Scheduler.Engine failing = text -> {
                throw new TranslationException(TranslationException.Kind.NETWORK, "boom");
            };
            Collector c = new Collector();
            s.submit("1", "h1", "hello", failing, ALWAYS, c);
            c.await();
            assertTrue(c.accepted);
            assertNull(c.translated);
            assertTrue(c.error instanceof TranslationException);
            assertEquals(TranslationException.Kind.NETWORK, ((TranslationException) c.error).kind());
            assertEquals(1, s.failedCount());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void retryableErrorIsRetriedUpToMaxAttempts() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine alwaysTimeout = text -> {
                calls.incrementAndGet();
                // TIMEOUT 属可重试错误
                throw new TranslationException(TranslationException.Kind.TIMEOUT, "timeout");
            };
            Collector c = new Collector();
            s.submit("1", "h1", "hello", alwaysTimeout, ALWAYS, c);
            c.await();
            // MAX_ATTEMPTS = 3
            assertEquals(3, calls.get());
            assertEquals(2, s.retriedCount());
            assertTrue(c.error instanceof TranslationException);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void nonRetryableErrorIsNotRetried() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine authFail = text -> {
                calls.incrementAndGet();
                // 401 归为 HTTP 且不可重试
                throw new TranslationException(TranslationException.Kind.HTTP,
                        "unauthorized", 401, null);
            };
            Collector c = new Collector();
            s.submit("1", "h1", "hello", authFail, ALWAYS, c);
            c.await();
            assertEquals("4xx must not be retried", 1, calls.get());
            assertEquals(0, s.retriedCount());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void retrySucceedsOnSecondAttempt() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine flaky = text -> {
                if (calls.incrementAndGet() == 1) {
                    throw new TranslationException(TranslationException.Kind.TIMEOUT, "timeout");
                }
                return "ok";
            };
            Collector c = new Collector();
            s.submit("1", "h1", "hello", flaky, ALWAYS, c);
            c.await();
            assertEquals(2, calls.get());
            assertEquals("ok", c.translated);
            assertNull(c.error);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void serverError5xxIsRetryable() {
        TranslationException e500 = new TranslationException(
                TranslationException.Kind.HTTP, "server", 500, null);
        TranslationException e503 = new TranslationException(
                TranslationException.Kind.HTTP, "unavailable", 503, null);
        TranslationException e400 = new TranslationException(
                TranslationException.Kind.HTTP, "bad request", 400, null);
        assertTrue(e500.isRetryable());
        assertTrue(e503.isRetryable());
        assertFalse(e400.isRetryable());
    }

    @Test
    public void rateLimitIsRetryableAndHasReadableMessage() {
        TranslationException e = new TranslationException(
                TranslationException.Kind.RATE_LIMIT, "限流", 429, null);
        assertTrue(e.isRetryable());
        assertTrue(e.userMessage().contains("限流"));
    }

    @Test
    public void configErrorIsNeverRetryable() {
        TranslationException e = new TranslationException(
                TranslationException.Kind.CONFIG, "missing url");
        assertFalse(e.isRetryable());
        assertTrue(e.userMessage().startsWith("配置错误"));
    }

    @Test
    public void contentHashIsStableAndConfigSensitive() {
        String a = Scheduler.contentHash("hello", "cfg1");
        String b = Scheduler.contentHash("hello", "cfg1");
        String c = Scheduler.contentHash("hello", "cfg2");
        String d = Scheduler.contentHash("hello!", "cfg1");

        assertEquals("same input must hash identically", a, b);
        assertNotEquals("config change must change hash", a, c);
        assertNotEquals("content change must change hash", a, d);
        assertEquals(64, a.length()); // SHA-256 hex
    }

    @Test
    public void concurrencyLimitIsRespected() throws Exception {
        Scheduler s = new Scheduler();
        try {
            s.setMaxConcurrency(2);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            Scheduler.Engine slow = text -> {
                int now = concurrent.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                release.await(5, TimeUnit.SECONDS);
                concurrent.decrementAndGet();
                return "t";
            };
            for (int i = 0; i < 6; i++) {
                s.submit("id" + i, "h" + i, "text", slow, ALWAYS, new Collector());
            }
            // 给任务时间进入闸门
            Thread.sleep(400);
            release.countDown();
            Thread.sleep(600);
            assertTrue("peak concurrency " + peak.get() + " exceeded limit 2",
                    peak.get() <= 2);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void shutdownAfterSubmitDoesNotCrash() {
        Scheduler s = new Scheduler();
        Collector c = new Collector();
        s.submit("1", "h1", "hello", new CountingEngine("t"), ALWAYS, c);
        // 立即关闭：任务可能被中断，回调可能报告取消/失败，但不得抛异常
        s.shutdown();
        assertTrue(s.stats().contains("scheduler["));
    }

    @Test
    public void rejectionCallbackDoesNotReportAccepted() throws Exception {
        Scheduler s = new Scheduler();
        try {
            // 通过先关闭线程池模拟提交被拒
            s.shutdown();
            Collector c = new Collector();
            s.submit("1", "h1", "hello", new CountingEngine("t"), ALWAYS, c);
            c.await();
            assertFalse("rejected task must not be accepted", c.accepted);
            assertNotNull(c.error);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void acceptanceSeesRecordedGenerationAndHash() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicReference<Integer> seenGen = new AtomicReference<>();
            AtomicReference<String> seenHash = new AtomicReference<>();
            Scheduler.Acceptance spy = (gen, hash) -> {
                seenGen.set(gen);
                seenHash.set(hash);
                return true;
            };
            Collector c = new Collector();
            s.submit("1", "abc123", "hello", new CountingEngine("t"), spy, c);
            c.await();
            assertEquals("abc123", seenHash.get());
            assertEquals(StateStore.get().generation(), (int) seenGen.get());
        } finally {
            s.shutdown();
        }
    }
}
