package dev.dove.agent.hooks;

import dev.dove.agent.util.AgentLog;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Two Windows settings the old shared agent's Fabric mode applied, kept here so they can be
 * measured: a 1 ms system timer (so {@code Thread.sleep} in the frame limiter wakes on time instead
 * of up to 15.6 ms late) and an above-normal priority for the thread that renders (Minecraft's
 * main thread, which is the one running {@code premain}).
 */
public final class WindowsRuntime {

    /** THREAD_PRIORITY_ABOVE_NORMAL in winbase.h. */
    static final int THREAD_PRIORITY_ABOVE_NORMAL = 1;

    private WindowsRuntime() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /** Asks Windows for a 1 ms timer for as long as the game runs. */
    public static boolean raiseTimerResolution() throws Throwable {
        MethodHandle timeBeginPeriod = function("winmm", "timeBeginPeriod", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        return (int) timeBeginPeriod.invokeExact(1) == 0; // TIMERR_NOERROR
    }

    /** Raises the calling thread to above-normal priority. */
    public static boolean raiseThreadPriority() throws Throwable {
        MethodHandle current = function("kernel32", "GetCurrentThread", FunctionDescriptor.of(ValueLayout.ADDRESS));
        MethodHandle set = function("kernel32", "SetThreadPriority", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MemorySegment thread = (MemorySegment) current.invokeExact();
        return (int) set.invokeExact(thread, THREAD_PRIORITY_ABOVE_NORMAL) != 0;
    }

    static int threadPriority() throws Throwable {
        MethodHandle current = function("kernel32", "GetCurrentThread", FunctionDescriptor.of(ValueLayout.ADDRESS));
        MethodHandle get = function("kernel32", "GetThreadPriority", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        MemorySegment thread = (MemorySegment) current.invokeExact();
        return (int) get.invokeExact(thread);
    }

    /** Runs {@code fix}, logging what happened; a failure only costs the setting. */
    public static void apply(String what, Fix fix) {
        try {
            if (fix.run()) {
                AgentLog.info("Windows: " + what);
            } else {
                AgentLog.warn("Windows refused " + what);
            }
        } catch (Throwable t) {
            AgentLog.warn("Cannot set " + what + ": " + t);
        }
    }

    private static MethodHandle function(String library, String name, FunctionDescriptor descriptor) {
        Linker linker = Linker.nativeLinker();
        MemorySegment address = SymbolLookup.libraryLookup(library, java.lang.foreign.Arena.global()).find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError(library + "!" + name));
        return linker.downcallHandle(address, descriptor);
    }

    @FunctionalInterface
    public interface Fix {
        boolean run() throws Throwable;
    }
}
