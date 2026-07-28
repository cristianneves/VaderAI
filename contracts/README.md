# Wire contract fixtures

One JSON fixture per WebSocket message type. Both sides assert against these:

- **TypeScript** — each fixture must parse under the matching zod schema in `packages/protocol`.
- **Java** — each fixture must deserialize into the matching record in `ai.vader.server.protocol`.

A field rename on one side fails the other side's test. That is the whole point.

`packages/protocol/src/index.ts` is the source of truth for the format; these
files are the executable check that the Java mirror has not drifted.

Audio frames are raw binary and have no fixture — their layout is defined by the
`AUDIO_*` constants in the protocol package.
