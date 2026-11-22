package lk.quizlink.common.protocol;

/** Proposed network settings. Coordinate changes with all module owners. */
public final class ProtocolConstants {
    public static final int VERSION = 1;
    public static final int TCP_PORT = 5000;
    public static final int DISCOVERY_PORT = 5001;
    public static final String MULTICAST_GROUP = "239.10.10.10";
    public static final int MULTICAST_PORT = 5002;
    public static final String DISCOVERY_REQUEST = "QUIZLINK_DISCOVER_V1";
    private ProtocolConstants() { }
}
