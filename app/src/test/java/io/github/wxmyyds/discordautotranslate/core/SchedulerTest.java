package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link Scheduler} 的调度语义：去重、并发限制、过期结果丢弃、可重试错误重试。
 *
 * <p>这些是需求 §5.4 与 §风险 3 要求的行为，也是阶段 2 的验收点。</p>
 *
 * <p>回写必须经过 {@link Scheduler.MainThread}。生产环境没有 Handler 时会直接丢弃回调，
 * 因此单测显式安装直投 dispatcher，不能依赖已删除的“无 Handler 时同步执行”兜底。</p>
 */
public class SchedulerTest {

    @Before
    public void installTestDispatcher() {
        Scheduler.MainThread.clear();
        Scheduler.MainThread.setDirectDispatcherForTests();
    }

    @After
    public void clearTestDispatcher() {
        Scheduler.MainThread.clear();
    }

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
    public void transientErrorIsAttemptedOnlyOnce() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine alwaysTimeout = text -> {
                calls.incrementAndGet();
                throw new TranslationException(TranslationException.Kind.TIMEOUT, "timeout");
            };
            Collector c = new Collector();
            s.submit("1", "h1", "hello", alwaysTimeout, ALWAYS, c);
            c.await();
            assertEquals(1, calls.get());
            assertTrue(c.error instanceof TranslationException);
            assertEquals(1, s.failedCount());
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
            assertEquals(1, calls.get());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void transientFailureDoesNotRetryToSuccess() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine flaky = text -> {
                calls.incrementAndGet();
                throw new TranslationException(TranslationException.Kind.TIMEOUT, "timeout");
            };
            Collector c = new Collector();
            s.submit("1", "h1", "hello", flaky, ALWAYS, c);
            c.await();
            assertEquals(1, calls.get());
            assertNull(c.translated);
            assertTrue(c.error instanceof TranslationException);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void rateLimitErrorHasReadableMessage() {
        TranslationException e = new TranslationException(
                TranslationException.Kind.RATE_LIMIT, "限流", 429, null);
        assertTrue(e.userMessage().contains("限流"));
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
    public void concurrencyLimitCanBeLoweredThenRestored() throws Exception {
        Scheduler s = new Scheduler();
        try {
            // 持有初始许可时连续调低再调高，旧实现会遗失可用 permit。
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            Scheduler.Engine slow = text -> {
                int now = concurrent.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                release.await(5, TimeUnit.SECONDS);
                concurrent.decrementAndGet();
                return "t";
            };
            for (int i = 0; i < 4; i++) {
                s.submit("first" + i, "h" + i, "text", slow, ALWAYS, new Collector());
            }
            Thread.sleep(200);
            s.setMaxConcurrency(1);
            s.setMaxConcurrency(4);
            release.countDown();
            Thread.sleep(400);

            CountDownLatch secondRelease = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(4);
            AtomicInteger secondConcurrent = new AtomicInteger();
            Scheduler.Engine fourWay = text -> {
                secondConcurrent.incrementAndGet();
                entered.countDown();
                secondRelease.await(5, TimeUnit.SECONDS);
                secondConcurrent.decrementAndGet();
                return "t";
            };
            for (int i = 0; i < 4; i++) {
                s.submit("second" + i, "s" + i, "text", fourWay, ALWAYS, new Collector());
            }
            assertTrue("restored concurrency should admit four tasks",
                    entered.await(3, TimeUnit.SECONDS));
            secondRelease.countDown();
        } finally {
            s.shutdown();
        }
    }

    /**
     * 关闭之后的实例不再交付任何回调：旧代次的回调不得再触碰新代次的状态。
     */
    @Test
    public void submitAfterShutdownDeliversNothing() throws Exception {
        Scheduler s = new Scheduler();
        s.shutdown();
        Collector c = new Collector();
        s.submit("1", "h1", "hello", new CountingEngine("t"), ALWAYS, c);
        assertFalse("retired scheduler must not call back",
                c.latch.await(500, TimeUnit.MILLISECONDS));
    }

    /** 线程池已满时提交失败必须回报错误，不得静默丢弃。 */
    @Test
    public void rejectedSubmitReportsFailure() throws Exception {
        Scheduler s = new Scheduler();
        try {
            // POOL_MAX = 8；用阻塞任务把 8 个线程全部占满。
            CountDownLatch release = new CountDownLatch(1);
            Scheduler.Engine blocking = text -> {
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            for (int i = 0; i < 8; i++) {
                s.submit("busy" + i, "h" + i, "text", blocking, ALWAYS, new Collector());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!s.stats().contains("inFlight=8") && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue("pool should be saturated: " + s.stats(), s.stats().contains("inFlight=8"));

            Collector rejected = new Collector();
            s.submit("overflow", "h", "text", new CountingEngine("t"), ALWAYS, rejected);
            rejected.await();
            assertNotNull("submission failure must be reported", rejected.error);

            release.countDown();
        } finally {
            s.shutdown();
        }
    }

    /** 同一条消息的新内容不会被旧内容的在途任务去重掉。 */
    @Test
    public void sameMessageWithNewContentIsNotDeduped() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine blocking = text -> {
                calls.incrementAndGet();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            s.submit("1", "old-hash", "old text", blocking, ALWAYS, new Collector());
            Collector edited = new Collector();
            s.submit("1", "new-hash", "new text", blocking, ALWAYS, edited);
            // 两个任务都要真正进入引擎后，这里的断言才有意义。
            //
            // 旧写法直接 release.countDown() 然后等 edited 回调：两个引擎调用
            // 是否都已发生取决于线程调度。若线程池先跑完 edited 那个任务的首轮
            // 尝试（引擎已被调用）而旧任务还没开始执行引擎，calls 只到 1，
            // 断言就会偶发失败（真机上出现过同一个 commit 两次 CI 结果相反）。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (calls.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("both tasks must reach the engine", 2, calls.get());
            release.countDown();
            edited.await();
            assertEquals("edited content must reach the engine", 2, calls.get());
        } finally {
            s.shutdown();
        }
    }

    /** 等待并发许可期间内容已失效时，不得再发出请求。 */
    @Test
    public void staleWhileWaitingForPermitDoesNotCallEngine() throws Exception {
        Scheduler s = new Scheduler();
        try {
            s.setMaxConcurrency(1);
            CountDownLatch release = new CountDownLatch(1);
            // 第一个任务真正进入引擎后才说明它已持有唯一许可。
            CountDownLatch holding = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine engine = text -> {
                calls.incrementAndGet();
                holding.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            s.submit("hold", "h0", "text", engine, ALWAYS, new Collector());
            // 注意：不可用 stats() 的 inFlight 判断。submit() 在 permits.acquire()
            // 之前就写入 inFlight，此时任务可能还没拿到许可；据此放行会让第二个
            // 任务抢先拿到许可并调用引擎，造成用例随机失败。
            assertTrue("first task must reach the engine and hold the permit",
                    holding.await(5, TimeUnit.SECONDS));

            Collector stale = new Collector();
            s.submit("1", "h1", "text", engine, (gen, hash) -> false, stale);
            release.countDown();
            stale.await();
            assertEquals("stale content must not produce a request", 1, calls.get());
            assertFalse(stale.accepted);
        } finally {
            s.shutdown();
        }
    }

    /** 热重载要求线程真正停止后才返回，因此 shutdownAndAwait 必须等待线程池终止。 */
    @Test
    public void shutdownAndAwaitWaitsForTermination() throws Exception {
        Scheduler s = new Scheduler();
        AtomicInteger calls = new AtomicInteger();
        Scheduler.Engine slow = text -> {
            calls.incrementAndGet();
            Thread.sleep(300);
            return "t";
        };
        s.submit("1", "h1", "text", slow, ALWAYS, new Collector());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!s.stats().contains("inFlight=1") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("task should be running: " + s.stats(), s.stats().contains("inFlight=1"));
        assertTrue("pool must terminate within the timeout", s.shutdownAndAwait(5000));
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
