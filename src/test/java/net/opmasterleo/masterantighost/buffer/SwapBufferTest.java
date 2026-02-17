package net.opmasterleo.masterantighost.buffer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SwapBuffer rolling window.
 *
 * <p>These tests validate the core buffer logic without NMS dependencies.
 * SwapBuffer uses only Java concurrent collections and can be tested in isolation.</p>
 */
@DisplayName("SwapBuffer Tests")
class SwapBufferTest {

    private SwapBuffer buffer;
    private UUID testPlayer;

    @BeforeEach
    void setup() {
        buffer = new SwapBuffer(2); // 2-tick window
        testPlayer = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Recording & Querying")
    class RecordingTests {

        @Test
        @DisplayName("No entries returns false")
        void testEmptyBuffer() {
            assertFalse(buffer.hasRecentTotemActivity(testPlayer, 100));
        }

        @Test
        @DisplayName("Totem swap within window returns true")
        void testTotemSwapWithinWindow() {
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            assertTrue(buffer.hasRecentTotemActivity(testPlayer, 101));
        }

        @Test
        @DisplayName("Totem swap outside window returns false")
        void testTotemSwapOutsideWindow() {
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            assertFalse(buffer.hasRecentTotemActivity(testPlayer, 103)); // 3 ticks later, window=2
        }

        @Test
        @DisplayName("Non-totem swap returns false for totem query")
        void testNonTotemSwap() {
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.OFFHAND_SWAP, false);
            assertFalse(buffer.hasRecentTotemActivity(testPlayer, 101));
        }

        @Test
        @DisplayName("Non-totem swap returns true for any-swap query")
        void testAnySwapActivity() {
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.WINDOW_CLICK, false);
            assertTrue(buffer.hasAnyRecentSwapActivity(testPlayer, 101));
        }

        @Test
        @DisplayName("Multiple entries, latest within window")
        void testMultipleEntries() {
            buffer.recordSwap(testPlayer, 95, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.NUMBER_KEY, true);
            assertTrue(buffer.hasRecentTotemActivity(testPlayer, 101)); // 100 is within window
        }

        @Test
        @DisplayName("Mixed player entries don't interfere")
        void testPlayerIsolation() {
            UUID otherPlayer = UUID.randomUUID();
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            buffer.recordSwap(otherPlayer, 100, SwapBuffer.SwapType.OFFHAND_SWAP, false);

            assertTrue(buffer.hasRecentTotemActivity(testPlayer, 101));
            assertFalse(buffer.hasRecentTotemActivity(otherPlayer, 101));
        }

        @Test
        @DisplayName("Boundary: swap at exact window edge")
        void testExactBoundary() {
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            // Window is 2 ticks, so tick 102 should be the boundary
            assertTrue(buffer.hasRecentTotemActivity(testPlayer, 102));  // exactly at window
            assertFalse(buffer.hasRecentTotemActivity(testPlayer, 103)); // one past window
        }
    }

    @Nested
    @DisplayName("Cleanup")
    class CleanupTests {

        @Test
        @DisplayName("Cleanup removes expired entries")
        void testCleanup() {
            buffer.recordSwap(testPlayer, 50, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            buffer.cleanupExpired(100); // 50 ticks later, well past expiry
            assertFalse(buffer.hasRecentTotemActivity(testPlayer, 100));
        }

        @Test
        @DisplayName("Cleanup preserves recent entries")
        void testCleanupPreservesRecent() {
            buffer.recordSwap(testPlayer, 99, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            buffer.cleanupExpired(100); // Recently added, should survive
            assertTrue(buffer.hasRecentTotemActivity(testPlayer, 100));
        }

        @Test
        @DisplayName("clearPlayer removes all entries for player")
        void testClearPlayer() {
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            buffer.clearPlayer(testPlayer);
            assertFalse(buffer.hasRecentTotemActivity(testPlayer, 100));
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ConcurrencyTests {

        @Test
        @DisplayName("Concurrent writes from multiple threads don't corrupt state")
        void testConcurrentWrites() throws InterruptedException {
            int threadCount = 10;
            int writesPerThread = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < writesPerThread; i++) {
                            UUID player = UUID.randomUUID();
                            long tick = threadId * writesPerThread + i;
                            buffer.recordSwap(player, tick, SwapBuffer.SwapType.OFFHAND_SWAP, true);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // No exception means concurrent writes are safe.
            // Verify buffer tracked at least some players.
            assertTrue(buffer.getTrackedPlayerCount() > 0);
        }

        @Test
        @DisplayName("Concurrent read and write don't throw exceptions")
        void testConcurrentReadWrite() throws InterruptedException {
            int iterations = 10000;
            CountDownLatch latch = new CountDownLatch(2);

            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        buffer.recordSwap(testPlayer, i, SwapBuffer.SwapType.OFFHAND_SWAP, (i % 2 == 0));
                    }
                } finally {
                    latch.countDown();
                }
            });

            Thread reader = new Thread(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        buffer.hasRecentTotemActivity(testPlayer, i);
                        buffer.hasAnyRecentSwapActivity(testPlayer, i);
                    }
                } finally {
                    latch.countDown();
                }
            });

            writer.start();
            reader.start();
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("Window Ticks Update")
    class WindowUpdateTests {

        @Test
        @DisplayName("Runtime window change affects queries immediately")
        void testWindowChange() {
            buffer.recordSwap(testPlayer, 100, SwapBuffer.SwapType.OFFHAND_SWAP, true);

            // Default window: 2 ticks
            assertTrue(buffer.hasRecentTotemActivity(testPlayer, 102));  // within 2-tick window

            // Shrink window to 1 tick
            buffer.setWindowTicks(1);
            assertFalse(buffer.hasRecentTotemActivity(testPlayer, 102)); // now outside 1-tick window
            assertTrue(buffer.hasRecentTotemActivity(testPlayer, 101));  // still within 1-tick window
        }
    }
}
