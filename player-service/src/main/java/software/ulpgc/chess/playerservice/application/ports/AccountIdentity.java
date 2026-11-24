package software.ulpgc.chess.playerservice.application.ports;

public interface AccountIdentity {
    String resolvePlayerId(String token);
}
