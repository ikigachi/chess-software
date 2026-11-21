package software.ulpgc.chess.registerservice.application.ports;

public interface PlayerIdentity {
    String resolvePlayerId(String credential);
}
