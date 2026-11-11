# Security

## Don't paste secrets. Ever.

Bug reports often need logs, and logs sometimes contain credentials.
Before posting anything, scrub:

- SDK tokens (`mgst_…`)
- `pairing.json` / `identity.json` contents (post the *keys*, never the values)
- Your LAN IPs if you care (they're RFC1918, but still)

If you already posted one, revoke the token at
[gadgets.muse.ai](https://gadgets.muse.ai/settings/sdk-tokens),
re-pair, and say so in the thread so I know the leak is closed.

## Reporting a vulnerability

Don't open a public issue for it. Ping me in the Muse Discord
`#projects` thread for this project and I'll share an email. Include
what's affected and, if you have one, a minimal repro. I'll fix first,
credit you in the release notes (unless you'd rather stay anonymous).

## Screen control (opt-in AccessibilityService + MediaProjection)

This fork adds screen control that the upstream 0.3.0 release deliberately
did not have. It is off until the user enables it, and it is a generic
primitive layer only:

- **AccessibilityService** (`ScreenControlAccessibilityService`):
  tap by coordinates / text / node index, swipe, type into the focused
  field, Back / Home / Recents, and reading the visible window tree.
  Enabled by the user in Settings > Accessibility. Every command is one
  explicit remote command from Muse; the gadget never decides what to tap
  and never taps on its own. Tap and type are refused on a screen with a
  focused/editable password field, and password text is redacted in
  `screen.read`. Scenes/automations cannot contain screen-control
  commands.
- **Screenshot** (MediaProjection): one still PNG per `screen.screenshot`
  command, after a one-time system Allow prompt. The grant lives only in
  memory and must be given again after an app restart. The image is saved
  only in the gadget workspace (`screenshots/…`) and read back with
  `file.read`. There is no screen recording, no notification listener, no
  root and no broad storage access.

If you enable Accessibility, anyone who can send Muse commands to this
gadget can tap and type on this device — that is exactly what the feature
is for, and exactly why it stays off by default. Only pair this gadget
with a Muse account you control, and turn the service off in Settings >
Accessibility when you no longer need it.
