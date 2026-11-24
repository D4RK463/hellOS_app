# Ideas for later

Features that are not part of the first version (MVP) of the hellOS app.

## Planned

### Sound mode of the Denon HEOS AVR (Direct, Stereo, ...) – in progress

Choose how the AVR processes the input signal, e.g. pass HDMI 1 through directly or upmix it.

- Not possible with HEOS: the HEOS CLI protocol (and hellOS) can only select the input
  (`inputs/hdmi_in_1`), not the sound mode.
- The Denon Telnet protocol (port 23) and the `/goform` HTTP interface are closed on the original
  HEOS AVR. It has a UPnP service instead: `urn:schemas-denon-com:service:ACT:1` on port 60006
  with `GetAudioConfig` / `SetAudioConfig`. The configuration contains the current `soundMode` and
  the `availableSoundModes` of the device.
- The format of the incoming signal (e.g. Dolby Atmos) is decided by the source; the AVR only
  decides how it is processed.
- Implemented in the app (`org.hellos.app.avr`, plain Java), can move into a library later.
- Newer AVR-X models use Telnet/HTTP instead; supporting them would be a separate extension.

## Possible extensions

From the hellOS feature list, not built yet (none of them needs a HEOS account):

- Quick selects of AVRs (show, play, assign)
- Play a stream URL (`playUrl`)
- Move queue items (drag and drop)
- Play the input of another player
- Check for firmware updates, reboot the device

Out of scope on purpose: everything that needs a HEOS account sign-in (favorites, HEOS playlists,
history, streaming services, saving the queue as a playlist).
