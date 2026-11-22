package lk.quizlink.student;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StudentJoinRequestTest {
    @Test
    void trimsInputAndNormalizesRoomCode() {
        StudentJoinRequest request = new StudentJoinRequest(" ab12cd ", " Student 1 ");

        assertEquals("AB12CD", request.roomCode());
        assertEquals("Student 1", request.displayName());
    }

    @Test
    void rejectsMissingJoinValues() {
        assertThrows(IllegalArgumentException.class, () -> new StudentJoinRequest("", "Student"));
        assertThrows(IllegalArgumentException.class, () -> new StudentJoinRequest("AB12CD", " "));
    }
}
