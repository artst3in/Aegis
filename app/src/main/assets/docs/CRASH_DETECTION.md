# Vehicle Crash Detection

Accelerometer-based vehicle impact detection. If you are in a moving vehicle and the phone detects a sudden deceleration consistent with a collision, a 30-second countdown begins. If you do not dismiss it, SOS fires automatically.

## How it works

The system monitors accelerometer data for impact signatures while the device is in motion (GPS velocity above a threshold). A sharp deceleration spike beyond the configured sensitivity triggers the countdown.

## The countdown

A full-screen "I'm OK" button appears with a 30-second timer. Tap it and nothing happens — the detection resets. If the timer expires without a tap, the system assumes you cannot respond and fires SOS to all Trusted and Emergency contacts.

## Sensitivity

Configurable in the crash detection settings. Higher sensitivity catches smaller impacts but may false-fire on speed bumps or potholes. Lower sensitivity requires a more violent deceleration to trigger.

## Why it's experimental

Field testing is ongoing. The acceleration thresholds work in controlled conditions but real-world driving produces a wide range of deceleration events. Until the model is validated across vehicle types and road conditions, this feature lives behind the experimental gate.

## Enabling

Settings → Experimental (7-tap unlock) → Vehicle crash detection toggle → Sensitivity settings.
