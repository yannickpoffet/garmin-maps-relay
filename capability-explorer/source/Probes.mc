import Toybox.Activity;
import Toybox.ActivityMonitor;
import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.WatchUi;

//! Builds the capability report.
//!
//! Three complementary sources, in increasing order of trustworthiness:
//!   1. System.getDeviceSettings()  - what the device declares about itself
//!   2. the `has` operator          - which Toybox modules/symbols exist here
//!   3. actually calling the API    - the only proof that really counts
//!
//! Fields are read explicitly rather than through dynamic symbol lookup, so
//! strict type checking can verify every access. The `has` guard in front of
//! each read is what distinguishes "this device lacks the field" from "the
//! field is there but currently null".
module Probes {

    function buildAll() as Array<Row> {
        var rows = [] as Array<Row>;
        addDevice(rows);
        addModules(rows);
        addSensors(rows);
        addActivity(rows);
        addSystem(rows);
        return rows;
    }

    function header(rows as Array<Row>, title as String) as Void {
        rows.add(new Row(true, title, ""));
    }

    function entry(rows as Array<Row>, label as String, value as String) as Void {
        rows.add(new Row(false, label, value));
    }

    function yn(b as Boolean?) as String {
        if (b == null) { return "?"; }
        return b ? "yes" : "no";
    }

    //! firmwareVersion is a plain Number on hardware but an Array on the
    //! simulator, so render either shape as a dotted version string.
    function verStr(v as Object?) as String {
        if (v == null) { return "-"; }
        if (v instanceof Lang.Array) {
            var a = v as Array<Number>;
            var out = "";
            for (var i = 0; i < a.size(); i++) {
                if (i > 0) { out = out + "."; }
                out = out + a[i].toString();
            }
            return out;
        }
        return v.toString();
    }

    function str(v as Object?) as String {
        if (v == null) { return "-"; }
        return v.toString();
    }

    //! The three states that matter, kept visually distinct in the report.
    function valRow(rows as Array<Row>, label as String, present as Boolean,
                    v as Numeric?, unit as String) as Void {
        if (!present) {
            entry(rows, label, "absent");
            return;
        }
        if (v == null) {
            entry(rows, label, "no data");
            return;
        }
        if (v instanceof Lang.Float) {
            entry(rows, label, (v as Lang.Float).format("%.1f") + unit);
        } else if (v instanceof Lang.Double) {
            entry(rows, label, (v as Lang.Double).format("%.1f") + unit);
        } else {
            entry(rows, label, v.toString() + unit);
        }
    }

    // ---------------------------------------------------------------- device

    function addDevice(rows as Array<Row>) as Void {
        header(rows, "DEVICE");
        var s = System.getDeviceSettings();

        entry(rows, "part", str(s.partNumber));
        entry(rows, "firmware", verStr(s.firmwareVersion));

        var mv = s.monkeyVersion;
        if (mv != null && mv.size() >= 3) {
            entry(rows, "CIQ API", mv[0].toString() + "." + mv[1].toString()
                                   + "." + mv[2].toString());
        }

        entry(rows, "screen", s.screenWidth.toString() + "x" + s.screenHeight.toString());
        entry(rows, "shape", shapeName(s.screenShape));
        entry(rows, "touch", yn(s.isTouchScreen));
        entry(rows, "phone conn", yn(s.phoneConnected));
        entry(rows, "notifs", str(s.notificationCount));

        if (s has :requiresBurnInProtection) {
            entry(rows, "burn-in prot", yn(s.requiresBurnInProtection));
        }
        if (s has :doesDeviceSupportAudioPlayback) {
            entry(rows, "audio", yn(s.doesDeviceSupportAudioPlayback as Boolean));
        }
        if (s has :isGlanceModeEnabled) {
            entry(rows, "glances", yn(s.isGlanceModeEnabled));
        }
    }

    function shapeName(shape as Number) as String {
        if (shape == System.SCREEN_SHAPE_ROUND)      { return "round"; }
        if (shape == System.SCREEN_SHAPE_SEMI_ROUND) { return "semi-round"; }
        if (shape == System.SCREEN_SHAPE_RECTANGLE)  { return "rect"; }
        return "?";
    }

    // --------------------------------------------------------------- modules

    //! The heart of the tool: `has` asks the running VM whether a symbol exists
    //! on THIS device, which is the real per-device answer no compatibility
    //! table can give you.
    //!
    //! Three outcomes, not two. Some modules are permission-gated by app type,
    //! and for those the VM *throws* on the `has` check rather than returning
    //! false (Toybox.Media does exactly this in a watch-app). So "gated" is a
    //! distinct answer from "absent": the module exists on the device, this
    //! app type just may not touch it.
    function hasModule(sym as Symbol) as String {
        try {
            return (Toybox has sym) ? "yes" : "no";
        } catch (e) {
            return "gated";
        }
    }

    function addModules(rows as Array<Row>) as Void {
        header(rows, "API MODULES");
        entry(rows, "Sensor",       hasModule(:Sensor));
        entry(rows, "SensorHistory",hasModule(:SensorHistory));
        entry(rows, "Position",     hasModule(:Position));
        entry(rows, "ActivityMon",  hasModule(:ActivityMonitor));
        entry(rows, "ActivityRec",  hasModule(:ActivityRecording));
        entry(rows, "UserProfile",  hasModule(:UserProfile));
        entry(rows, "Communicatns", hasModule(:Communications));
        entry(rows, "Complicatns",  "gated");   // watch faces / data fields
        entry(rows, "Weather",      hasModule(:Weather));
        entry(rows, "Media",        "gated");   // ACP apps only
        entry(rows, "Ant",          hasModule(:Ant));
        entry(rows, "AntPlus",      hasModule(:AntPlus));
        entry(rows, "BluetoothLE",  hasModule(:BluetoothLowEnergy));
        entry(rows, "PersistedCnt", hasModule(:PersistedContent));
        entry(rows, "Cryptography", hasModule(:Cryptography));
        entry(rows, "Attention",    hasModule(:Attention));
        entry(rows, "Timer",        hasModule(:Timer));
        entry(rows, "Background",   "gated");   // needs (:background) annotation

        // Finer-grained: a module can exist while a capability inside it does not.
        entry(rows, "BufferedBmp",  yn(Graphics has :BufferedBitmap));
        entry(rows, "Menu2",        yn(WatchUi has :Menu2));
        entry(rows, "Storage",      yn(Application has :Storage));
    }

    // --------------------------------------------------------------- sensors

    function addSensors(rows as Array<Row>) as Void {
        header(rows, "SENSORS (live)");

        if (!(Toybox has :Sensor)) {
            entry(rows, "Sensor", "absent");
            return;
        }

        var info = Sensor.getInfo();

        valRow(rows, "heart rate", info has :heartRate,   info.heartRate,   "bpm");
        valRow(rows, "altitude",   info has :altitude,    info.altitude,    "m");
        valRow(rows, "pressure",   info has :pressure,    info.pressure,    "Pa");
        valRow(rows, "temp",       info has :temperature, info.temperature, "C");
        valRow(rows, "heading",    info has :heading,     info.heading,     "rad");
        valRow(rows, "cadence",    info has :cadence,     info.cadence,     "rpm");
        valRow(rows, "speed",      info has :speed,       info.speed,       "m/s");
        valRow(rows, "power",      info has :power,       info.power,       "W");

        // Vector sensors report an Array, so they get their own formatting.
        vecRow(rows, "accel", info has :accel, info.accel);
        vecRow(rows, "magnet", info has :mag,  info.mag);
    }

    function vecRow(rows as Array<Row>, label as String, present as Boolean,
                    v as Array<Number>?) as Void {
        if (!present) {
            entry(rows, label, "absent");
            return;
        }
        if (v == null || v.size() < 3) {
            entry(rows, label, "no data");
            return;
        }
        entry(rows, label, v[0].toString() + "," + v[1].toString() + "," + v[2].toString());
    }

    // -------------------------------------------------------------- activity

    function addActivity(rows as Array<Row>) as Void {
        header(rows, "ACTIVITY");

        if (!(Toybox has :ActivityMonitor)) {
            entry(rows, "ActivityMon", "absent");
            return;
        }

        var mi = ActivityMonitor.getInfo();
        valRow(rows, "steps",     mi has :steps,         mi.steps,         "");
        valRow(rows, "step goal", mi has :stepGoal,      mi.stepGoal,      "");
        valRow(rows, "calories",  mi has :calories,      mi.calories,      "");
        valRow(rows, "distance",  mi has :distance,      mi.distance,      "cm");
        valRow(rows, "floors",    mi has :floorsClimbed, mi.floorsClimbed, "");

        if (mi has :activeMinutesDay) {
            var am = mi.activeMinutesDay;
            entry(rows, "act min", am == null ? "no data" : str(am.total));
        }

        // Heart rate outside an activity comes from the history buffer,
        // not from Sensor.getInfo().
        if (ActivityMonitor has :getHeartRateHistory) {
            entry(rows, "hr (hist)", latestHr());
        }

        entry(rows, "in activity", yn(Activity.getActivityInfo() != null));
    }

    function latestHr() as String {
        var it = ActivityMonitor.getHeartRateHistory(1, true);
        var sample = it.next();
        if (sample == null) { return "no data"; }
        var hr = sample.heartRate;
        if (hr == null || hr == ActivityMonitor.INVALID_HR_SAMPLE) {
            return "no data";
        }
        return hr.toString() + "bpm";
    }

    // ---------------------------------------------------------------- system

    function addSystem(rows as Array<Row>) as Void {
        header(rows, "SYSTEM");
        var st = System.getSystemStats();

        entry(rows, "battery", st.battery.format("%.0f") + "%");

        if (st has :batteryInDays) {
            var d = st.batteryInDays;
            if (d != null) {
                entry(rows, "batt days", d.format("%.1f"));
            }
        }

        entry(rows, "mem used",  (st.usedMemory / 1024).toString() + "K");
        entry(rows, "mem free",  (st.freeMemory / 1024).toString() + "K");
        entry(rows, "mem total", (st.totalMemory / 1024).toString() + "K");

        if (st has :charging) {
            entry(rows, "charging", yn(st.charging));
        }
        if (st has :solarIntensity) {
            var si = st.solarIntensity;
            if (si != null) {
                entry(rows, "solar", si.toString() + "%");
            }
        }
    }
}
