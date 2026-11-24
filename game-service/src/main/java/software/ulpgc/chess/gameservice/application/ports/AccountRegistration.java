package software.ulpgc.chess.gameservice.application.ports;

public interface AccountRegistration {
    boolean hasAccount(String playerId);

    String register(String credential, String username, String password);

    String deactivate(String token, String password);
}
