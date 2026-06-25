# Snatch Detection

Accelerometer-based grab detection. If someone yanks the phone from your hand or off a surface, the sudden acceleration spike triggers SOS.

## How it works

The accelerometer monitors for impact signatures: a sharp acceleration spike (the grab) followed by sustained motion (the getaway). The threshold distinguishes a deliberate snatch from an ordinary bump or drop.

## Why it's experimental

The heuristic false-fires on ordinary jolts — a phone dropped on a hard surface, thrown onto a bed, or knocked off a table produces acceleration spikes similar to a snatch. Until the detection model is refined, this feature lives behind the experimental gate to avoid false SOS alerts.

## Enabling

Settings → Experimental (7-tap unlock) → Snatch detection toggle.

When triggered, SOS fires to all Trusted and Emergency contacts with GPS, audio, and camera — same as a manual SOS.
