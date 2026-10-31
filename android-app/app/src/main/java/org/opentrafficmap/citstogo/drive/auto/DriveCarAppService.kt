package org.opentrafficmap.citstogo.drive.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
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
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
            }

            override fun onStop(owner: LifecycleOwner) {
                handler.removeCallbacks(refresh)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = engine.current()
        val critical = state.alerts.firstOrNull { it.priority == AlertPriority.Critical }
        if (critical != null && critical.distanceM != null && critical.distanceM!! < 250.0) {
            return criticalTemplate(state, critical)
        }
        if (state.intersection != null || state.glosa != null) {
            return navigationTemplate(state)
        }
        return mainTemplate(state)
    }

    /**
     * The driving view uses the supported NavigationTemplate expected from a navigation-category
     * app while a junction or recommendation is active. A blank map surface is intentional: this
     * app has no map provider on the head unit, and it must not fake turn-by-turn navigation.
     */
    private fun navigationTemplate(state: DriveSnapshot): Template {
        val details = Action.Builder()
            .setTitle("Status")
            .setOnClickListener {
                screenManager.push(DriveCarStatusScreen(carContext))
                null
            }
            .build()
        val infoText = buildString {
            state.signal?.let {
                append(if (it.isGreen) "Green" else if (it.isRed) "Red" else "Transition")
                append(" · group ${it.signalGroup}")
                append(" · ")
            }
            state.glosa?.targetSpeedKph?.let { append("${it.toInt()} km/h · ") }
            state.intersection?.lane?.distanceToStopLineM?.let { append("${it.toInt()} m to stop line") }
            state.primaryAlert?.let { append(" · ${it.title}") }
        }.ifBlank { "Waiting for C-ITS data" }
        val info = MessageInfo.Builder("C-ITS Drive Assist")
            .setText(infoText)
            .build()
        return NavigationTemplate.Builder()
            .setNavigationInfo(info)
            .setActionStrip(ActionStrip.Builder().addAction(details).build())
            .build()
    }

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
        val refresh = Action.Builder()
            .setTitle("Refresh")
            .setOnClickListener {
                invalidate()
                null
            }
            .build()
        return PaneTemplate.Builder(
            Pane.Builder().apply { rows.forEach { addRow(it) } }.build(),
        )
            .setTitle("C-ITS Drive Assist")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(refresh).build())
            .build()
    }

    private fun criticalTemplate(state: DriveSnapshot, alert: SafetyEvent): Template {
        val distance = alert.distanceM?.let { "${it.toInt()} m · " }.orEmpty()
        return MessageTemplate.Builder("${alert.title}\n$distance${alert.description}")
            .setTitle("Safety alert")
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle("Show status")
                    .setOnClickListener {
                        invalidate()
                        null
                    }
                    .build(),
            )
            .build()
    }

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
        state.simulated -> "Simulation (offline data)"
        !state.networkAvailable -> "No network"
        state.connection.connected -> "Connected · ${state.connection.address}"
        else -> state.connection.phase.label + (state.connection.lastError?.let { " · $it" }.orEmpty())
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
        private const val REFRESH_INTERVAL_MS = 1_000L
    }
}

/** A pushed status screen for hosts that show the navigation view first. */
class DriveCarStatusScreen(carContext: CarContext) : DriveCarScreen(carContext) {
    override fun onGetTemplate(): Template = mainTemplate(engine.current())
}
