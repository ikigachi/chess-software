package software.ulpgc.chess.matchesservice.infrastructure.adapters;

import com.google.gson.*;
import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.matchesservice.application.ports.GameReader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class HttpGameReader implements GameReader, AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final URI origin;
    private final HttpClient client;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public HttpGameReader(URI origin) {
        if (origin == null || !Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !Set.of("", "/").contains(origin.getPath()))
            throw new IllegalArgumentException("Game service must be an HTTP origin.");
        this.origin = origin;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override public Optional<Room> findByRoomCode(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Room code cannot be empty.");
        String path = "/games/" + URLEncoder.encode(code, StandardCharsets.UTF_8).replace("+", "%20");
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(4)).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new IOException("Game service rejected the query.");
            JsonObject fields = gson.fromJson(new String(response.body(), StandardCharsets.UTF_8), JsonObject.class);
            Room room = readRoom(fields);
            if (!code.equals(room.code())) throw new IOException("Game service returned a different room.");
            return Optional.of(room);
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            throw new IllegalStateException("Game service is unavailable.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Game query was interrupted.", exception);
        }
    }

    private Room readRoom(JsonObject fields) throws IOException {
        String id = readRequiredString(fields, "id");
        JsonObject room = readRequiredObject(fields, "room");
        User white = readUser(readRequiredObject(room, "white"));
        if (!room.has("black")) throw new IOException("Game response has no Black participant field.");
        User black = room.get("black").isJsonNull() ? null : readUser(readRequiredObject(room, "black"));
        var history = room.get("moves");
        if (history == null || !history.isJsonArray()) throw new IOException("Game response has no move history.");
        var moves = new ArrayList<Move>();
        for (var element : history.getAsJsonArray()) {
            if (!element.isJsonObject()) throw new IOException("Game response contains an invalid move.");
            JsonObject move = element.getAsJsonObject();
            var value = move.get("promotion");
            Promotion promotion = value == null || value.isJsonNull() ? null
                    : Promotion.valueOf(capitalize(readRequiredString(move, "promotion")));
            moves.add(new Move(Square.at(readRequiredString(move, "from")), Square.at(readRequiredString(move, "to")), promotion));
        }
        if (black == null && !moves.isEmpty()) throw new IOException("Game has moves without a Black participant.");
        return new Room(readRequiredString(room, "code"), white, black, Game.replay(id, moves));
    }

    private String capitalize(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private User readUser(JsonObject fields) throws IOException {
        return new User(readRequiredString(fields, "id"), readRequiredString(fields, "name"));
    }

    private JsonObject readRequiredObject(JsonObject fields, String name) throws IOException {
        var value = fields == null ? null : fields.get(name);
        if (value == null || !value.isJsonObject()) throw new IOException("Game response has no valid " + name + ".");
        return value.getAsJsonObject();
    }

    private String readRequiredString(JsonObject fields, String name) throws IOException {
        var value = fields == null ? null : fields.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IOException("Game response has no valid " + name + ".");
        return value.getAsString();
    }

    @Override public void close() { client.close(); }
}
