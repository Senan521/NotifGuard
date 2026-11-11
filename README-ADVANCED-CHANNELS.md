# NotifGuard – Advanced Channel Manager update

## What changed
- Channel scan now lists every channel returned by Android's notification service, not only keyword matches.
- Added filters: All, Suspicious, Enabled, Disabled.
- Added search by app name, package name, channel name, and channel ID.
- Added optional system-app scanning.
- Suspicious labels are suggestions based on the configured block/allow words; users can still inspect all other channels.
- Channels are not preselected. The user must explicitly select channels before disabling them.
- Successfully disabled channels remain visible with a Disabled status.
- Updated the Material 3 theme/progress components to stable APIs and bumped app version to 0.2.

## Limitations
Android only exposes notification channels that an app has actually created and registered with the system. If an app creates a channel only after its first notification, send/receive that notification and scan again. If an app suppresses content internally or does not create a real Android notification channel, NotifGuard cannot expose a channel that Android itself does not report.

Channel enumeration/management requires Shizuku or Root mode. The instant notification listener filter remains independent.
