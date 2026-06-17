package space.manus.nacre.ime.pointer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object FlexPointerBridge {
    var isAccessibilityReady by mutableStateOf(false)
        private set

    var isPointerVisible by mutableStateOf(false)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    private var service: NacrePointerAccessibilityService? = null
    private var imeWantsPointer = false

    fun attach(accessibilityService: NacrePointerAccessibilityService) {
        service = accessibilityService
        isAccessibilityReady = true
        lastError = null
        accessibilityService.setPointerVisible(imeWantsPointer)
        isPointerVisible = imeWantsPointer
    }

    fun detach(accessibilityService: NacrePointerAccessibilityService) {
        if (service == accessibilityService) {
            service = null
            isAccessibilityReady = false
            isPointerVisible = false
        }
    }

    fun setImePointerVisible(visible: Boolean) {
        imeWantsPointer = visible
        val activeService = service
        if (activeService == null) {
            isPointerVisible = false
            return
        }
        val ok = runCatching {
            activeService.setPointerVisible(visible)
        }.getOrDefault(false)
        isPointerVisible = ok && visible
        if (!ok) lastError = "Pointer overlay unavailable"
    }

    fun moveBy(dx: Float, dy: Float): Boolean {
        val activeService = service ?: return false
        return runCatching {
            activeService.movePointerBy(dx, dy)
        }.onFailure {
            lastError = it.message
        }.getOrDefault(false)
    }

    fun tap(): Boolean {
        val activeService = service ?: return false
        return runCatching {
            activeService.tapPointer()
        }.onFailure {
            lastError = it.message
        }.getOrDefault(false)
    }

    fun longPress(): Boolean {
        val activeService = service ?: return false
        return runCatching {
            activeService.longPressPointer()
        }.onFailure {
            lastError = it.message
        }.getOrDefault(false)
    }

    fun scrollBy(deltaY: Float): Boolean {
        val activeService = service ?: return false
        return runCatching {
            activeService.scrollAtPointer(deltaY)
        }.onFailure {
            lastError = it.message
        }.getOrDefault(false)
    }
}
