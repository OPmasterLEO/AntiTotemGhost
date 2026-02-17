package net.opmasterleo.masterantighost.combat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the DamageCoalescer.
 *
 * <p>Tests validate thread-safe damage accumulation via AtomicReference CAS.
 * No Bukkit/NMS dependencies — pure Java logic.</p>
 */
@DisplayName("DamageCoalescer Tests")
class DamageCoalescerTest {

    private UUID testPlayer;

    @BeforeEach
    void setup() {
        testPlayer = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Basic Coalescing")
    class BasicTests {

        @Test
        @DisplayName("Initial damage is stored correctly")
        void testInitialDamage() {
            DamageContext ctx = createContext(20.0, 100);
            DamageCoalescer coalescer = new DamageCoalescer(ctx);

            assertEquals(20.0, coalescer.getTotalDamage(), 0.001);
        }

        @Test
        @DisplayName("Additional damage is accumulated")
        void testDamageAccumulation() {
            DamageContext ctx = createContext(20.0, 100);
            DamageCoalescer coalescer = new DamageCoalescer(ctx);

            coalescer.addDamage(15.0, null, 100);
            coalescer.addDamage(10.0, null, 101);

            assertEquals(45.0, coalescer.getTotalDamage(), 0.001);
        }

        @Test
        @DisplayName("Latest damage source is preserved")
        void testLatestSourcePreserved() {
            Object source1 = "source1";
            Object source2 = "source2";

            DamageContext ctx = new DamageContext(testPlayer, 20.0,
                    org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                    null, source1, 100);
            DamageCoalescer coalescer = new DamageCoalescer(ctx);

            coalescer.addDamage(15.0, source2, 101);

            assertEquals(source2, coalescer.getLatestDamageSource());
        }

        @Test
        @DisplayName("Null damage source preserves previous source")
        void testNullSourcePreservesPrevious() {
            Object source1 = "source1";

            DamageContext ctx = new DamageContext(testPlayer, 20.0,
                    org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                    null, source1, 100);
            DamageCoalescer coalescer = new DamageCoalescer(ctx);

            coalescer.addDamage(10.0, null, 101);

            assertEquals(source1, coalescer.getLatestDamageSource());
        }
    }

    @Nested
    @DisplayName("Thread Safety — CAS Correctness")
    class ConcurrencyTests {

        @Test
        @DisplayName("Concurrent addDamage produces correct total")
        void testConcurrentAddDamage() throws InterruptedException {
            DamageContext ctx = createContext(0.0, 100);
            DamageCoalescer coalescer = new DamageCoalescer(ctx);

            int threadCount = 10;
            int addsPerThread = 1000;
            double damagePerAdd = 1.0;
            double expectedTotal = threadCount * addsPerThread * damagePerAdd;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < addsPerThread; i++) {
                            coalescer.addDamage(damagePerAdd, null, 100);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // CAS loop guarantees no lost updates — total must be exact.
            assertEquals(expectedTotal, coalescer.getTotalDamage(), 0.001,
                    "CAS loop must not lose any damage updates");
        }

        @Test
        @DisplayName("Concurrent read and write don't throw exceptions")
        void testConcurrentReadWrite() throws InterruptedException {
            DamageContext ctx = createContext(10.0, 100);
            DamageCoalescer coalescer = new DamageCoalescer(ctx);

            int iterations = 10000;
            CountDownLatch latch = new CountDownLatch(2);

            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        coalescer.addDamage(0.1, null, 100 + i);
                    }
                } finally {
                    latch.countDown();
                }
            });

            Thread reader = new Thread(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        double total = coalescer.getTotalDamage();
                        assertTrue(total >= 10.0, "Total should never decrease");
                        coalescer.getLatestDamageSource();
                        coalescer.getCoalescedDamage();
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

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private DamageContext createContext(double damage, long tick) {
        return new DamageContext(
                testPlayer,
                damage,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                null,
                null,
                tick
        );
    }
}
