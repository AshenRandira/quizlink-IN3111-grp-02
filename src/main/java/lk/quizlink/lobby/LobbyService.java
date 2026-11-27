package lk.quizlink.lobby;

import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.Quiz;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Thread-safe in-memory authority for active QuizLink rooms and their participant memberships.
 * <p>
 * Room-code allocation uses bounded candidate retries and {@link ConcurrentMap#putIfAbsent};
 * a concurrent creation cannot replace an existing active room. Lookups and removals operate
 * on canonicalized codes. Each active room has an independent membership state guarded by that
 * state's monitor. A room close synchronizes on the same state as joins and leaves, preventing
 * successful registration into a room that has already been removed. The service does not
 * manage quiz execution, participant connection identity, or network communication.
 */
public final class LobbyService {

    public static final int DEFAULT_MAX_CODE_ATTEMPTS = 32;
    private static final int MAX_PARTICIPANT_ID_ATTEMPTS = 16;

    private final RoomCodeGenerator codeGenerator;
    private final int maxCodeAttempts;
    private final Supplier<String> participantIdGenerator;
    private final ConcurrentMap<String, RoomState> rooms = new ConcurrentHashMap<>();
    private final java.util.Set<String> issuedParticipantIds = ConcurrentHashMap.newKeySet();

    /**
     * Creates an in-memory lobby with secure random room codes and UUID participant IDs.
     */
    public LobbyService() {
        this(new RoomCodeGenerator(), DEFAULT_MAX_CODE_ATTEMPTS,
                () -> UUID.randomUUID().toString());
    }

    LobbyService(RoomCodeGenerator codeGenerator, int maxCodeAttempts) {
        this(codeGenerator, maxCodeAttempts, () -> UUID.randomUUID().toString());
    }

    LobbyService(RoomCodeGenerator codeGenerator, int maxCodeAttempts,
                 Supplier<String> participantIdGenerator) {
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator must not be null");
        if (maxCodeAttempts < 1) {
            throw new IllegalArgumentException("maxCodeAttempts must be at least 1");
        }
        this.maxCodeAttempts = maxCodeAttempts;
        this.participantIdGenerator = Objects.requireNonNull(
                participantIdGenerator, "participantIdGenerator must not be null");
    }

    /**
     * Creates and registers a room for the supplied immutable quiz definition.
     *
     * @param quiz existing quiz definition
     * @return the newly registered room and its generated canonical code
     * @throws LobbyException if the quiz is null or no unique code is allocated within the
     * configured attempt bound
     */
    public Room createRoom(Quiz quiz) {
        if (quiz == null) {
            throw new LobbyException(LobbyException.Reason.INVALID_QUIZ,
                    "A quiz definition is required to create a room");
        }

        for (int attempt = 0; attempt < maxCodeAttempts; attempt++) {
            String code = codeGenerator.generateCandidate();
            Room candidate = new Room(code, quiz);
            if (rooms.putIfAbsent(code, new RoomState(candidate)) == null) {
                return candidate;
            }
        }

        throw new LobbyException(LobbyException.Reason.ROOM_CODE_ALLOCATION_FAILED,
                "Could not allocate a unique room code after " + maxCodeAttempts + " attempts");
    }

    /**
     * Finds an active room by code.
     *
     * @param roomCode code to look up
     * @return the room if the code is valid and active; empty for invalid or unknown codes
     */
    public Optional<Room> findRoom(String roomCode) {
        String normalized = normalizedOrNull(roomCode);
        if (normalized == null) {
            return Optional.empty();
        }
        RoomState room = rooms.get(normalized);
        return room == null ? Optional.empty() : Optional.of(room.room);
    }

    /**
     * Checks whether a syntactically valid room code currently identifies an active room.
     *
     * @param roomCode code to check
     * @return true only when the room exists
     */
    public boolean roomExists(String roomCode) {
        String normalized = normalizedOrNull(roomCode);
        return normalized != null && rooms.containsKey(normalized);
    }

    /**
     * Removes an active room. A previously returned {@link Room} remains an immutable snapshot.
     *
     * @param roomCode code to close
     * @return true if an active room was removed, otherwise false for invalid or unknown codes
     */
    public boolean closeRoom(String roomCode) {
        String normalized = normalizedOrNull(roomCode);
        if (normalized == null) {
            return false;
        }
        RoomState room = rooms.get(normalized);
        if (room == null) {
            return false;
        }
        synchronized (room) {
            if (!rooms.remove(normalized, room)) {
                return false;
            }
            room.participants.clear();
            return true;
        }
    }

    /**
     * Registers a student in an existing active room and assigns a server-generated ID.
     * Duplicate display names are allowed; participant IDs distinguish members.
     *
     * @param roomCode existing room code
     * @param displayName student's display name; surrounding whitespace is trimmed
     * @return the newly registered participant
     * @throws LobbyException for an invalid/unknown room, invalid display name, or ID allocation
     * failure
     */
    public Participant joinRoom(String roomCode, String displayName) {
        String normalizedCode = requireRoomCode(roomCode);
        RoomState room = requireActiveRoom(normalizedCode);
        String normalizedName = normalizeDisplayName(displayName);

        synchronized (room) {
            if (rooms.get(normalizedCode) != room) {
                throw invalidRoom();
            }
            Participant participant = allocateParticipant(normalizedName);
            room.participants.put(participant.id(), participant);
            return participant;
        }
    }

    /**
     * Finds a participant only within the specified active room.
     *
     * @param roomCode room containing the participant
     * @param participantId server-generated participant ID
     * @return the participant, or empty if the ID is null, blank, or not a member of that room
     * @throws LobbyException if the room code is invalid or the room is unknown/closed
     */
    public Optional<Participant> findParticipant(String roomCode, String participantId) {
        String normalizedCode = requireRoomCode(roomCode);
        RoomState room = requireActiveRoom(normalizedCode);
        if (participantId == null || participantId.isBlank()) {
            return Optional.empty();
        }
        synchronized (room) {
            requireStillActive(normalizedCode, room);
            return Optional.ofNullable(room.participants.get(participantId));
        }
    }

    /**
     * Returns an immutable snapshot of the active participants in join-registration order.
     * For concurrent joins, that order is the order in which the room lock is acquired.
     *
     * @param roomCode active room to list
     * @return immutable participant snapshot
     * @throws LobbyException if the room code is invalid or the room is unknown/closed
     */
    public List<Participant> getParticipants(String roomCode) {
        String normalizedCode = requireRoomCode(roomCode);
        RoomState room = requireActiveRoom(normalizedCode);
        synchronized (room) {
            requireStillActive(normalizedCode, room);
            return List.copyOf(room.participants.values());
        }
    }

    /**
     * Returns the participant count for an active room.
     *
     * @param roomCode active room to count
     * @return participant count at the snapshot point
     * @throws LobbyException if the room code is invalid or the room is unknown/closed
     */
    public int getParticipantCount(String roomCode) {
        return getParticipants(roomCode).size();
    }

    /**
     * Removes one participant from an active room. Calling this again for an already-removed
     * participant returns false. If the room closes concurrently, closure wins and this returns
     * false; an initially unknown or malformed room raises {@link LobbyException}.
     *
     * @param roomCode active room containing the participant
     * @param participantId trusted server-resolved participant ID
     * @return true if this call removed the participant
     * @throws LobbyException if the room code is invalid or the room is unknown/closed
     */
    public boolean leaveRoom(String roomCode, String participantId) {
        String normalizedCode = requireRoomCode(roomCode);
        RoomState room = requireActiveRoom(normalizedCode);
        if (participantId == null || participantId.isBlank()) {
            return false;
        }
        synchronized (room) {
            if (rooms.get(normalizedCode) != room) {
                return false;
            }
            return room.participants.remove(participantId) != null;
        }
    }

    private Participant allocateParticipant(String displayName) {
        for (int attempt = 0; attempt < MAX_PARTICIPANT_ID_ATTEMPTS; attempt++) {
            String participantId = participantIdGenerator.get();
            if (participantId == null || participantId.isBlank()) {
                throw new LobbyException(LobbyException.Reason.PARTICIPANT_ID_ALLOCATION_FAILED,
                        "Could not generate a valid participant ID");
            }
            Participant participant;
            try {
                participant = new Participant(participantId, displayName);
            } catch (IllegalArgumentException exception) {
                throw new LobbyException(LobbyException.Reason.PARTICIPANT_ID_ALLOCATION_FAILED,
                        "Could not generate a valid participant ID");
            }
            if (issuedParticipantIds.add(participant.id())) {
                return participant;
            }
        }
        throw new LobbyException(LobbyException.Reason.PARTICIPANT_ID_ALLOCATION_FAILED,
                "Could not generate a unique participant ID");
    }

    private static String normalizeDisplayName(String displayName) {
        String trimmed = displayName == null ? null : displayName.trim();
        try {
            return new Participant("display-name-validation", trimmed).displayName();
        } catch (IllegalArgumentException exception) {
            throw new LobbyException(LobbyException.Reason.INVALID_DISPLAY_NAME,
                    "Display name is required and must not contain control characters");
        }
    }

    private static String requireRoomCode(String roomCode) {
        try {
            return RoomCodeGenerator.normalizeCode(roomCode);
        } catch (IllegalArgumentException exception) {
            throw invalidRoom();
        }
    }

    private RoomState requireActiveRoom(String normalizedCode) {
        RoomState room = rooms.get(normalizedCode);
        if (room == null) {
            throw invalidRoom();
        }
        return room;
    }

    private void requireStillActive(String normalizedCode, RoomState room) {
        if (rooms.get(normalizedCode) != room) {
            throw invalidRoom();
        }
    }

    private static LobbyException invalidRoom() {
        return new LobbyException(LobbyException.Reason.INVALID_ROOM,
                "The requested room does not exist");
    }

    private static String normalizedOrNull(String roomCode) {
        if (roomCode == null) {
            return null;
        }
        try {
            return RoomCodeGenerator.normalizeCode(roomCode);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static final class RoomState {
        private final Room room;
        private final LinkedHashMap<String, Participant> participants = new LinkedHashMap<>();

        private RoomState(Room room) {
            this.room = room;
        }
    }
}
