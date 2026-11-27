package lk.quizlink.lobby;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomCodeGeneratorTest {

    @Test
    void generatesSixUppercaseAlphanumericCharacters() {
        RoomCodeGenerator generator = new RoomCodeGenerator(bound -> bound - 1);

        String candidate = generator.generateCandidate();

        assertEquals("999999", candidate);
        assertEquals(RoomCodeGenerator.CODE_LENGTH, candidate.length());
        assertTrue(candidate.matches("[A-Z0-9]{6}"));
    }

    @Test
    void usesEachInjectedRandomIndexWithinAlphabetBounds() {
        AtomicInteger calls = new AtomicInteger();
        RoomCodeGenerator generator = new RoomCodeGenerator(bound -> {
            calls.incrementAndGet();
            return 0;
        });

        assertEquals("AAAAAA", generator.generateCandidate());
        assertEquals(6, calls.get());
    }

    @Test
    void validatesSyntaxAndNormalizesUserInput() {
        assertTrue(RoomCodeGenerator.isValidCode("ab12cd"));
        assertTrue(RoomCodeGenerator.isValidCode(" AB12CD "));
        assertFalse(RoomCodeGenerator.isValidCode(null));
        assertFalse(RoomCodeGenerator.isValidCode("AB12C"));
        assertFalse(RoomCodeGenerator.isValidCode("AB12C!"));
        assertEquals("AB12CD", RoomCodeGenerator.normalizeCode(" ab12cd "));
    }

    @Test
    void rejectsMalformedInputAndInvalidRandomIndexes() {
        assertThrows(IllegalArgumentException.class, () -> RoomCodeGenerator.normalizeCode(null));
        assertThrows(IllegalArgumentException.class, () -> RoomCodeGenerator.normalizeCode("  "));
        assertThrows(IllegalArgumentException.class, () -> RoomCodeGenerator.normalizeCode("ABC-12"));
        assertThrows(IllegalStateException.class,
                () -> new RoomCodeGenerator(bound -> bound).generateCandidate());
    }
}
