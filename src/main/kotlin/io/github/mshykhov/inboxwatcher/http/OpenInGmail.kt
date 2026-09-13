package io.github.mshykhov.inboxwatcher.http

import io.github.mshykhov.inboxwatcher.core.GmailLinks
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes

/**
 * Public redirect page for phone taps. The undocumented `googlegmail:///cv=` scheme is the only
 * way into the Gmail iOS app (Google's universal links for mail.google.com are broken: the AASA
 * file redirects to www.google.com and 404s), and Telegram refuses non-http links in messages,
 * so the push links here and this page jumps into the scheme. Desktop goes straight to Gmail web.
 */
fun openInGmailRoute(accountEmail: String?): RoutingHttpHandler =
    routes(
        "/open/{id}" bind GET to { request ->
            val id = request.path("id").orEmpty()
            if (THREAD_ID.matches(id)) {
                Response(OK)
                    .header("content-type", "text/html; charset=utf-8")
                    .body(page(id, GmailLinks.thread(accountEmail, id)))
            } else {
                Response(NOT_FOUND)
            }
        },
    )

/** Gmail API ids are hex; anything wider is unexpected and goes into HTML, so reject it. */
private val THREAD_ID = Regex("[A-Za-z0-9_-]{1,64}")

private fun page(
    threadId: String,
    webUrl: String,
): String =
    """
    <!doctype html>
    <html lang="ru">
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Открываю Gmail…</title>
    <style>
    body{font-family:-apple-system,system-ui,sans-serif;background:#f6f8fc;margin:0;min-height:100vh;
    display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px}
    .app{padding:14px 28px;border-radius:24px;background:#0b57d0;color:#fff;text-decoration:none;font-size:17px}
    .web{color:#0b57d0;font-size:15px}
    </style>
    </head>
    <body>
    <a class="app" href="googlegmail:///cv=$threadId">Открыть в приложении Gmail</a>
    <a class="web" href="$webUrl">Открыть в браузере</a>
    <script>
    if(/iPhone|iPad|iPod|Android/.test(navigator.userAgent)){location.href=document.querySelector(".app").href}
    else{location.replace(document.querySelector(".web").href)}
    </script>
    </body>
    </html>
    """.trimIndent()
