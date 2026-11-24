package software.ulpgc.chess.registerservice.application.ports;

public interface AccountIdentity {
    String resolvePlayerId(String token);
}
