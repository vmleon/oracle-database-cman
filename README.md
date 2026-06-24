# Oracle CMAN-TDM Showcase

Oracle Connection Manager (CMAN) acts as a smart Oracle Net-aware proxy — it understands TNS protocol, can multiplex connections, route by service, and enforce access control at the database protocol layer. This makes it fundamentally different from a dumb TCP relay (like SOCKS5), which blindly forwards bytes with no awareness of Oracle Net sessions, service names, or connection state.

See [cman-showcase-design.md](cman-showcase-design.md) for the full architecture and roadmap.

**Status:** Foundation slice — config core and repo scaffold in place.
