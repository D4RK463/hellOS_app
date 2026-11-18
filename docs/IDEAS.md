# Ideas for later

Features that are not part of the first version (MVP) of the hellOS app.

## Planned

### Sound mode of Denon/Marantz AVRs (Direct, Pure Direct, Stereo, ...)

Choose how the AVR processes the input signal, e.g. pass HDMI 1 through directly or upmix it.

- Not possible with HEOS: the HEOS CLI protocol (and hellOS) can only select the input
  (`inputs/hdmi_in_1`), not the sound mode.
- Denon/Marantz AVRs have their own control protocol next to HEOS: Telnet on port 23 (or HTTP).
  Commands, for example: `MS?` (query), `MSDIRECT`, `MSPURE DIRECT`, `MSSTEREO`, `MSAUTO`,
  `MSMCH STEREO`, `MSDOLBY DIGITAL`, `MSDTS SURROUND`.
- The format of the incoming signal (e.g. Dolby Atmos) is decided by the source; the AVR only
  decides how it is processed.
- Open question: implement it in the app or as a separate module/library next to hellOS.
- Only show it for players that are AVRs (`PlayerInfo.model()`).

## Possible extensions

From the hellOS feature list, not built yet (none of them needs a HEOS account):

- Quick selects of AVRs (show, play, assign)
- Play a stream URL (`playUrl`)
- Move queue items (drag and drop)
- Play the input of another player
- Check for firmware updates, reboot the device

Out of scope on purpose: everything that needs a HEOS account sign-in (favorites, HEOS playlists,
history, streaming services, saving the queue as a playlist).
