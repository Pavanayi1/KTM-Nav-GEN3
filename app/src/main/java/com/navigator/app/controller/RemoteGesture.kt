package com.navigator.app.controller

import com.navigator.app.ble.BccuProtocol.HandlebarButton

/** A recognised handlebar gesture: which button, and how it was pressed. */
enum class GestureKind { SINGLE, DOUBLE, LONG }

data class RemoteGesture(val button: HandlebarButton, val kind: GestureKind)
