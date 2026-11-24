package software.ulpgc.chess.frontend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "regalia.docker.tests", matches = "true")
class DockerDeploymentIntegrationTest {
    private final String projectName = "regalia-test-" + UUID.randomUUID().toString().substring(0, 8);
    private Path repositoryRoot;
    private Path logDirectory;
    private URI origin;
    private int commandNumber;

    @BeforeAll void startIsolatedDeployment() throws Exception {
        repositoryRoot = findRepositoryRoot();
        logDirectory = Files.createTempDirectory(repositoryRoot.resolve("chess-frontend/target"), "docker-check-");
        try (var socket = new ServerSocket(0)) {
            origin = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        runCompose("up", "-d", "--build");
        runCompose("exec", "-T", "nginx", "nginx", "-t");
        awaitGameService();
        awaitAuthenticationService();
    }

    @AfterAll void stopIsolatedDeployment() throws Exception {
        if (origin == null) return;
        try {
            runCompose("logs", "--no-color");
        } finally {
            runCompose("down", "--volumes");
        }
    }

    @Test void servesStaticMarkupJavascriptAndAllPieceAssetsWithoutEmbeddingPlayerText() throws Exception {
        try (var browser = newBrowser()) {
            var response = send(browser, "/", "GET", null);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("A game, without haste."));
            assertTrue(response.body().contains("<script type=\"module\" src=\"/main.js\""));
            assertTrue(response.body().contains("id=\"square-template\""));
            assertFalse(response.body().contains("{{"));
            var preview = loadState(browser);
            assertTrue(preview.get("room").isJsonNull());
            assertEquals(32, preview.getAsJsonObject("pieces").size());
            assertEquals("Guest", preview.getAsJsonObject("viewer").get("name").getAsString());
            assertEquals(200, send(browser, "/style.css", "GET", null).statusCode());
            for (String script : List.of("main.js", "app.js", "room-actions.js", "room-api.js")) {
                var javascript = send(browser, "/" + script, "GET", null);
                assertEquals(200, javascript.statusCode());
                assertEquals("application/javascript", javascript.headers().firstValue("Content-Type")
                        .orElseThrow().split(";", 2)[0]);
            }
            for (String color : List.of("white", "black")) {
                for (String piece : List.of("pawn", "rook", "knight", "bishop", "queen", "king")) {
                    var asset = send(browser, "/assets/pieces/" + color + "-" + piece + ".svg", "GET", null);
                    assertEquals(200, asset.statusCode());
                    assertEquals("image/svg+xml", asset.headers().firstValue("Content-Type").orElseThrow().split(";", 2)[0]);
                }
            }
            assertEquals(200, send(browser, "/assets/marble-bust.png", "GET", null).statusCode());
            for (String missing : List.of("/assets/pieces/white-dragon.svg", "/unknown", "/assets/../pom.xml")) {
                assertEquals(404, send(browser, missing, "GET", null).statusCode());
            }
            readJsonResponse(send(browser, "/api/action", "GET", null), 405);
            String name = "A <script>& {{TOKEN}}";
            var created = readJsonResponse(sendJson(browser, "/games", "POST",
                    Map.of("token", preview.get("token").getAsString(), "name", name)), 201);
            assertEquals(name, created.getAsJsonObject("room").getAsJsonObject("white").get("name").getAsString());
            assertFalse(send(browser, "/", "GET", null).body().contains(name));
        }
    }

    @Test void servesTheFrontendAndPreservesCookiesHeadersAndRejectedRequests() throws Exception {
        try (var browser = newBrowser()) {
            var page = send(browser, "/", "GET", null);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Saved profile name"));
            Path web = repositoryRoot.resolve("chess-frontend/src/main/resources/web");
            try (var paths = Files.walk(web)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String path = "/" + web.relativize(file).toString().replace('\\', '/');
                    assertEquals(200, send(browser, path, "GET", null).statusCode(), path);
                }
            }
            var response = send(browser, "/api/state", "GET", null);
            assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));
            var before = readJsonResponse(response, 200);
            String token = before.get("token").getAsString();
            assertEquals(before.get("viewer"), loadState(browser).get("viewer"));

            var rejected = readJsonResponse(sendJson(browser, "/api/profile", "PUT",
                    Map.of("token", "wrong", "name", "Rejected")), 403);
            assertEquals(token, rejected.get("token").getAsString());
            assertEquals(before, loadState(browser));
            readJsonResponse(send(browser, "/api/profile", "PUT", "{"), 400);
            var wrongMethod = send(browser, "/api/profile", "DELETE", null);
            readJsonResponse(wrongMethod, 405);
            assertEquals("GET, PUT", wrongMethod.headers().firstValue("Allow").orElseThrow());

            String update = encodeJson(Map.of("token", token, "name", "Boundary"));
            String boundary = update + " ".repeat(8192 - update.getBytes(StandardCharsets.UTF_8).length);
            readJsonResponse(send(browser, "/api/profile", "PUT", boundary), 200);
            readJsonResponse(send(browser, "/api/profile", "PUT", boundary + " "), 413);
            assertEquals("Boundary", loadState(browser).getAsJsonObject("viewer").get("name").getAsString());
            assertEquals(404, send(browser, "/players/resolve", "POST", "{}").statusCode());
            assertEquals(404, send(browser, "/README.md", "GET", null).statusCode());
        }
    }

    @Test void playsAndRecoversTheSameGameAndProfileAfterRecreatingTheJavaContainers() throws Exception {
        try (var white = newBrowser(); var black = newBrowser()) {
            var whiteState = loadState(white);
            var blackState = loadState(black);
            String whiteToken = whiteState.get("token").getAsString();
            String blackToken = blackState.get("token").getAsString();
            assertNotEquals(whiteState.get("viewer"), blackState.get("viewer"));
            var created = sendJson(white, "/games", "POST", Map.of("token", whiteToken, "name", "Historical Alice"));
            String code = readJsonResponse(created, 201).get("id").getAsString();
            assertEquals("/games/" + code, created.headers().firstValue("Location").orElseThrow());
            joinRoom(black, code, blackToken, "Bob");
            recordMove(white, code, whiteToken, "e2", "e4");
            recordMove(black, code, blackToken, "e7", "e5");
            var before = loadState(white);
            var updated = readJsonResponse(sendJson(white, "/api/profile", "PUT",
                    Map.of("token", whiteToken, "name", "Saved Alice")), 200);
            var after = loadState(white);
            assertEquals(before.get("room"), after.get("room"));
            assertEquals(before.get("pieces"), after.get("pieces"));
            assertEquals(updated.get("profile"), after.get("viewer"));

            runCompose("up", "-d", "--no-deps", "--force-recreate", "player-service", "game-service");
            awaitGameService();
            var restored = loadState(white);
            assertEquals(updated.get("profile"), restored.get("viewer"));
            assertNotEquals(whiteToken, restored.get("token").getAsString());
            String restoredToken = restored.get("token").getAsString();
            var reopened = joinRoom(white, code, restoredToken, "Returning Alice");
            assertEquals(before.get("room"), reopened.get("room"));
            assertEquals(before.get("pieces"), reopened.get("pieces"));
            var continued = recordMove(white, code, restoredToken, "g1", "f3");
            assertEquals(3, continued.getAsJsonObject("room").getAsJsonArray("moves").size());
            assertEquals("Historical Alice", continued.getAsJsonObject("room").getAsJsonObject("white").get("name").getAsString());
        }
    }

    @Test void returnsJsonWhenTheGameServiceIsStoppedOrDoesNotRespond() throws Exception {
        try (var browser = newBrowser()) {
            runCompose("stop", "game-service");
            try {
                var failure = readJsonResponse(send(browser, "/api/state", "GET", null), 503);
                assertEquals("Game service is unavailable. Try again shortly.", failure.get("message").getAsString());
                assertEquals(200, send(browser, "/", "GET", null).statusCode());
            } finally {
                runCompose("start", "game-service");
                awaitGameService();
            }
            runCompose("pause", "game-service");
            try {
                readJsonResponse(send(browser, "/api/state", "GET", null), 503);
            } finally {
                runCompose("unpause", "game-service");
                awaitGameService();
            }
        }
    }

    @Test void authenticatesAcrossContainersAndBlocksTheAccountAfterDeactivationWithoutChangingHistory() throws Exception {
        try (var white = newBrowser(); var black = newBrowser(); var anotherBrowser = newBrowser()) {
            var initial = loadState(white);
            String initialToken = initial.get("token").getAsString();
            String playerId = initial.getAsJsonObject("viewer").get("id").getAsString();
            String code = readJsonResponse(sendJson(white, "/games", "POST", Map.of("token", initialToken,
                    "name", "Historical account Alice")), 201).get("id").getAsString();
            var blackState = loadState(black);
            joinRoom(black, code, blackState.get("token").getAsString(), "Account opponent");
            String username = "account-" + UUID.randomUUID().toString().substring(0, 8);
            String password = "white knight on e4";
            var registered = readJsonResponse(sendJson(white, "/api/accounts", "POST", Map.of(
                    "token", initialToken, "username", username, "password", password)), 201);
            assertTrue(registered.get("requiresLogin").getAsBoolean());
            readJsonResponse(send(white, "/api/state", "GET", null), 401);
            var loginResponse = sendJson(white, "/api/session", "POST", Map.of(
                    "token", initialToken, "username", username, "password", password));
            var signedIn = readJsonResponse(loginResponse, 201);
            assertTrue(signedIn.get("authenticated").getAsBoolean());
            assertEquals(playerId, signedIn.getAsJsonObject("viewer").get("id").getAsString());
            assertTrue(loginResponse.headers().allValues("Set-Cookie").stream().allMatch(cookie ->
                    cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax")));
            assertDoesNotThrow(() -> UUID.fromString(signedIn.get("token").getAsString()));
            assertFalse(loginResponse.body().contains(password));
            String token = signedIn.get("token").getAsString();
            readJsonResponse(sendJson(white, "/api/profile", "PUT", Map.of("token", token, "name", "Account Alice")), 200);
            recordMove(white, code, token, "e2", "e4");
            recordMove(black, code, blackState.get("token").getAsString(), "e7", "e5");

            var challenge = readJsonResponse(send(anotherBrowser, "/api/session", "GET", null), 200);
            readJsonResponse(sendJson(anotherBrowser, "/api/session", "POST", Map.of("token", challenge.get("token").getAsString(),
                    "username", username, "password", password)), 201);
            runCompose("up", "-d", "--no-deps", "--force-recreate", "register-service", "authentication-service", "game-service");
            awaitGameService();
            awaitAuthenticationService();
            var restored = readJsonResponse(send(anotherBrowser, "/api/session", "GET", null), 200);
            assertTrue(restored.get("authenticated").getAsBoolean());
            assertEquals(playerId, restored.getAsJsonObject("viewer").get("id").getAsString());
            assertEquals("Account Alice", readJsonResponse(send(anotherBrowser, "/api/profile", "GET", null), 200)
                    .getAsJsonObject("profile").get("name").getAsString());
            recordMove(anotherBrowser, code, restored.get("token").getAsString(), "g1", "f3");
            readJsonResponse(sendJson(anotherBrowser, "/api/accounts/current", "DELETE", Map.of(
                    "token", restored.get("token").getAsString(), "password", password)), 200);
            readJsonResponse(send(white, "/api/state", "GET", null), 401);
            var recorded = readJsonResponse(send(white, "/games/" + code, "GET", null), 200).getAsJsonObject("room");
            assertEquals(3, recorded.getAsJsonArray("moves").size());
            assertEquals("Historical account Alice", recorded.getAsJsonObject("white").get("name").getAsString());
            for (String internal : List.of("/accounts", "/sessions", "/players/current"))
                assertEquals(404, send(white, internal, "GET", null).statusCode());
        }
    }

    private void awaitAuthenticationService() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        try (var browser = newBrowser()) {
            while (System.nanoTime() < deadline) {
                try {
                    var challenge = send(browser, "/api/session", "GET", null);
                    if (challenge.statusCode() == 200) {
                        String token = readJsonResponse(challenge, 200).get("token").getAsString();
                        if (sendJson(browser, "/api/session", "POST", Map.of("token", token, "username", "not-registered",
                                "password", "a deliberately invalid password")).statusCode() == 401) return;
                    }
                } catch (java.io.IOException ignored) {}
                Thread.sleep(200);
            }
        }
        fail("The Docker authentication service did not become available. See " + logDirectory);
    }

    private Path findRepositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("compose.yaml"))) directory = directory.getParent();
        return java.util.Objects.requireNonNull(directory, "Run the Docker checks from the Maven project.");
    }

    private void runCompose(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-f",
                repositoryRoot.resolve("compose.yaml").toString(), "-p", projectName));
        command.addAll(List.of(arguments));
        Path output = logDirectory.resolve("command-" + ++commandNumber + ".log");
        var builder = new ProcessBuilder(command).directory(repositoryRoot.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("REGALIA_PORT", Integer.toString(origin.getPort()));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.MINUTES), "Docker command timed out. See " + output);
            assertEquals(0, process.exitValue(), "Docker command failed. See " + output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void awaitGameService() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        try (var browser = newBrowser()) {
            while (System.nanoTime() < deadline) {
                try {
                    if (send(browser, "/api/state", "GET", null).statusCode() == 200) return;
                } catch (java.io.IOException ignored) {}
                Thread.sleep(200);
            }
        }
        fail("The Docker game service did not become available. See " + logDirectory);
    }

    private HttpClient newBrowser() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> send(HttpClient browser, String path, String method, String body) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(15));
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendJson(HttpClient browser, String path, String method, Map<String, String> fields) throws Exception {
        return send(browser, path, method, encodeJson(fields));
    }

    private String encodeJson(Map<String, String> fields) {
        JsonObject body = new JsonObject();
        fields.forEach(body::addProperty);
        return body.toString();
    }

    private JsonObject readJsonResponse(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                .toLowerCase(Locale.ROOT).startsWith("application/json"));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject loadState(HttpClient browser) throws Exception {
        return readJsonResponse(send(browser, "/api/state", "GET", null), 200);
    }

    private JsonObject joinRoom(HttpClient browser, String code, String token, String name) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve("/api/action")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(Map.of("action", "join", "code", code,
                        "token", token, "name", name)), StandardCharsets.UTF_8)).build();
        return readJsonResponse(browser.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 200);
    }

    private String encodeForm(Map<String, String> fields) {
        StringJoiner form = new StringJoiner("&");
        fields.forEach((name, value) -> form.add(URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return form.toString();
    }

    private JsonObject recordMove(HttpClient browser, String code, String token, String from, String to) throws Exception {
        return readJsonResponse(sendJson(browser, "/games/" + code, "PUT",
                Map.of("token", token, "from", from, "to", to)), 200);
    }
}
