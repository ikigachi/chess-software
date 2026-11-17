package software.ulpgc.chess.backend.infrastructure.adapters;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RoomHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public RoomHttpServer(int port, RoomApiHandler api, GameApiHandler games, StaticAssetHandler assets) throws IOException {
        Objects.requireNonNull(api);
        Objects.requireNonNull(games);
        Objects.requireNonNull(assets);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor);
        server.createContext("/api/", api);
        server.createContext("/games", games);
        server.createContext("/", assets);
    }

    public void start() { server.start(); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
