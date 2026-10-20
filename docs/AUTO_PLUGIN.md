# Automatic start on plug-in

Plugging in the receiver already opens the app, because the manifest matches the USB attach
broadcast. Two opt-in settings on the **Settings** page, under **Receiver on plug-in**, carry that
one step further: **Auto connect** starts the capture on its own, and **Auto PCAP recording** —
which does nothing without it — also writes what the receiver hears into a new file. Both are off
until the user turns them on, so nothing is captured or written that was not asked for.

## Settings

| Setting | Stored as | Default |
| --- | --- | --- |
| Auto connect | `auto_connect_on_plugin` | off |
| Auto PCAP recording | `auto_record_on_plugin` | off |
| Recording folder | `auto_record_folder_tree_uri` | none |

The folder is a storage framework tree URI picked once with the system folder chooser; its grant is
persisted, so it survives restarts and reaches the bridge service, which names the files inside it.
A folder that was moved or deleted no longer looks like a tree URI and counts as none: the page asks
for a new one. The recording switch stays disabled while Auto connect is off, because a recording
with no capture behind it would only fill the storage with empty files.

Recordings are named `cits-YYYYMMDD-HHMMSS.pcap` after the local moment the cable went in, so a day
of plug-ins sorts itself in a file manager and two plug-ins a minute apart stay apart. The name is
the same the manual **Record PCAP** picker offers, and the file is the same radiotap PCAP described
in [PCAP_CAPTURE.md](PCAP_CAPTURE.md).

## When it fires, and when it does not

Only the plug-in that opens the app triggers a start: Android delivers the attach intent the device
filter matches as the activity's launch intent, and that is the moment these settings are meant for.
A receiver plugged in while the app is already showing is left to the **Start** button. A screen
rotation or a restore of a killed app recreates the activity with a saved state and does not start a
second capture.

`AutoRecording.plan()` decides what a plug-in may start. It holds back when

- the device in front of it is not the receiver — the manifest also matches generic CDC and vendor
  specific classes, so a charger or a sound card must not open a capture, and the Espressif vendor id
  decides;
- the app is set to Bluetooth, which has no plug-in event to key off;
- the activity already reports a connection or a replay, or the flasher holds the USB port;
- Auto PCAP recording is on but no folder was chosen. Then the connection still starts and the
  activity log says why the recording did not.

The bridge service re-checks the running and replay states before it starts anything: when the app
was just created, its status is still the default and cannot be trusted. A start that would disturb a
running capture or a replay is refused with a message and changes nothing — in particular it never
creates a file, because the file is named inside the service after the refusal is out of the way.

## When an automatic capture ends

**Stop** ends it like any other capture, and that closes the file. Removing the app from the
recent-apps list ends it the same way: the service is declared `stopWithTask`, so closing the app is
a request to stop everything, and the notification goes with it. On top of that, a capture the
plug-in started belongs to the receiver that asked for it: when the receiver stays away for ten
seconds the bridge stops the capture and itself, instead of waiting as a foreground service for a
device the user took along. The grace period covers a firmware reset or a cable that re-enumerates,
which would otherwise end a recording that was going well. A capture started by hand keeps retrying
after a replug exactly as it always did.

A capture that ends this way is not restarted. The service answers `START_NOT_STICKY`, because a
redelivered start would not have the USB handle or the settings the start intent carried and could
only put a notification up over work nobody is doing.

## Architecture

```text
attach intent    Android launches MainActivity (manifest usb_device_filter)
activity         onCreate, no saved state → AutoRecording.isReceiver(vendorId)
                     → AutoRecording.plan(settings, folder, own status, mode, flashing)
                     → notice, or selectedDeviceName + requestUsbThenStart(autoStartRecord)
                     → startBridge(autoStartRecord) → CitsBridgeService.ACTION_AUTO_START
service          autoStartBridge(): refuse while connectionWanted or replayWanted
                     → startBridge(autoStarted = true)
                     → startAutoPcap(treeUri): DocumentsContract.createDocument(name) → startPcap(uri)
detach           usbReceiver: scheduleAutoUnplugStop() → after AUTO_UNPLUG_STOP_MS without a port,
                     publishStatus(unplugged) + stopBridge() (which closes the PCAP) + stopSelf()
```

`bridge/AutoRecording.kt` is the whole decision and the naming, kept away from the activity and free
of Android types so the matrix of the two settings against the states of the app runs on the JVM.

## Tests

`AutoRecordingTest` covers the file name and its seconds resolution, reading a folder (and an SD card
folder) out of a tree URI, the vendor gate, and every row of the plan matrix: both on, recording
without a folder, recording off, and the four reasons to start nothing at all.

`AutoPluginSettingsUiTest` renders the Settings card and checks what it promises: the recording switch
disabled until Auto connect is on, the folder row and its warning while no folder is chosen, the chosen
folder named instead of the warning, and no folder asked for at all while recording stays off.
