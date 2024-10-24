package org.example.project

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import kotlin.io.path.Path
import kotlinx.serialization.json.Json
import org.example.project.MovieHandler.MovieWithoutDirector
import org.example.project.MovieHandler.TMDBResponse
import org.example.project.MovieHandler.TMDB_TOKEN
import org.example.project.MovieHandler.getMovieDirector
import org.example.project.MovieHandler.json
import org.example.project.MovieHandler.updateMovieFile

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = IP_ADDRESS, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    routing {
        route("/movies") {
            get {
                call.respond(MovieHandler.getMovies(call.request.queryParameters["batch"]!!.toInt()))
            }
        }
        route("/books") {
            get {
                call.respond(BookHandler.getBooks(call.request.queryParameters["batch"]!!.toInt()))
            }
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
            File(filename).appendText("\n${jsonBooks.results.books.joinToString("\n") { json.encodeToString(Book.serializer(), it) }}")
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