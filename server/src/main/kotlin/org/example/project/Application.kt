package org.example.project

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.io.path.Path
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.html.*
import kotlinx.serialization.*
import java.time.LocalDate
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = IP_ADDRESS, module = Application::module)
        .start(wait = true)
}

private suspend fun getSession(
    call: ApplicationCall
): UserSession? {
    val userSession: UserSession? = call.sessions.get()
    //if there is no session, redirect to login
    if (userSession == null) {
        val redirectUrl = URLBuilder("http://$IP_ADDRESS:8080/login").run {
            parameters.append("redirectUrl", call.request.uri)
            build()
        }
        call.respondRedirect(redirectUrl)
        return null
    }
    return userSession
}

fun Application.configureSecurity() {
//    install(Authentication) {
//        session<UserSession> {
//            validate { session: UserSession ->
//                // Check if the session is valid
//                if (session.accessToken.isNotEmpty()) session else null
//            }
//            challenge {
//                // What to do when authentication fails (e.g., redirect to login)
//                call.respondRedirect("/login")
//            }
//        }
//    }
    install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.path = "/"
            cookie.domain = "localhost"
            cookie.maxAgeInSeconds = 60 * 60 // 1 hour
            cookie.httpOnly = true
            cookie.secure = false
        }
    }
    authentication {
        oauth("auth-oauth-google") {
            urlProvider = { "http://$IP_ADDRESS:$SERVER_PORT/callback" }
            providerLookup = {
                OAuthServerSettings.OAuth2ServerSettings(
                    name = "google",
                    authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                    accessTokenUrl = "https://oauth2.googleapis.com/token",
                    clientId = System.getenv("GOOGLE_CLIENT_ID"),
                    clientSecret = System.getenv("GOOGLE_CLIENT_SECRET"),
                    requestMethod = HttpMethod.Post,
                    defaultScopes = listOf("https://www.googleapis.com/auth/userinfo.profile")
                )
            }
            client = HttpClient(Apache)
        }
    }

    routing {
        authenticate("auth-oauth-google") {
            get("/login") {
                call.sessions.set(UserSession("accessToken"))
                call.respondRedirect("/callback")
            }

            get("/callback") {
                val principal: OAuthAccessTokenResponse.OAuth2? = call.authentication.principal()
                if (principal != null) {
                    call.sessions.set(UserSession(principal.accessToken))
                    call.respondText("You can close this window")
                } else {
                    call.respondRedirect("/login")  // Redirect back to login if authentication failed
                }
            }
        }
    }
}

@Serializable
data class UserSession(val accessToken: String): Principal

fun Application.module() {
    configureSecurity()
    install(CORS) {
        anyHost() // For development; restrict this in production to specific origins
        allowCredentials = true // Allows cookies to be sent along with requests
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }

    routing {
        get("/status") {
            val a = call.sessions.get<UserSession>()
            call.respondText(
                if (a == null)
                    ""
                else
                    call.sessions.get<UserSession>()!!.accessToken)
        }
        get("/movies") {
            val userSession: UserSession? = getSession(call)
            if (userSession != null) call.respond(MovieHandler.getMovies(call.request.queryParameters["batch"]!!.toInt()))
        }
        get("/books") {
            val userSession: UserSession? = getSession(call)
            if (userSession != null) call.respond(BookHandler.getBooks(call.request.queryParameters["batch"]!!.toInt()))
        }
    }
}

object MovieHandler {
    @OptIn(InternalAPI::class)
    private suspend fun downloadMoviesFile(filename: String) {
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://files.tmdb.org/p/exports/$filename")
            val outputFile = File(filename)

            outputFile.writeChannel().use {
                response.content.copyTo(this)
            }
        } catch (e: Exception) {
            println("Error downloading file: ${e.message}")
        } finally {
            client.close()
        }
    }

    private fun deleteOldFile(date: LocalDate) {
        val prevDate = date.minusDays(1)
        val prevDateFilename = "movie_ids_${prevDate.monthValue}_${prevDate.dayOfMonth}_${prevDate.year}.json"
        if (Files.exists(Path(prevDateFilename))) {
            val prevFile = File(prevDateFilename)
            val prevFileGz = File("$prevDateFilename.gz")
            prevFile.delete()
            prevFileGz.delete()
        }
    }

    private suspend fun unzipMoviesFile(filename: String) {
        val buffer = ByteArray(1024)
        withContext(Dispatchers.IO) {
            GZIPInputStream(FileInputStream("$filename.gz")).use { gzipInputStream ->
                FileOutputStream(filename).use { outputStream ->
                    var len: Int
                    while (gzipInputStream.read(buffer).also { len = it } > 0) {
                        outputStream.write(buffer, 0, len)
                    }
                }
            }
        }
    }

    private suspend fun updateMovieFile(): String {
        val date = LocalDate.now()
        val filename = "movie_ids_${date.monthValue}_${date.dayOfMonth}_${date.year}.json"
        if (!Files.exists(Path(filename))) {
            try {
                downloadMoviesFile("$filename.gz")
                deleteOldFile(date)
            } catch (e: Exception) {
                // Date has changed but archive is still old
            }

            unzipMoviesFile(filename)
        }
        return filename
    }

    private val TMDB_TOKEN = System.getenv("TMDB_TOKEN")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class TMDBPerson(val name: String, val job: String)

    @Serializable
    data class TMDBResponse(val crew: List<TMDBPerson>)

    @Serializable
    data class MovieWithoutDirector(val id: Int, val original_title: String)

    private suspend fun getMovieDirector(id: Int): String {
        val client = HttpClient(CIO)
        val response = client.get("https://api.themoviedb.org/3/movie/$id/credits?api_key=$TMDB_TOKEN")
        val tmdbResponse = json.decodeFromString<TMDBResponse>(response.body())
        return tmdbResponse.crew.firstOrNull { it.job == "Director" }?.name ?: "Not found"
    }

    suspend fun getMovies(batch: Int): List<Movie> {
        val filename = updateMovieFile()
        return File(filename).useLines { lines ->
            lines.drop(batch * BATCH_SIZE).take(BATCH_SIZE).toList().map {
                val rawMovie = json.decodeFromString<MovieWithoutDirector>(it)
                Movie(rawMovie.id, rawMovie.original_title, getMovieDirector(rawMovie.id))
            }
        }
    }
}

object BookHandler {
    private val BOOKS_TOKEN = System.getenv("BOOKS_TOKEN")
    private val json = Json { ignoreUnknownKeys = true }
    private var lists = emptyList<String>()
    private var listsIndex = 1
    private val filename = "books.txt"
    private var filenameSize = 0

    @Serializable
    data class ListOfBooks(val books: List<Book>)

    @Serializable
    data class BooksList(val list_name_encoded: String)

    @Serializable
    data class Lists(val results: List<BooksList>)

    @Serializable
    data class Results(val results: ListOfBooks)

    private suspend fun initialize() {
        File(filename).delete()
        val client = HttpClient(CIO)
        val response = client.get("https://api.nytimes.com/svc/books/v3/lists/names.json?api-key=$BOOKS_TOKEN")
        val jsonLists = json.decodeFromString<Lists>(response.body())
        lists = jsonLists.results.map { it.list_name_encoded }
    }

    private suspend fun updateBooksFile(lastIndex: Int) {
        while (lastIndex >= filenameSize) {
            val client = HttpClient(CIO)
            val response =
                client.get("https://api.nytimes.com/svc/books/v3/lists/current/${lists[listsIndex]}.json?api-key=$BOOKS_TOKEN")
            if (response.status == HttpStatusCode.TooManyRequests) {
                return
            }
            val jsonBooks = json.decodeFromString<Results>(response.body())
            listsIndex += 1
            File(filename).appendText(
                "\n${
                    jsonBooks.results.books.joinToString("\n") {
                        json.encodeToString(
                            Book.serializer(),
                            it
                        )
                    }
                }"
            )
            filenameSize += jsonBooks.results.books.size
        }
    }

    suspend fun getBooks(batch: Int): List<Book> {
        if (lists.isEmpty()) initialize()
        updateBooksFile((batch + 1) * BATCH_SIZE)

        return File(filename).useLines { lines ->
            lines.drop(batch * BATCH_SIZE).take(BATCH_SIZE).toList().filterNot { it == "" }.map {
                json.decodeFromString<Book>(it)
            }
        }
    }
}