package dev.peekapi;

import static org.junit.jupiter.api.Assertions.*;

import dev.peekapi.internal.ConsumerIdentifier;
import org.junit.jupiter.api.Test;

class ConsumerIdentifierTest {

    @Test
    void xApiKeyReturnedAsIs() {
        assertEquals("ak_live_abc123", ConsumerIdentifier.identify("ak_live_abc123", null));
    }

    @Test
    void xApiKeyTakesPriorityOverAuth() {
        assertEquals(
                "ak_live_abc123",
                ConsumerIdentifier.identify("ak_live_abc123", "Bearer some-token"));
    }

    @Test
    void authorizationHeaderIsHashed() {
        String result = ConsumerIdentifier.identify(null, "Bearer my-token");
        assertNotNull(result);
        assertTrue(result.startsWith("hash_"));
        assertEquals(17, result.length()); // "hash_" (5) + 12 hex chars
    }

    @Test
    void sameAuthProducesSameHash() {
        String hash1 = ConsumerIdentifier.identify(null, "Bearer my-token");
        String hash2 = ConsumerIdentifier.identify(null, "Bearer my-token");
        assertEquals(hash1, hash2);
    }

    @Test
    void differentAuthProducesDifferentHash() {
        String hash1 = ConsumerIdentifier.identify(null, "Bearer token-1");
        String hash2 = ConsumerIdentifier.identify(null, "Bearer token-2");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void nullHeadersReturnNull() {
        assertNull(ConsumerIdentifier.identify(null, null));
    }

    @Test
    void emptyHeadersReturnNull() {
        assertNull(ConsumerIdentifier.identify("", ""));
    }

    @Test
    void hashConsumerIdFormat() {
        String hash = ConsumerIdentifier.hashConsumerId("test-value");
        assertNotNull(hash);
        assertTrue(hash.startsWith("hash_"));
        assertEquals(17, hash.length());
        // Verify hex chars only after prefix
        assertTrue(hash.substring(5).matches("[0-9a-f]{12}"));
    }

    @Test
    void hashConsumerIdIsDeterministic() {
        String hash1 = ConsumerIdentifier.hashConsumerId("test-value");
        String hash2 = ConsumerIdentifier.hashConsumerId("test-value");
        assertEquals(hash1, hash2);
    }
}
