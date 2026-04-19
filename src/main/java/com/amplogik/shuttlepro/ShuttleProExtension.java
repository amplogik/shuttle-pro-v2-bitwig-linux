package com.amplogik.shuttlepro;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.bitwig.extension.api.MemoryBlock;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.Action;
import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.Preferences;
import com.bitwig.extension.controller.api.SettableEnumValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.controller.api.Transport;
import com.bitwig.extension.controller.api.UsbDevice;
import com.bitwig.extension.controller.api.UsbInputPipe;

public class ShuttleProExtension extends ControllerExtension {
    private static final int REPORT_SIZE = 5;

    // Jog step options, label → beats. 1 bar = 4 beats (4/4 assumed).
    private static final String[] JOG_STEPS = {
            "1/64 bar", "1/32 bar", "1/16 bar", "1/8 bar",
            "1/4 bar (1 beat)", "1/2 bar", "1 bar"
    };

    // Action options for each configurable button.
    private static final String ACT_NONE             = "None";
    private static final String ACT_PLAY             = "Play/Pause";
    private static final String ACT_STOP             = "Stop";
    private static final String ACT_RECORD           = "Record";
    private static final String ACT_REWIND           = "Rewind";
    private static final String ACT_FAST_FORWARD     = "Fast Forward";
    private static final String ACT_TAP_TEMPO        = "Tap Tempo";
    private static final String ACT_TOGGLE_LOOP      = "Toggle Loop";
    private static final String ACT_TOGGLE_METRONOME = "Toggle Metronome";
    private static final String ACT_RETURN_TO_ZERO   = "Return to Zero";
    private static final String ACT_UNDO             = "Undo";
    private static final String ACT_REDO             = "Redo";
    private static final String ACT_CUT              = "Cut";
    private static final String ACT_COPY             = "Copy";
    private static final String ACT_PASTE            = "Paste";
    private static final String ACT_DUPLICATE        = "Duplicate";
    private static final String ACT_DELETE           = "Delete";
    private static final String ACT_SPLIT            = "Split at Playhead";
    private static final String ACT_SET_LOOP_IN     = "Set Loop In at Playhead";
    private static final String ACT_SET_LOOP_OUT    = "Set Loop Out at Playhead";
    private static final String ACT_ADD_CUE_MARKER   = "Add Cue Marker";
    private static final String ACT_PREV_CUE_MARKER  = "Previous Cue Marker";
    private static final String ACT_NEXT_CUE_MARKER  = "Next Cue Marker";
    private static final String ACT_CYCLE_WHEEL_MODE = "Cycle Wheel Mode";

    private static final String[] ACTIONS = {
            ACT_NONE,
            ACT_PLAY, ACT_STOP, ACT_RECORD,
            ACT_REWIND, ACT_FAST_FORWARD, ACT_TAP_TEMPO,
            ACT_TOGGLE_LOOP, ACT_TOGGLE_METRONOME, ACT_RETURN_TO_ZERO,
            ACT_UNDO, ACT_REDO,
            ACT_CUT, ACT_COPY, ACT_PASTE, ACT_DUPLICATE, ACT_DELETE,
            ACT_SPLIT,
            ACT_SET_LOOP_IN, ACT_SET_LOOP_OUT,
            ACT_ADD_CUE_MARKER, ACT_PREV_CUE_MARKER, ACT_NEXT_CUE_MARKER,
            ACT_CYCLE_WHEEL_MODE
    };

    // Wheel mode options. Order is the cycle order.
    private static final String MODE_TRANSPORT   = "Transport";
    private static final String MODE_LOOP_START  = "Loop start";
    private static final String MODE_LOOP_END    = "Loop end (length)";
    private static final String MODE_LOOP_REGION = "Loop region (move both)";
    private static final String[] WHEEL_MODES = {
            MODE_TRANSPORT, MODE_LOOP_START, MODE_LOOP_END, MODE_LOOP_REGION
    };

    // Order matches the Controller Settings panel layout.
    private static final String[] BUTTON_LABELS = {
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9",
            "B1", "B2", "B3", "B4"
    };
    private static final int[] BUTTON_MASKS = {
            ReportParser.F1, ReportParser.F2, ReportParser.F3,
            ReportParser.F4, ReportParser.F5, ReportParser.F6,
            ReportParser.F7, ReportParser.F8, ReportParser.F9,
            ReportParser.B1, ReportParser.B2, ReportParser.B3, ReportParser.B4
    };
    private static final String[] BUTTON_DEFAULTS = {
            ACT_PLAY, ACT_STOP, ACT_RECORD, ACT_NONE, ACT_NONE, ACT_NONE,
            ACT_NONE, ACT_NONE, ACT_NONE,
            ACT_NONE, ACT_NONE, ACT_NONE, ACT_NONE
    };

    private Transport transport;
    private Application application;
    private UsbInputPipe pipe;
    private MemoryBlock buffer;
    private final byte[] readBuffer = new byte[REPORT_SIZE];

    private final ReportParser prev = new ReportParser();
    private final ReportParser cur  = new ReportParser();
    private boolean haveBaseline = false;
    private boolean shuttleTickScheduled = false;

    // Preferences
    private SettableEnumValue jogNormalPref, jogCoarsePref, jogFinePref;
    private SettableRangedValue shuttleScalePref, shuttleTickMsPref;
    private SettableEnumValue wheelModePref;
    private SettableEnumValue[] buttonPrefs = new SettableEnumValue[BUTTON_LABELS.length];

    // Button label → runnable
    private final Map<String, Runnable> actionMap = new HashMap<>();

    protected ShuttleProExtension(ShuttleProExtensionDefinition def, ControllerHost host) {
        super(def, host);
    }

    @Override
    public void init() {
        final ControllerHost host = getHost();
        transport   = host.createTransport();
        application = host.createApplication();

        setupPreferences(host.getPreferences());
        setupActionMap();

        UsbDevice device = (UsbDevice) host.hardwareDevice(0);
        pipe   = (UsbInputPipe) device.iface(0).pipe(0);
        buffer = host.allocateMemoryBlock(REPORT_SIZE);

        host.showPopupNotification("Contour ShuttlePro v2 initialized");
        scheduleRead();
    }

    @Override public void exit()  { }
    @Override public void flush() { }

    private void setupActionMap() {
        actionMap.put(ACT_NONE,             () -> {});
        actionMap.put(ACT_PLAY,             transport::togglePlay);
        actionMap.put(ACT_STOP,             transport::stop);
        actionMap.put(ACT_RECORD,           transport::record);
        actionMap.put(ACT_REWIND,           transport::rewind);
        actionMap.put(ACT_FAST_FORWARD,     transport::fastForward);
        actionMap.put(ACT_TAP_TEMPO,        transport::tapTempo);
        actionMap.put(ACT_TOGGLE_LOOP,      () -> transport.isArrangerLoopEnabled().toggle());
        actionMap.put(ACT_TOGGLE_METRONOME, () -> transport.isMetronomeEnabled().toggle());
        actionMap.put(ACT_RETURN_TO_ZERO,   () -> transport.setPosition(0));
        actionMap.put(ACT_UNDO,             application::undo);
        actionMap.put(ACT_REDO,             application::redo);
        actionMap.put(ACT_CUT,              application::cut);
        actionMap.put(ACT_COPY,             application::copy);
        actionMap.put(ACT_PASTE,            application::paste);
        actionMap.put(ACT_DUPLICATE,        application::duplicate);
        actionMap.put(ACT_DELETE,           application::remove);
        actionMap.put(ACT_SPLIT,            namedAction("split_at_play_position"));
        actionMap.put(ACT_SET_LOOP_IN,      this::setLoopInAtPlayhead);
        actionMap.put(ACT_SET_LOOP_OUT,     this::setLoopOutAtPlayhead);
        actionMap.put(ACT_ADD_CUE_MARKER,   transport::addCueMarkerAtPlaybackPosition);
        actionMap.put(ACT_PREV_CUE_MARKER,  transport::jumpToPreviousCueMarker);
        actionMap.put(ACT_NEXT_CUE_MARKER,  transport::jumpToNextCueMarker);
        actionMap.put(ACT_CYCLE_WHEEL_MODE, this::cycleWheelMode);
    }

    private void setupPreferences(Preferences prefs) {
        final String wheelsCat  = "Wheels";
        final String jogCat     = "Jog wheel";
        final String shuttleCat = "Shuttle ring";
        final String buttonsCat = "Buttons";

        wheelModePref = prefs.getEnumSetting("Wheel mode", wheelsCat, WHEEL_MODES, MODE_TRANSPORT);

        jogNormalPref = prefs.getEnumSetting("Normal step",          jogCat, JOG_STEPS, "1/16 bar");
        jogCoarsePref = prefs.getEnumSetting("Coarse step (M1 held)", jogCat, JOG_STEPS, "1/4 bar (1 beat)");
        jogFinePref   = prefs.getEnumSetting("Fine step (M2 held)",   jogCat, JOG_STEPS, "1/64 bar");

        shuttleScalePref  = prefs.getNumberSetting(
                "Scrub scale (beats per tick per unit)", shuttleCat,
                0.05, 2.0, 0.05, "beats", 0.25);
        shuttleTickMsPref = prefs.getNumberSetting(
                "Scrub tick interval", shuttleCat,
                20, 200, 10, "ms", 50);

        for (int i = 0; i < BUTTON_LABELS.length; i++) {
            buttonPrefs[i] = prefs.getEnumSetting(
                    BUTTON_LABELS[i], buttonsCat, ACTIONS, BUTTON_DEFAULTS[i]);
        }
    }

    private double jogBeats(String option) {
        switch (option) {
            case "1/64 bar":         return 0.0625;
            case "1/32 bar":         return 0.125;
            case "1/16 bar":         return 0.25;
            case "1/8 bar":          return 0.5;
            case "1/4 bar (1 beat)": return 1.0;
            case "1/2 bar":          return 2.0;
            case "1 bar":            return 4.0;
            default:                 return 0.25;
        }
    }

    private Runnable namedAction(String id) {
        return () -> {
            Action a = application.getAction(id);
            if (a != null) a.invoke();
            else getHost().errorln("ShuttlePro: unknown Bitwig action id '" + id + "'");
        };
    }

    private void setLoopInAtPlayhead() {
        double playPos = transport.playPosition().get();
        double oldEnd  = transport.arrangerLoopStart().get()
                       + transport.arrangerLoopDuration().get();
        transport.arrangerLoopStart().setRaw(playPos);
        double newDuration = Math.max(oldEnd - playPos, 0.25);
        transport.arrangerLoopDuration().setRaw(newDuration);
    }

    private void setLoopOutAtPlayhead() {
        double playPos = transport.playPosition().get();
        double start   = transport.arrangerLoopStart().get();
        double newDuration = Math.max(playPos - start, 0.25);
        transport.arrangerLoopDuration().setRaw(newDuration);
    }

    private void cycleWheelMode() {
        String cur = wheelModePref.get();
        int idx = 0;
        for (int i = 0; i < WHEEL_MODES.length; i++) {
            if (WHEEL_MODES[i].equals(cur)) { idx = i; break; }
        }
        String next = WHEEL_MODES[(idx + 1) % WHEEL_MODES.length];
        wheelModePref.set(next);
        getHost().showPopupNotification("Wheel mode: " + next);
    }

    private void applyWheelDelta(double beats) {
        switch (wheelModePref.get()) {
            case MODE_TRANSPORT:
                transport.incPosition(beats, false);
                break;
            case MODE_LOOP_START: {
                double newStart = Math.max(transport.arrangerLoopStart().get() + beats, 0);
                transport.arrangerLoopStart().setRaw(newStart);
                break;
            }
            case MODE_LOOP_END: {
                double newDuration = Math.max(transport.arrangerLoopDuration().get() + beats, 0.25);
                transport.arrangerLoopDuration().setRaw(newDuration);
                break;
            }
            case MODE_LOOP_REGION: {
                double newStart = Math.max(transport.arrangerLoopStart().get() + beats, 0);
                transport.arrangerLoopStart().setRaw(newStart);
                break;
            }
        }
    }

    private void scheduleRead() {
        pipe.readAsync(buffer, amount -> {
            if (amount >= REPORT_SIZE) onReport();
            scheduleRead();
        }, 0);
    }

    private void onReport() {
        ByteBuffer bb = buffer.createByteBuffer();
        bb.get(readBuffer, 0, REPORT_SIZE);
        cur.parse(readBuffer);

        if (!haveBaseline) {
            prev.copyFrom(cur);
            haveBaseline = true;
            return;
        }

        handleButtons();
        handleJog();
        handleShuttle();

        prev.copyFrom(cur);
    }

    private boolean pressed(int mask) {
        return (cur.buttons & mask) != 0 && (prev.buttons & mask) == 0;
    }

    private boolean held(int mask) {
        return (cur.buttons & mask) != 0;
    }

    private void handleButtons() {
        for (int i = 0; i < BUTTON_LABELS.length; i++) {
            if (pressed(BUTTON_MASKS[i])) {
                Runnable action = actionMap.get(buttonPrefs[i].get());
                if (action != null) action.run();
            }
        }
    }

    private void handleJog() {
        if (cur.jogCounter == prev.jogCounter) return;
        int raw = (cur.jogCounter - prev.jogCounter) & 0xFF;
        int delta = raw >= 128 ? raw - 256 : raw;

        String stepOpt = jogNormalPref.get();
        if (held(ReportParser.M1))      stepOpt = jogCoarsePref.get();
        else if (held(ReportParser.M2)) stepOpt = jogFinePref.get();

        applyWheelDelta(delta * jogBeats(stepOpt));
    }

    private void handleShuttle() {
        if (cur.shuttle != 0 && !shuttleTickScheduled) {
            shuttleTickScheduled = true;
            tickShuttle();
        }
    }

    private void tickShuttle() {
        if (cur.shuttle == 0) {
            shuttleTickScheduled = false;
            return;
        }
        applyWheelDelta(cur.shuttle * shuttleScalePref.getRaw());
        getHost().scheduleTask(this::tickShuttle, (long) shuttleTickMsPref.getRaw());
    }
}
