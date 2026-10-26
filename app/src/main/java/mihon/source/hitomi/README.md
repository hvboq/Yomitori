# Built-in Hitomi source

The Kotlin adapter follows the native Hitomi protocol implementation in
`qwopqwop200/Aidoku` at `821a612f3648e9312cf1efafe175d4aa77d96b5f`:
`HitomiSource.swift`, `HitomiSearch.swift`, `HitomiModels.swift`, and
`HitomiGalleryRouting.swift`. Aidoku's `Docs/native-source-auth.md` specifies
the MIT option for community source ports; the full notice is in `LICENSE`.
No code from `Vendor/AidokuRunner` is included.

Android-specific changes: suspend source API, bounded metadata concurrency,
stable source/gallery/chapter identities, strict range validation, search
intersection that cannot resurrect empty results, and integration into
Mihon's extension refresh and content visibility settings.

The source ID is reserved for this fork. Do not change it or silently replace
it with an external extension ID after libraries have been created.

Protocol tests use synthetic, non-content fixtures. Passing them does not
establish that the live site's routes currently work.

The Hitomi connection option follows Aidoku's `HTTPSBypassProxy.swift`,
`HTTPSDNSResolver.swift`, and `TLSClientHello.swift` at the same commit.
Unlike the community adapter above, this app networking code is covered by
Aidoku's GPLv3 license; its attribution and license are bundled in
`assets/notices/Aidoku-Attribution.txt` and `Aidoku-GPL-3.0.txt`.

Hitomi uses dedicated encrypted DNS. DPI bypass is enabled by default and can
be disabled in the source settings. A loopback SOCKS5 relay splits the TLS
ClientHello at the SNI name, preserving handshake bytes and normal certificate
validation. Only Hitomi and its image CDN on HTTPS port 443 are allowed.
The option affects source API, cover, page, and download requests; translation
API requests and standalone WebView/browser sessions do not use this relay.
Turning it off changes new requests without canceling existing connections.

Image routing follows the current `common.js`/`gg.js` protocol: thumbnail
hash buckets select `atn` or `btn`, AVIF pages use `a1`/`a2`, and WebP pages
use `w1`/`w2`. Stored cover URLs remain stable and are rerouted at request
time. A bounded shared routing lookup avoids one lookup per thumbnail;
403/404 responses refresh the route and retry once.

Search autocomplete uses the site's character-index JSON protocol at
`tagindex.hitomi.la`, through the same source transport. Global prefixes and
all searchable namespaces (`language`, `artist`, `group`, `series`,
`character`, `tag`, `female`, `male`, `type`) use remote candidates rather
than a curated tag list. Both search bars debounce input, cancel obsolete
requests, and replace only the token at the cursor when a candidate is selected.
Multiple-word values use underscores and negative filters retain `-`.
Editing, remote candidate normalization and actual search share the same
`Char.isWhitespace` boundaries, including full-width and non-breaking spaces.
Remote names and prefix requests still reject ISO control characters before
normalization; ordinary search separators retain the editor's whitespace rules.
Responses are bounded to 256 KiB and at most 20 candidates are displayed.

The opt-in Android `HitomiConnectivityDiagnosticTest` checks suggestions,
both thumbnail routes when available, reader images, WebP availability and
TLS fragmentation. Its report contains transport facts without image files,
gallery titles or full image URLs. Live-site availability is a separate check
from the synthetic unit tests.
