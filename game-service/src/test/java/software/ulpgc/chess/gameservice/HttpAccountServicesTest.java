package software.ulpgc.chess.gameservice;

import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.ulpgc.chess.gameservice.application.ports.AccountAuthentication;
import software.ulpgc.chess.gameservice.infrastructure.adapters.AccountConflictException;
import software.ulpgc.chess.gameservice.infrastructure.adapters.HttpAccountServices;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpAccountServicesTest {
    private static final String TOKEN = "a".repeat(43);
    private static final String PASSWORD = "white knight on e4";
    private static final String IDENTITY = "\"accountId\":\"account\",\"playerId\":\"player\",\"expiresAt\":\"2099-01-01T00:00:00Z\"";
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final AtomicReference<SentRequest> received = new AtomicReference<>();
    private Javalin authentication;
    private Javalin registration;
    private HttpAccountServices accounts;

    @BeforeEach void start() {
        authentication = Javalin.create(config -> {
            config.routes.post("/sessions", this::respond);
            config.routes.get("/sessions/current", this::respond);
            config.routes.delete("/sessions/current", this::respond);
        }).start("127.0.0.1", 0);
        registration = Javalin.create(config -> {
            config.routes.post("/accounts", this::respond);
            config.routes.get("/accounts/by-player/{playerId}", this::respond);
            config.routes.delete("/accounts/current", this::respond);
        }).start("127.0.0.1", 0);
        accounts = new HttpAccountServices(URI.create("http://127.0.0.1:" + registration.port()),
                URI.create("http://127.0.0.1:" + authentication.port()));
    }

    private void respond(Context context) {
        received.set(new SentRequest(context.path(), context.header("Authorization"), context.body()));
        context.status(status.get()).contentType("application/json").result(response.get());
    }

    @AfterEach void stop() {
        if (accounts != null) accounts.close();
        if (registration != null) registration.stop();
        if (authentication != null) authentication.stop();
    }

    @Test void loginResumeAndLogoutUseTheAuthenticationOriginAndBearerHeader() {
        status.set(201);
        response.set("{\"token\":\"" + TOKEN + "\"," + IDENTITY + "}");
        var grant = accounts.login("alice", PASSWORD);
        var identity = new AccountAuthentication.Identity("account", "player", Instant.parse("2099-01-01T00:00:00Z"));
        assertEquals(new AccountAuthentication.Grant(TOKEN, identity), grant);
        assertEquals("/sessions", received.get().path());
        assertNull(received.get().authorization());
        var credentials = JsonParser.parseString(received.get().body()).getAsJsonObject();
        assertEquals("alice", credentials.get("username").getAsString());
        assertEquals(PASSWORD, credentials.get("password").getAsString());

        status.set(200);
        response.set("{" + IDENTITY + "}");
        assertEquals(identity, accounts.resume(TOKEN));
        assertEquals(new SentRequest("/sessions/current", "Bearer " + TOKEN, ""), received.get());
        status.set(204);
        accounts.logout(TOKEN);
        assertEquals(new SentRequest("/sessions/current", "Bearer " + TOKEN, ""), received.get());
        status.set(401);
        assertDoesNotThrow(() -> accounts.logout(TOKEN));
    }

    @Test void registrationLookupAndDeactivationUseTheirOwnOriginAndRequiredCredentials() {
        status.set(201);
        response.set("{\"playerId\":\"player\",\"status\":\"Active\"}");
        assertEquals("player", accounts.register("guest-credential", "alice", PASSWORD));
        assertEquals("/accounts", received.get().path());
        assertNull(received.get().authorization());
        var registrationBody = JsonParser.parseString(received.get().body()).getAsJsonObject();
        assertEquals("guest-credential", registrationBody.get("credential").getAsString());
        assertEquals("alice", registrationBody.get("username").getAsString());
        assertEquals(PASSWORD, registrationBody.get("password").getAsString());

        status.set(200);
        response.set("{\"registered\":true}");
        assertTrue(accounts.hasAccount("player"));
        assertEquals(new SentRequest("/accounts/by-player/player", null, ""), received.get());
        response.set("{\"registered\":false}");
        assertFalse(accounts.hasAccount("player"));

        response.set("{\"playerId\":\"player\",\"status\":\"Inactive\"}");
        assertEquals("player", accounts.deactivate(TOKEN, PASSWORD));
        assertEquals("/accounts/current", received.get().path());
        assertEquals("Bearer " + TOKEN, received.get().authorization());
        var passwordBody = JsonParser.parseString(received.get().body()).getAsJsonObject();
        assertEquals(1, passwordBody.size());
        assertEquals(PASSWORD, passwordBody.get("password").getAsString());
    }

    @Test void malformedRemoteSessionsRemainServiceErrorsAndNeverBecomeBrowserCredentials() {
        status.set(201);
        for (String body : List.of("null", "[]", "{}", "not-json", " ".repeat(8193),
                "{\"token\":\"short\"," + IDENTITY + "}")) {
            response.set(body);
            assertThrows(IllegalStateException.class, () -> accounts.login("alice", PASSWORD));
        }
    }

    @Test void incompleteIdentitiesAndInconsistentAccountResponsesRemainServiceErrors() {
        for (String body : List.of("{}", "{\"registered\":\"false\"}", "{\"registered\":null}")) {
            response.set(body);
            assertThrows(IllegalStateException.class, () -> accounts.hasAccount("player"));
        }
        for (String body : List.of("{}", "{\"accountId\":12,\"playerId\":\"player\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}",
                "{\"accountId\":\"account\",\"playerId\":\"player\",\"expiresAt\":\"invalid\"}")) {
            response.set(body);
            assertThrows(IllegalStateException.class, () -> accounts.resume(TOKEN));
        }
        status.set(201);
        response.set("{\"playerId\":\"player\",\"status\":\"Inactive\"}");
        assertThrows(IllegalStateException.class, () -> accounts.register("guest", "alice", PASSWORD));
        status.set(200);
        response.set("{\"playerId\":\"player\",\"status\":\"Active\"}");
        assertThrows(IllegalStateException.class, () -> accounts.deactivate(TOKEN, PASSWORD));
    }

    @Test void remoteRejectionsDistinguishInvalidInputAuthenticationConflictsAndFailures() {
        response.set("{\"message\":\"remote secret\"}");
        status.set(400);
        assertThrows(IllegalArgumentException.class, () -> accounts.login("alice", PASSWORD));
        status.set(401);
        assertThrows(SecurityException.class, () -> accounts.resume(TOKEN));
        assertThrows(SecurityException.class, () -> accounts.deactivate(TOKEN, PASSWORD));
        status.set(409);
        assertThrows(AccountConflictException.class, () -> accounts.register("guest", "alice", PASSWORD));
        for (int code : new int[] {200, 302, 500, 503}) {
            status.set(code);
            var failure = assertThrows(IllegalStateException.class, () -> accounts.login("alice", PASSWORD));
            assertNotNull(failure.getCause());
            assertFalse(failure.getMessage().contains("remote secret"));
        }
    }

    @Test void unavailableServicesNeverReturnSuccessfulRegistrationSessionOrLogout() {
        registration.stop();
        authentication.stop();
        assertThrows(IllegalStateException.class, () -> accounts.hasAccount("player"));
        assertThrows(IllegalStateException.class, () -> accounts.register("guest", "alice", PASSWORD));
        assertThrows(IllegalStateException.class, () -> accounts.deactivate(TOKEN, PASSWORD));
        assertThrows(IllegalStateException.class, () -> accounts.login("alice", PASSWORD));
        assertThrows(IllegalStateException.class, () -> accounts.resume(TOKEN));
        assertThrows(IllegalStateException.class, () -> accounts.logout(TOKEN));
    }

    private record SentRequest(String path, String authorization, String body) {}
}
