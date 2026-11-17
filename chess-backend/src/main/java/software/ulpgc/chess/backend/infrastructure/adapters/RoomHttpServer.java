package software.ulpgc.chess.backend.infrastructure.adapters;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;

import java.net.URI;
import java.util.Objects;

public final class RoomHttpServer implements AutoCloseable {
    private final Javalin server;
    private final int port;

    public RoomHttpServer(int port, Handler proxy, StaticAssetHandler assets) {
        Objects.requireNonNull(proxy);
        Objects.requireNonNull(assets);
        this.port = port;
        server = Javalin.create(config -> {
            config.router.ignoreTrailingSlashes = false;
            for (HandlerType method : HandlerType.commonHttp()) {
                config.routes.addHttpHandler(method, "/api/*", proxy);
                config.routes.addHttpHandler(method, "/games", proxy);
                config.routes.addHttpHandler(method, "/games/*", proxy);
                config.routes.addHttpHandler(method, "/", assets);
                config.routes.addHttpHandler(method, "/*", assets);
            }
        });
    }

    public void start() { server.start("127.0.0.1", port); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.port()); }

    @Override public void close() { server.stop(); }
}
