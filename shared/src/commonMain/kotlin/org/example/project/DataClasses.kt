package org.example.project

import kotlinx.serialization.Serializable

@Serializable
data class Movie(val id: Int, val original_title: String, val director: String)

@Serializable
data class Book(val primary_isbn13: String, val title: String, val author: String)
