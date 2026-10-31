package org.opentrafficmap.citstogo.drive.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.opentrafficmap.citstogo.CitsApplication
import org.opentrafficmap.citstogo.R
import org.opentrafficmap.citstogo.drive.engine.DriveEngine
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot
import org.opentrafficmap.citstogo.drive.glosa.GlosaStatus
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.safety.SafetyEvent
import org.opentrafficmap.citstogo.intersection.MovementPhaseState

/**
 * Android Auto entry point.
 *
 * The app declares the official navigation category because its purpose is a glanceable,
 * position-aware driving aid. It uses only supported Car App Library templates (Pane/Message) and
 * never tries to project a custom phone layout. A projection host that does not allow unknown
 * apps still refuses the service; that is a host policy and is documented in the README.
 *
 * `ALLOW_ALL_HOSTS_VALIDATOR` is the documented testing validator for apps that are sideloaded or
 * not yet reviewed for Play distribution. It does not bypass template restrictions.
 */
class DriveCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DriveCarSession()
}

class DriveCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DriveCarScreen(carContext)
}

open class DriveCarScreen(carContext: CarContext) : Screen(carContext) {
    protected val engine: DriveEngine
        get() = (carContext.applicationContext as CitsApplication).container.driveEngine
    private val handler = Handler(Looper.getMainLooper())
    private var lastFingerprint: String? = null

    /**
     * NavigationTemplate is a map-backed template. The app does not have a turn-by-turn map, but
     * providing a valid surface callback keeps the official navigation contract intact instead of
     * leaving the host to report a generic error for a missing surface.
     */
    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) = Unit

        override fun onVisibleAreaChanged(visibleArea: Rect) = Unit
        override fun onStableAreaChanged(stableArea: Rect) = Unit
        override fun onSurfaceDestroyed(container: SurfaceContainer) = Unit
        override fun onScroll(distanceX: Float, distanceY: Float) = Unit
        override fun onFling(velocityX: Float, velocityY: Float) = Unit
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = Unit
        override fun onClick(x: Float, y: Float) = Unit
    }
    private val refresh = object : Runnable {
        override fun run() {
            val fingerprint = fingerprint(engine.current())
            if (fingerprint != lastFingerprint) {
                lastFingerprint = fingerprint
                invalidate()
            }
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                setSurfaceCallback(surfaceCallback)
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
            }

            override fun onStop(owner: LifecycleOwner) {
                handler.removeCallbacks(refresh)
                setSurfaceCallback(null)
            }
        })
    }

    private fun setSurfaceCallback(callback: SurfaceCallback?) {
        runCatching {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
        }.onFailure { Log.w(TAG, "Unable to register the Android Auto surface callback", it) }
    }

    override fun onGetTemplate(): Template {
        return try {
            drivingTemplate(engine.current())
        } catch (e: Exception) {
            // The host shows a generic "unknown error" if a template throws. Keep a valid,
            // supported template on screen and leave the detailed cause in logcat.
            Log.e(TAG, "Unable to build Android Auto template", e)
            MessageTemplate.Builder("C-ITS Drive Assist\nDriving status is temporarily unavailable.")
                .setTitle("C-ITS Drive Assist")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
    }

    /**
     * The driving view uses the supported NavigationTemplate expected from a navigation-category
     * app. The template type is kept stable so the host never has to switch task types or exhaust
     * its template quota; the map surface stays empty because this app has no head-unit map.
     */
    private fun drivingTemplate(state: DriveSnapshot): Template {
        val details = Action.Builder()
            .setTitle("Status")
            .setOnClickListener {
                screenManager.push(DriveCarStatusScreen(carContext))
                null
            }
            .build()
        val refreshAction = Action.Builder()
            .setTitle("Refresh")
            .setOnClickListener {
                invalidate()
                null
            }
            .build()
        val infoText = buildString {
            append(connectionLabel(state))
            state.signal?.let {
                append(" · ")
                append(if (it.isGreen) "Green" else if (it.isRed) "Red" else "Transition")
                append(" SG ${it.signalGroup}")
                it.conservativeEndSeconds?.let { seconds -> append(" ${seconds.toInt()}s") }
            }
            state.glosa?.targetSpeedKph?.let { append(" · ${it.toInt()} km/h") }
            state.intersection?.lane?.distanceToStopLineM?.let { append(" · ${it.toInt()} m") }
            state.primaryAlert?.let { append(" · ${it.priority.name}: ${it.title}") }
        }
        val info = MessageInfo.Builder("C-ITS Drive Assist")
            .setText(infoText)
            .build()
        return NavigationTemplate.Builder()
            .setNavigationInfo(info)
            .setActionStrip(ActionStrip.Builder().addAction(details).addAction(refreshAction).build())
            .build()
    }

    /** Status detail is a pushed screen reached only by an explicit driver action. */
    protected fun mainTemplate(state: DriveSnapshot): Template {
        val rows = mutableListOf<Row>()
        rows += row("C-ITS link", connectionLabel(state), state.hasActiveConnection)
        state.position?.speedKph?.let { rows += row("Speed", "${it.toInt()} km/h", true) }
        state.intersection?.let { match ->
            rows += row(
                "Junction",
                match.lane.intersectionName?.takeIf { it.isNotBlank() } ?: match.lane.intersectionKey.toString(),
                true,
            )
            rows += row("Stop line", "${match.lane.distanceToStopLineM.toInt()} m", true)
        }
        state.signal?.let { signal ->
            rows += row(
                "Signal",
                "${signal.state.label} · group ${signal.signalGroup}",
                signal.isGreen,
            )
        }
        state.glosa?.let { glosa ->
            when {
                glosa.targetSpeedKph != null -> rows += row(
                    "Advisory",
                    "${glosa.targetSpeedKph.toInt()} km/h · ${glosa.advisory.name.lowercase().replace('_', ' ')}",
                    glosa.status == GlosaStatus.Available,
                )
                else -> rows += row("Advisory", glosa.reason, false)
            }
        } ?: state.glosaSuppressedBy?.let { rows += row("Advisory", it, false) }
        state.primaryAlert?.let { alert -> rows += row("Safety", alertRowText(alert), false) }
        if (rows.isEmpty()) {
            rows += row("C-ITS Drive Assist", "Waiting for C-ITS data", false)
        }
        return PaneTemplate.Builder(
            Pane.Builder().apply { rows.forEach { addRow(it) } }.build(),
        )
            .setTitle("C-ITS Drive Assist")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun fingerprint(state: DriveSnapshot): String = listOf(
        state.connection.phase.name,
        state.connection.connected,
        state.simulated,
        state.signal?.state?.name,
        state.signal?.signalGroup,
        state.signal?.conservativeEndSeconds?.div(5),
        state.glosa?.targetSpeedKph?.toInt(),
        state.glosaSuppressedBy,
        state.primaryAlert?.actionId,
        state.primaryAlert?.status,
        state.intersection?.lane?.distanceToStopLineM?.div(10)?.toInt(),
        state.intersection?.lane?.laneId,
        state.position?.speedKph?.toInt(),
    ).joinToString("|")

    private fun row(title: String, text: String, positive: Boolean): Row =
        Row.Builder()
            .setTitle(title)
            .addText(text)
            .apply {
                if (positive) {
                    setImage(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_launcher_foreground),
                        ).build(),
                    )
                }
            }
            .build()

    private fun connectionLabel(state: DriveSnapshot): String = when {
        state.simulated -> "Simulation"
        !state.networkAvailable -> "No network"
        state.connection.connected -> "Connected"
        else -> state.connection.phase.label
    }

    private fun alertRowText(alert: SafetyEvent): String = buildString {
        append(alert.priority.name)
        append(" · ")
        append(alert.title)
        alert.distanceM?.let { append(" · ${it.toInt()} m") }
        if (alert.status != org.opentrafficmap.citstogo.drive.safety.SafetyEventStatus.New &&
            alert.status != org.opentrafficmap.citstogo.drive.safety.SafetyEventStatus.Updated
        ) {
            append(" · ")
            append(alert.status.label)
        }
    }

    companion object {
        private const val TAG = "DriveCarApp"
        private const val REFRESH_INTERVAL_MS = 2_000L
    }
}

/** A pushed status screen for hosts that show the navigation view first. */
class DriveCarStatusScreen(carContext: CarContext) : DriveCarScreen(carContext) {
    override fun onGetTemplate(): Template = mainTemplate(engine.current())
}
