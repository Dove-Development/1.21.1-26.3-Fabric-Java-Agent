package dev.dove.agent.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsRuntimeTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void raisesTheTimerResolution() throws Throwable {
        assertTrue(WindowsRuntime.raiseTimerResolution());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void raisesOnlyTheCallingThread() throws Throwable {
        AtomicInteger other = new AtomicInteger();
        Thread thread = new Thread(() -> {
            try {
                assertTrue(WindowsRuntime.raiseThreadPriority());
                other.set(WindowsRuntime.threadPriority());
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        thread.start();
        thread.join();

        assertEquals(WindowsRuntime.THREAD_PRIORITY_ABOVE_NORMAL, other.get());
        assertEquals(0, WindowsRuntime.threadPriority()); // THREAD_PRIORITY_NORMAL, untouched
    }

    @Test
    void aFailureOnlyCostsTheSetting() {
        WindowsRuntime.apply("nothing", () -> {
            throw new UnsatisfiedLinkError("no such library");
        });
    }
}
