package com.yancotv.shared

expect class Platform() {
    val name: String
}

expect fun getPlatform(): Platform
