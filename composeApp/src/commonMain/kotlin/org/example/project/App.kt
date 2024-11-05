package org.example.project

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.Alignment
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.cookies.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import org.jetbrains.skiko.currentNanoTime
import java.awt.Desktop
import java.net.URI

class Repository {
    private val client = HttpClient(CIO) {
        install(HttpCookies)
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var moviesBatchNumber = 0
    private var booksBatchNumber = 0

    suspend fun getMovies(): List<Movie> {
        val response: HttpResponse = client.get("http://$IP_ADDRESS:$SERVER_PORT/movies") {
            parameter("batch", moviesBatchNumber)
        }
        moviesBatchNumber += 1
        return response.body()
    }

    suspend fun getBooks(): List<Book> {
        val response: HttpResponse = client.get("http://$IP_ADDRESS:$SERVER_PORT/books") {
            parameter("batch", booksBatchNumber)
        }
        booksBatchNumber += 1
        return response.body()
    }

    suspend fun loginToGoogle() {
        client.get("http://$IP_ADDRESS:$SERVER_PORT/login")
    }

    suspend fun isLoggedIn(): Boolean {
        return client.get("http://$IP_ADDRESS:$SERVER_PORT/status").body() as String != ""
    }
}

sealed class LoginState {
    object LoggedOut : LoginState()
    data class LoggedIn(val token: String) : LoginState()
}


@Composable
fun <T> InfiniteList(loadMore: suspend () -> List<T>) {
    val scrollState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val visible = remember { mutableStateListOf<T>() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorTime by remember { mutableStateOf<Long>(0) }

    LaunchedEffect(Unit) {
        visible.clear()
        visible.addAll(loadMore())
        visible.addAll(loadMore())
    }

    LazyColumn(
        state = scrollState,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        items(visible) { element ->
            when (element) {
                is Movie -> MovieItem(element)
                is Book -> BookItem(element)
            }
        }
    }

    LaunchedEffect(scrollState.firstVisibleItemIndex) {
        if (scrollState.firstVisibleItemIndex == visible.size - BATCH_SIZE || errorMessage != null && currentNanoTime() - errorTime >= TIME_TO_RETRY) {
            coroutineScope.launch {
                val newItems = loadMore()
                if (newItems.size == BATCH_SIZE) {
                    visible.addAll(newItems)
                    errorMessage = null
                    errorTime = 0
                } else {
                    errorMessage = "Too many requests, pleas wait for a minute"
                    errorTime = currentNanoTime()
                }
            }
        }
    }

    errorMessage?.let {
        Text(text = it, color = MaterialTheme.colors.error)
    }
}

@Composable
fun MovieItem(movie: Movie) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(movie.original_title, style = MaterialTheme.typography.h6)
        Spacer(modifier = Modifier.height(8.dp))
        Text(movie.director, style = MaterialTheme.typography.body2)
        Divider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
fun BookItem(book: Book) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(book.title, style = MaterialTheme.typography.h6)
        Spacer(modifier = Modifier.height(8.dp))
        Text(book.author, style = MaterialTheme.typography.body2)
        Divider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
fun App() {
    var loginState by remember { mutableStateOf<LoginState>(LoginState.LoggedOut) }
    val repository = Repository()

    when (loginState) {
        is LoginState.LoggedOut -> LoginScreen(repository) { token ->
            loginState = LoginState.LoggedIn(token)
        }
        is LoginState.LoggedIn -> MainAppContent(repository)
    }
}

@Composable
fun LoginScreen(repository: Repository, onLoginSuccess: (String) -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Welcome! Please log in to continue.", style = MaterialTheme.typography.h6)
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = {
            coroutineScope.launch {
                repository.loginToGoogle()
                Desktop.getDesktop().browse(URI("http://$IP_ADDRESS:$SERVER_PORT/login"))
                while (!repository.isLoggedIn()) {
                    delay(1000)
                }
                onLoginSuccess("token") // Call it only after successful authorization
            }
        }) {
            Text("Log in with Google")
        }
    }
}

@Composable
fun MainAppContent(repository: Repository) {
    var currentTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TabRow(selectedTabIndex = currentTab) {
                Tab(selected = currentTab == 0, onClick = { currentTab = 0 }) {
                    Text("Movies")
                }

                Tab(selected = currentTab == 1, onClick = { currentTab = 1 }) {
                    Text("Books")
                }
            }
        }
    ) {
        when (currentTab) {
            0 -> InfiniteList { repository.getMovies() }
            1 -> InfiniteList { repository.getBooks() }
            else -> throw IndexOutOfBoundsException()
        }
    }
}