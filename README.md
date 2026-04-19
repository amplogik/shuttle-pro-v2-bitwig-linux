# Contour ShuttlePro v2 — Bitwig Studio Extension (Linux)

A Bitwig Studio controller extension that turns a Contour Design ShuttlePro v2
into a transport / editing controller. The extension talks directly to the
device over raw USB via Bitwig's USB extension API (no HID daemon, no MIDI
bridge).

Configurable from **Settings → Controllers → Contour ShuttlePro v2**:

- Jog wheel step (normal, M1-held coarse, M2-held fine).
- Shuttle ring scale and tick interval.
- Wheel mode (Transport / Loop start / Loop end / Loop region) with a
  dedicated "Cycle Wheel Mode" action you can bind to any button.
- Each of the 13 buttons (F1–F9, B1–B4) can be assigned to a transport
  action, an editing action, a tool selection, or any no-op.

## Requirements

- Linux (tested on Ubuntu/Debian-family distros).
- Bitwig Studio 5.x or newer (extension API 25).
- Java 8+ and Maven, for building.

## Building

```sh
mvn install
```

The build produces `target/ShuttleProExtension.bwextension`. Copy it into
your Bitwig extensions directory:

```sh
cp target/ShuttleProExtension.bwextension "$HOME/Bitwig Studio/Extensions/"
```

Then in Bitwig: **Settings → Controllers → Add controller →
Contour Design → ShuttlePro v2**.

## Linux setup: udev rule

Bitwig runs the extension under your normal user account. For its libusb to
claim the ShuttlePro, your user needs raw USB access to the device. The
repository ships a udev rule that grants this:

`udev/70-shuttlepro-bitwig.rules`:

```
SUBSYSTEM=="usb", ATTRS{idVendor}=="0b33", ATTRS{idProduct}=="0030", MODE="0660", GROUP="plugdev", TAG+="uaccess"
```

`TAG+="uaccess"` gives access to the currently logged-in seat user via
logind (the right thing on a normal desktop session). The `MODE`/`GROUP`
combination is a fallback for headless or multi-user setups.

Install and reload:

```sh
sudo cp udev/70-shuttlepro-bitwig.rules /etc/udev/rules.d/
sudo udevadm control --reload
sudo udevadm trigger
```

Unplug and replug the Shuttle once after installing the rule, so the new
permissions apply to the current device session.

## Important: plug the Shuttle in *after* Bitwig starts

Bitwig's USB-based controller matching only fires on USB **hotplug** events
— it does not reliably match devices that are already plugged in at Bitwig
launch. In practice this means:

- If the Shuttle is plugged in *before* Bitwig starts, the extension won't
  initialize. It won't appear in the Controller Script Console and buttons
  / jog / shuttle do nothing.
- If you plug the Shuttle in *after* Bitwig is already running, the
  extension initializes normally, shows a popup, and works.

This is a Bitwig-side behavior, not an extension bug — the extension's
`init()` is simply never called in the cold-start case.

**Workflow options:**

1. Just leave the Shuttle unplugged until Bitwig is up, then plug it in.
2. Use the virtual-replug helper below.
3. Use the Bitwig launcher wrapper below to automate (2).

## Optional: virtual-replug helper

You can avoid physically unplugging the device by toggling its `authorized`
sysfs attribute — the kernel treats this exactly like an unplug/replug and
fires a fresh hotplug event that Bitwig picks up.

Writing to `authorized` normally requires root. Add the following line to
`/etc/udev/rules.d/70-shuttlepro-bitwig.rules` to grant write access to the
`plugdev` group:

```
ACTION=="add", SUBSYSTEM=="usb", ATTR{idVendor}=="0b33", ATTR{idProduct}=="0030", \
    RUN+="/bin/sh -c 'chgrp plugdev /sys$env{DEVPATH}/authorized && chmod g+w /sys$env{DEVPATH}/authorized'"
```

Reload udev (`sudo udevadm control --reload`) and replug the device once so
the rule applies.

Then save this as `shuttlepro-replug.sh` somewhere on your `$PATH` and make
it executable (`chmod +x`):

```sh
#!/usr/bin/env bash
# Virtual-replug the Contour ShuttlePro v2 so Bitwig's USB hotplug handler
# picks it up and initializes the extension.
set -euo pipefail

for vid_file in /sys/bus/usb/devices/*/idVendor; do
    vid=$(cat "$vid_file" 2>/dev/null || true)
    pid=$(cat "$(dirname "$vid_file")/idProduct" 2>/dev/null || true)
    if [ "$vid" = "0b33" ] && [ "$pid" = "0030" ]; then
        dev=$(dirname "$vid_file")
        echo 0 > "$dev/authorized"
        sleep 0.5
        echo 1 > "$dev/authorized"
        echo "Replugged ShuttlePro at $dev"
        exit 0
    fi
done

echo "ShuttlePro v2 not found on USB bus" >&2
exit 1
```

Run it any time after Bitwig is up and you want the extension to (re-)load.

## Optional: Bitwig launcher wrapper

If you'd like Bitwig to pick up the Shuttle automatically on launch, save
the following as `bitwig-with-shuttle.sh` (make it executable) and point
your desktop launcher at it:

```sh
#!/usr/bin/env bash
# Launch Bitwig Studio, then virtual-replug the ShuttlePro a few seconds
# later so the extension initializes.
set -euo pipefail

REPLUG_DELAY_SECONDS=8

( sleep "$REPLUG_DELAY_SECONDS" && shuttlepro-replug.sh ) &

exec bitwig-studio "$@"
```

Adjust `REPLUG_DELAY_SECONDS` if Bitwig takes longer to become ready on
your machine.

To wire this into your desktop environment, copy the system `.desktop` file
to your user overrides directory and change the `Exec=` line:

```sh
cp /usr/share/applications/bitwig-studio.desktop ~/.local/share/applications/
```

Edit `~/.local/share/applications/bitwig-studio.desktop` and change the
`Exec=` line to point at your wrapper, e.g.:

```
Exec=/home/you/bin/bitwig-with-shuttle.sh %U
```

Your application menu will now use the wrapper.

## Troubleshooting

- **Extension doesn't appear in the Controller Script Console after launch.**
  Expected if the device was plugged in before Bitwig started — see the
  section above.

- **Plugging the device in does nothing and no popup appears.**
  Check that the udev rule is installed and the device has been replugged
  once since installation. Verify with
  `ls -l /dev/bus/usb/$(lsusb | awk '/0b33:0030/{print $2"/"substr($4,1,3)}')`
  — the device node should be accessible to your user or the `plugdev`
  group.

- **Helper script says "permission denied" on `authorized`.**
  The udev rule that chgrps/chmods `authorized` hasn't fired yet for the
  current device session. Replug the device once after installing the rule.

- **Multiple ShuttlePros, or you want different bindings per project.**
  All bindings are stored as Bitwig controller preferences, so Bitwig's
  usual per-project/per-user scoping applies.

## Project layout

```
src/main/java/com/amplogik/shuttlepro/
  ShuttleProExtension.java            — controller logic, prefs, action map
  ShuttleProExtensionDefinition.java  — USB matcher + controller metadata
  ReportParser.java                   — 5-byte HID report decoder
udev/
  70-shuttlepro-bitwig.rules          — USB access rule
```

## License

No license specified yet.
