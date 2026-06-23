# SPEC: Support Project Aether Button

**Status:** IMPLEMENTED — Chad (LunaGlass heart icon + "Support
Project Aether" label live at the Settings footer per
SettingsScreen.kt:332-373, opening the Revolut Pro destination).
**Owner:** Chad (implementation)

## Location

`SettingsScreen.kt` — bottom of the scrollable column, BELOW the existing version label and "Project Aether" branding. Last element before the bottom padding.

## Visual

```
┌──────────────────────────────┐
│                              │
│  ... existing settings ...   │
│                              │
│  v2026.05.613                │
│  Project Aether              │
│                              │
│      [♡] Support             │
│                              │
└──────────────────────────────┘
```

- Centered horizontally, Row with icon + text
- LunaGlass vector heart icon (`ic_aegis_heart.xml`) tinted `AegisCyan`, 14.dp
- "Support" text in `AegisCyan`, 12.sp, regular weight
- 4.dp spacing between icon and text
- No box, no border, no background — just a tappable Row
- Subtle — looks like a footer link, not a call to action
- 8.dp top padding from Project Aether text
- 24.dp bottom padding

## New files

### 1. `res/drawable/ic_aegis_heart.xml`

LunaGlass line-art heart. 24×24 viewport, stroke 1.8, white, round caps/joins.

```xml
<!--
  LunaGlass heart glyph — Support link icon.
  Stroke width matches the LunaGlass house value (1.8).
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000"
        android:pathData="M12,21.35 L10.55,20.03 C5.4,15.36 2,12.27 2,8.5 C2,5.41 4.42,3 7.5,3 C9.24,3 10.91,3.81 12,5.08 C13.09,3.81 14.76,3 16.5,3 C19.58,3 22,5.41 22,8.5 C22,12.27 18.6,15.36 13.45,20.03 Z" />
</vector>
```

### 2. `AegisIcon.kt` addition

```kotlin
/** Heart — Support Project Aether link in Settings footer. */
val Heart = R.drawable.ic_aegis_heart
```

## Behavior

On tap: open `https://checkout.revolut.com/pay/7d734fa1-9e39-4ec3-a7e7-f22ea98742d9` (or whatever username Tato picks) in the system browser via `Intent(Intent.ACTION_VIEW, Uri.parse(url))`.

No in-app webview. No dialog. No confirmation. Just opens the link.

## Implementation sketch

```kotlin
// After the Project Aether text, before the final Spacer:
val ctx = LocalContext.current
val donateUrl = "https://checkout.revolut.com/pay/7d734fa1-9e39-4ec3-a7e7-f22ea98742d9
Row(
    modifier = Modifier
        .padding(top = 8.dp, bottom = 24.dp)
        .clickable {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(donateUrl))
                )
            }
        },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
) {
    AegisIcon(
        icon = AegisIcons.Heart,
        contentDescription = "Support",
        tint = AegisCyan,
        modifier = Modifier.size(14.dp),
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(
        text = "Support",
        color = AegisCyan,
        fontSize = 12.sp,
    )
}
```

## Notes

- URL is a constant — extract to a companion object or BuildConfig field for easy update
- Do NOT add analytics, tracking, or usage counting on the tap
- Do NOT show a badge, dot, or any attention-drawing indicator
- Do NOT mention money, donate, or payment in the UI text — just "Support"
- The button must be invisible to anyone who doesn't scroll to the very bottom of Settings

## Commit message

```
feat: Support Project Aether link in Settings footer

LunaGlass heart icon + "Support" below version + Project Aether.
Opens Ko-fi in system browser. No tracking. Bottom of Settings
only — you don't find it unless you look.

New: ic_aegis_heart.xml (LunaGlass line-art heart)
New: AegisIcons.Heart
```
