package org.opentrafficmap.citstogo.bridge

/**
 * When the bridge service is allowed to keep existing, and when it has to go away.
 *
 * The rules live here, free of Android types, because the whole class of "the notification stayed
 * after I stopped the app" bugs comes from one wrong answer to a question that needs no device to
 * ask: the service is created by any control intent, goes to the foreground as soon as it exists,
 * and then has to notice for itself when it has nothing left to justify that.
 */
object ServiceLifecycle {
    /**
     * Whether the service has run out of reasons to stay in the foreground.
     *
     * Read after the command has been handled, so a command that did start something — a capture, a
     * replay, a CAM broadcast on its own, or a recording the user pointed at a file — is never mistaken
     * for an idle wake-up. A stopped bridge answers `true`, which is what makes the Stop action and a
     * removed task converge on the same ending as this check alone.
     *
     * A recording counts even with nothing feeding it yet, because the user chose a file for it: the
     * alternative is that picking a destination before pressing Start silently throws the choice away.
     */
    fun hasNothingLeftToRun(
        captureWanted: Boolean,
        replayActive: Boolean,
        camBroadcasting: Boolean,
        recordingActive: Boolean,
    ): Boolean = !captureWanted && !replayActive && !camBroadcasting && !recordingActive
}
