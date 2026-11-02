# Jukebox speakers and private Plex playback

Set a Powered Web Jukebox and its speakers to the same channel (1–9999). Each speaker selects the nearest loaded matching jukebox within 64 blocks, in the same dimension. The **Jukebox Standing Speaker** sits on the floor; the **Jukebox Ceiling Speaker** attaches under a ceiling. They are passive audio endpoints: the jukebox supplies playback and consumes electrical power. Set each speaker's volume independently. Sound attenuates from each speaker over 32 blocks and uses Minecraft's Records and master volume sliders.

HTTPS files and YouTube retain the jukebox's existing Play/Stop controls. A jukebox uses one download/decoder per listening client, shared by its linked speakers. Speakers do not load chunks to locate a jukebox.

## Plex Web

Use **Private Plex / web player** on the jukebox to open Plex Web, or **Open browser controls** to enter another approved web player. Sign in through that private browser. This is a Plex Web player, not an embedded Plexamp application or a native Plexamp remote endpoint.

Start a track with the page's own Play control after the page loads. Plex keeps control of its own queue and playback position; the TV resume clock is not applied to private jukebox players.

Only the operator hosts this browser. The jukebox has no world-visible browser display. Other listeners receive audio packets; jukebox video frames are rejected by the relay. Login redirects and subsequent browser addresses are kept inside the operator's client, rather than published as the jukebox's shared address. The hosting player must remain connected and within 32 blocks of the jukebox for playback to continue.

Plex playback depends on the server's web policy and Plex's browser/codec requirements. Autoplay pages that finish building their Web Audio routing before capture attaches can remain silent; the native check covers interactive track selection and Play. An actual Plex account/library is needed to verify playback against a particular server. Browser authentication stays in the player's local browser profile.

## Local Plex Media Server

Add exact endpoints to the Minecraft server's `config/eamedia-plex-servers.txt`, then restart. For example:

```
192.168.1.20:32400
```

Open the private browser's **Local Plex** button, or enter `http://192.168.1.20:32400/web/`. Add the exact `plex.direct` hostname and port if Plex redirects to that address. This grants access only to those explicit endpoints; it does not grant access to the rest of the LAN. Link-local/metadata addresses remain unavailable.

The Plex server must be reachable **from the Minecraft server**, because browser connections travel through the existing authenticated Minecraft web route. A player's unrelated home LAN is not automatically reachable from a hosted Minecraft server. Existing server proxy settings still apply. Plex public sites must also be permitted by `config/eamedia-web-domains.txt` (new default files include `plex.tv` and `plexapp.com`; older files should add those entries or retain an existing `*` policy).

Use Electrical Age 3.0.74 or newer with EA Media 1.0.12 on server and clients. Its protocol version is 9; older active media clients cannot mix with it. ELN without the addon reserves hidden placeholders for the speaker IDs so saves remain recoverable.
