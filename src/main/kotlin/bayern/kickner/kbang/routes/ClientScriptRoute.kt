package bayern.kickner.kbang.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /kbang.sh`: the Bash client with this server's URL baked in (see `clientScript`). Unauthenticated, the
 * script holds no secret, the key comes from the caller's `KBANG_KEY`.
 *
 * @param script The finished script, built once at startup.
 */
fun Route.clientScriptRoute(script: String) {
    get("/kbang.sh") {
        call.respondText(script, ContentType.Text.Plain)
    }
}
