# Ideas for later

Features that are not part of the first version (MVP) of the hellOS app.

## Planned

### Sound mode of newer Denon/Marantz AVRs

The sound mode works for the original HEOS AVR (UPnP, see README). Newer AVR-X models use Telnet
(port 23) or HTTP instead; supporting them would be a separate extension.

### Online radio streams

- Open: collect which stream formats play on which devices (each station shows a format tag);
  HTTPS and HLS are untested, and which metadata HEOS shows for a URL stream (station name, ICY
  title).
- Enter a stream URL by hand, sort favorites (drag and drop), browse by country or genre.

## Possible extensions

From the hellOS feature list, not built yet (none of them needs a HEOS account):

- Quick selects of AVRs (show, play, assign)
- Move queue items (drag and drop)
- Play the input of another player
- Check for firmware updates, reboot the device

Out of scope on purpose: everything that needs a HEOS account sign-in (favorites, HEOS playlists,
history, streaming services, saving the queue as a playlist).
