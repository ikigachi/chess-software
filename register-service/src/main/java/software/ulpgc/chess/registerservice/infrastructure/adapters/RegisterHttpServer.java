package software.ulpgc.chess.registerservice.infrastructure.adapters;

import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.http.MethodNotAllowedResponse;
import io.javalin.http.NotFoundResponse;
import software.ulpgc.chess.registerservice.application.domain.model.AccountConflictException;

import java.net.URI;
import java.util.Objects;

public final class RegisterHttpServer implements AutoCloseable {
    private final Javalin server;
    private final int port;

    public RegisterHttpServer(int port, AccountApiHandler accounts) {
        Objects.requireNonNull(accounts);
        this.port = port;
        server = Javalin.create(config -> {
            config.http.prefer405over404 = true;
            config.router.ignoreTrailingSlashes = false;
            config.routes.post("/accounts", accounts::registerAccount);
            config.routes.delete("/accounts/current", accounts::deactivateAccount);
            config.routes.exception(AccountConflictException.class, (exception, context) ->
                    accounts.sendErrorResponse(context, 409, exception.getMessage()));
            config.routes.exception(SecurityException.class, (exception, context) ->
                    accounts.sendErrorResponse(context, 401, "Account credentials are invalid."));
            config.routes.exception(JsonParseException.class, (exception, context) ->
                    accounts.sendErrorResponse(context, 400, "Invalid JSON request."));
            config.routes.exception(IllegalArgumentException.class, (exception, context) ->
                    accounts.sendErrorResponse(context, 400, exception.getMessage()));
            config.routes.exception(IllegalStateException.class, (exception, context) ->
                    accounts.sendErrorResponse(context, 503, "Account service is unavailable. Try again later."));
            config.routes.exception(NotFoundResponse.class, (exception, context) ->
                    accounts.sendErrorResponse(context, 404, "Not found"));
            config.routes.exception(MethodNotAllowedResponse.class, (exception, context) -> {
                context.header("Allow", context.path().equals("/accounts") ? "POST" : "DELETE");
                accounts.sendErrorResponse(context, 405, "Method not allowed");
            });
            config.routes.exception(Exception.class, (exception, context) ->
                    accounts.sendErrorResponse(context, 500, "Account request failed."));
        });
    }

    public void start() { start("127.0.0.1"); }
    public void start(String bindAddress) { server.start(bindAddress, port); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.port()); }

    @Override public void close() { server.stop(); }
}
