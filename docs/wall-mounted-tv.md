# Wall-mounted TV

EAMedia 1.0.8 with Electrical Age 3.0.67 adds **Wall-mounted Browser Television** to the machines tab. Craft it from a Powered Browser Television and an Iron Plate. Place it against a vertical solid wall. It has an exposed wall bracket and no tabletop foot, with the screen facing into the room.

Power it with a 120/240 V appliance cord or a fixed cable on the left/bottom input. The existing browser controls, shared picture, audio, playback handoff and approved server-routed website access are the same as the tabletop television. Picture size remains 960×540 with up to 30 fps capture/relay; actual delivery depends on the clients, website and connection.

The housing is baked into terrain geometry. The live screen remains a dynamic texture. Existing TVs and jukeboxes keep their saved IDs and models. The wall TV has a separate reserved ID, `(126 << 6) + 11`, and becomes an inert hidden placeholder if EAMedia is absent. Update the addon on the server and all clients together; 1.0.8 requires the updated core.

Server-routed browsing still uses the existing approved-domain list and Minecraft connection. This update does not add new destinations or change that policy.
