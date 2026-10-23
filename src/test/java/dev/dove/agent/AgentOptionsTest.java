package dev.dove.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOptionsTest {

    @Test
    void parsesLauncherArguments() {
        AgentOptions options = AgentOptions.parse("log=C:\\Users\\me\\.minecraft\\logs\\dove-agent.log");

        assertEquals(Path.of("C:\\Users\\me\\.minecraft\\logs\\dove-agent.log"), options.logFile());
    }

    @Test
    void keepsCommasInsidePaths() {
        AgentOptions options = AgentOptions.parse("log=/tmp/a,b/agent.log,debug=true");

        assertEquals(Path.of("/tmp/a,b/agent.log"), options.logFile());
        assertTrue(options.debug());
    }

    @Test
    void defaultsWithoutArguments() {
        AgentOptions options = AgentOptions.parse(null);

        assertNull(options.logFile());
        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "dove-agent"), options.cacheDir());
        assertTrue(options.fastStartup());
        // Measured without a gain (see the README), so off unless asked for.
        assertFalse(options.timer());
        assertFalse(options.renderPriority());
        assertEquals(dev.dove.agent.hooks.HybridCpu.Mode.AUTO, options.hybridCpu());
    }

    @Test
    void switchesTurnOnAndOff() {
        AgentOptions options = AgentOptions.parse("fastStartup=false,timer=true,renderPriority=true");

        assertFalse(options.fastStartup());
        assertTrue(options.timer());
        assertTrue(options.renderPriority());
    }
}
