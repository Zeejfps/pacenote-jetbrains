package dev.diffdino.pairing.ui.support

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * How long a piece of UI watches the core: the flows it collects, on the EDT, stop when it is
 * disposed, which its parent's disposal does too.
 */
class Lifetime(parent: Disposable) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    init {
        Disposer.register(parent, this)
    }

    /** A lifetime that ends with this one, or earlier when disposed itself. */
    fun child(): Lifetime = Lifetime(this)

    /** Applies [flow]'s value now, then every later change. */
    fun <T> bind(flow: StateFlow<T>, apply: (T) -> Unit) {
        var applied = flow.value
        apply(applied)
        scope.launch {
            flow.collect {
                if (it != applied) {
                    applied = it
                    apply(it)
                }
            }
        }
    }

    /** Like [bind], but a change that comes while one was just applied waits [pauseMillis] and then
     * applies the latest, so a flow that streams is not drawn once per piece. */
    fun <T> bindThrottled(flow: StateFlow<T>, pauseMillis: Long, apply: (T) -> Unit) {
        var applied = flow.value
        apply(applied)
        scope.launch {
            flow.collect {
                if (it != applied) {
                    applied = it
                    apply(it)
                    delay(pauseMillis)
                }
            }
        }
    }

    /** Runs [block] on the EDT for as long as this lifetime lasts. */
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    override fun dispose() {
        scope.cancel()
    }
}
