package com.yatesv.contacthub.engine

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yatesv.contacthub.ui.ChatMirror

/** Lets a Compose screen run inside an overlay window (outside any Activity). */
class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    init { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

/** A live, animated copy of the chat screen, shown on top while Google Messages works underneath. */
fun mirrorView(ctx: Context, convKey: String): Pair<ComposeView, OverlayOwner> {
    val owner = OverlayOwner()
    val v = ComposeView(ctx).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent { ChatMirror(convKey) }
        isClickable = true   // swallow touches so nothing underneath gets tapped
    }
    return v to owner
}
