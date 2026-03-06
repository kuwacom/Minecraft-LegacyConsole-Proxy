package dev.kuwa.mlcproxy.session

enum class SessionState {
    CONNECTED,
    PRELOGIN,
    LOGIN,
    PLAY,
    CLOSED
}
