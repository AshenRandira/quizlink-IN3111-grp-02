package lk.quizlink.discovery;

import lk.quizlink.common.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryClientDemoTest {

    @Test
    void defaultsToLanBroadcastMode() {
        DiscoveryClientDemo.DemoOptions options = DiscoveryClientDemo.parseArguments(new String[0]);

        assertFalse(options.direct());
        assertEquals(ProtocolConstants.DISCOVERY_PORT, options.discoveryPort());
        assertEquals(UdpDiscoveryClient.DEFAULT_TIMEOUT, options.timeout());
    }

    @Test
    void parsesDirectModeWithDefaultsAndOptionalConfiguration() throws Exception {
        DiscoveryClientDemo.DemoOptions defaults =
                DiscoveryClientDemo.parseArguments(new String[]{"--direct", "127.0.0.1"});
        assertTrue(defaults.direct());
        assertEquals(InetAddress.getByName("127.0.0.1"), defaults.destination());
        assertEquals(ProtocolConstants.DISCOVERY_PORT, defaults.discoveryPort());
        assertEquals(UdpDiscoveryClient.DEFAULT_TIMEOUT, defaults.timeout());

        DiscoveryClientDemo.DemoOptions configured = DiscoveryClientDemo.parseArguments(
                new String[]{"--direct", "127.0.0.1", "5010", "800"});
        assertEquals(5010, configured.discoveryPort());
        assertEquals(Duration.ofMillis(800), configured.timeout());
    }

    @Test
    void rejectsMalformedArgumentsAndConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryClientDemo.parseArguments(null));
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryClientDemo.parseArguments(new String[]{"broadcast"}));
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryClientDemo.parseArguments(new String[]{"--direct"}));
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryClientDemo.parseArguments(
                        new String[]{"--direct", "127.0.0.1", "not-a-port"}));
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryClientDemo.parseArguments(
                        new String[]{"--direct", "127.0.0.1", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryClientDemo.parseArguments(
                        new String[]{"--direct", "127.0.0.1", "5001", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryClientDemo.parseArguments(
                        new String[]{"--direct", "127.0.0.1", "5001", "2147483648"}));
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryClientDemo.parseArguments(
                        new String[]{"--direct", "256.256.256.256"}));
    }
}
