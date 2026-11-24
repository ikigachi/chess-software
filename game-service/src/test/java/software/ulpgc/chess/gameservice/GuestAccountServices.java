package software.ulpgc.chess.gameservice;

import software.ulpgc.chess.gameservice.application.ports.AccountAuthentication;
import software.ulpgc.chess.gameservice.application.ports.AccountRegistration;

final class GuestAccountServices implements AccountAuthentication, AccountRegistration {
    @Override public boolean hasAccount(String playerId) { return false; }
    @Override public String register(String credential, String username, String password) { throw new UnsupportedOperationException(); }
    @Override public String deactivate(String token, String password) { throw new UnsupportedOperationException(); }
    @Override public Grant login(String username, String password) { throw new SecurityException(); }
    @Override public Identity resume(String token) { throw new SecurityException(); }
    @Override public void logout(String token) { throw new SecurityException(); }
}
