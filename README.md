# jmail

**A mobile email app (Android) that speaks only the new protocols: JMAP for
mail, Sieve for server-side filters, and OpenPGP built right into the app.**

- **JMAP only** (RFC 8620 / 8621). No IMAP, no POP3, no SMTP: reading,
  searching, flags, folders, drafts and sending all go over JMAP on HTTPS.
- **Sieve** filters edited on the phone over JMAP Sieve (RFC 9661): list,
  edit, validate on the server, save, activate, delete, plus a small rule
  builder.
- **PGP built in**: Bouncy Castle inside the app. No OpenKeychain, no other
  app in between. Reads PGP/MIME and inline PGP, encrypts and signs what you
  send, and reads mail a server keeps encrypted at rest with your key.

Your server needs JMAP (and JMAP Sieve for the filters screen). Developed and
tested against [Stalwart](https://stalw.art/) 0.13.4 (session discovery also
against 0.16); other JMAP servers should work for mail, but have not been
tested.

Kotlin / Jetpack Compose, package `io.github.th3rumbl3m4t0r.jmail`, minSdk 28
(Android 9), target/compileSdk 35. License: **GPL-3.0-or-later** (see
[License](#license)).

## Install

Download the APK from [`releases/`](releases/) and install it (allow installs
from unknown sources). Signing certificate SHA-256:

    d2:1a:bf:9e:75:30:24:ba:46:60:f4:ed:44:d8:2a:30:32:00:c7:0d:50:e5:e1:84:bf:f6:03:94:c6:92:e8:b2

Sign in with your server's address (e.g. `mail.example.com`), user name and
password (an app password if your account has one). The password is kept on
the phone only, encrypted with an Android keystore key.

## What it does

- **list**: one folder at a time (the folder button opens the list of folders
  with unread counts), newest first, unread rows marked with the accent, `pgp` /
  `sig` / `att` / `flag` marks, a preview line (none for encrypted mail: the
  server has none). `more` fetches the next 50. Server-side `search` in the folder.
- **message**: headers (`headers` under actions shows them all, raw), the PGP
  line (`encrypted · signed by …`, with a warning when the signature is bad,
  the key unknown, or the signer isn't the sender), the body, attachments with
  `open` and `save` (to Downloads); `reply`, `reply all`, `forward`, `unread`,
  `flag`, `move`, `trash` (`delete` with a "sure?" when it is in the trash
  already). A draft opens in the editor.
- **html**: rendered in a WebView with JavaScript, file and content access off
  and **no network**: images and other online content stay blocked until you
  press `load online content` (`always for <domain>` adds the sender's domain to
  the allow list under settings → html mail). `cid:` images come from the
  message itself. Links open in the browser. `text` switches to the text
  rendering; the junk folder shows text by default.
- **write**: a free `from` field (any of your addresses, catch-all aliases
  included), to / cc / bcc, subject, text, file attachments; `encrypt` is on
  whenever every recipient has a key, `sign` whenever you have one (both can be
  turned off); the line under them says what will happen. `save draft` keeps a
  draft on the server, encrypted to your own key when you have one. `mailto:`
  links open the editor. A reply is written from the address the message was
  sent to, so you answer from whatever alias the sender used.
- **your addresses**: the server only knows its identities. Catch-all aliases
  are inferred from received mail: an address that was the only recipient of
  a message is one of yours, and so is its domain. Domains can also be listed
  under settings → sending. A `from` whose domain is none of these gets a
  warning, nothing more.
- **keys** (settings → keys): your private key (import by pasting the armored
  key, from a file, or by sharing a key file to the app), other people's
  public keys, `copy public key`, remove. Your key is stored encrypted with an
  Android keystore key; the passphrase is kept the same way if "remember
  passphrase" is on, otherwise for the session only.
- **notifications**: new mail, every 15 minutes (WorkManager) and whenever the
  app opens; per folder under settings → notifications (inbox only by default).
- **sieve rules** (settings → filters): the scripts on the server (one is
  active at a time), a plain text editor, validation on the server (errors come
  back with line and column), save, activate, delete, new scripts. `add a rule`
  builds one `if` block (from / to / subject / any header; contains / is /
  matches; move to a folder, mark read, flag, discard, redirect) and appends it
  with the `require` line kept in step; the text can be edited before saving.
- **look**: "x11": night / day palettes, one adjustable accent, square
  corners, monospace, 1px borders, no animations.

Decrypted text and attachments are held in memory (a dozen messages) while the
app runs and never written to the phone. What is cached in the database: folder
list and message headers (sender, subject, date, flags, preview, size).

## PGP

- **Reading**: PGP/MIME `multipart/encrypted` (RFC 3156), which is also what
  Stalwart's encryption at rest produces (headers in the clear, body in
  `encrypted.asc`, no signature): the ciphertext part is downloaded, decrypted
  with your key, the result parsed as MIME (text, HTML, attachments). A
  `multipart/signed` inside, or a signature packet inside the ciphertext, is
  verified against the keys you hold. `multipart/signed` messages are verified
  over the raw bytes of the signed part. Inline PGP (`-----BEGIN PGP MESSAGE-----`
  in the text) is decrypted too.
- **Writing**: AES-256 with integrity protection, compressed, encrypted to each
  recipient's encryption subkey and to your own, signed with your signing
  subkey (SHA-256). Signed-only mail is `multipart/signed` with a detached
  signature (`micalg=pgp-sha256`). Checked both ways against GnuPG 2.4.
- Keys: anything Bouncy Castle 1.79 reads (RSA, Ed25519/X25519, ECDSA/ECDH).
  The server does not encrypt what you upload (drafts, sent copies), so the app
  encrypts those to your own key when you have one.
- Not there yet: key generation (import an existing key), Autocrypt, WKD
  lookups, S/MIME, threads.

## Server notes

JMAP: session from `/.well-known/jmap` (the server's own URLs are re-based on
the address you typed, so a server that calls itself `localhost` still works),
then `Mailbox/get`, `Email/query` + `Email/get` for a folder page,
`Email/changes` since the state held for everything after that (a state too
old to replay starts over), `Email/set` for flags and moves, blob download /
upload, `Email/import` + `EmailSubmission/set` for sending (the finished
RFC 5322 message is built on the phone, uploaded, imported into Drafts and
submitted; on success the server moves it to Sent). Identity from
`Identity/get`. Filters: `SieveScript/get`, `/set`, `/validate` (RFC 9661).

Sending from an alias on Stalwart: it requires the envelope sender
(`mailFrom`) to be the identity's own address and refuses identities for
catch-all aliases, but accepts any `From` header. So mail from an alias goes
out through the account's identity with the alias in `From`; when the server
has (or lets the app create) an identity for the exact address, that one is
used. (`Delivered-To` is no use for aliases: on Stalwart it carries the
account's primary address.)

The app only talks HTTPS, except to `localhost` and to `10.0.2.2` (the
emulator's address for the development machine), for testing against a local
server.

## Layout

    Jmap.kt          the JMAP client (session, method calls, typed helpers, blobs)
    Pgp.kt           Bouncy Castle: keys, decrypt + verify, encrypt + sign, detached signatures
    Mime.kt          mime4j parsing to a Part tree; writer for what we send (QP, base64, RFC 2047, PGP/MIME)
    Html.kt          HTML mail → text
    Keys.kt          the key ring: import, lookup by address, passphrase handling
    Reader.kt        opens a message: fetch, decrypt, verify, text, attachments (in-memory cache)
    Sync.kt          folders + list in step with the server, flag/move actions, sending, drafts; Notify; SyncWorker
    Db.kt            SQLite: mailboxes, emails (headers), state, keys
    Store.kt         prefs (account, identity, look, pgp options, sync state), Crypto.kt for secrets
    Model.kt         Mailbox, Address, Email, BodyPart, EmailFull, Key, Opened
    Sieve.kt         the rule → Sieve text generator (require merging)
    SieveView.kt     scripts list, script editor, rule builder
    Senders.kt       which addresses are ours (identities + Delivered-To + settings), the reply-from rule
    HtmlView.kt      the WebView for HTML mail (no JS, no network unless asked, cid: images)
    MainActivity.kt  topbar, statusbar, sign-in page, the list, the folder picker
    ReaderView.kt    one message, attachments, actions, the "key needed" window
    ComposeView.kt   the editor (ComposeDraft: new / reply / forward / draft / mailto)
    SettingsView.kt  settings and the keys window
    Look.kt, Bits.kt the x11 look and a few extra pieces
    app/src/test     JVM tests: PGP against gpg-made keys and messages, MIME, HTML, JMAP JSON, Sieve
    app/src/test/resources  test keys (alice: ed25519/cv25519 with passphrase "alicekey"; bob: rsa3072, none) and messages
    releases/        release APKs, one per version

The test keys and messages are throw-away fixtures made for the tests
(`@example.com` addresses); they protect nothing.

## Build

JDK 17+ and the Android SDK (`sdk.dir` in `local.properties` or `ANDROID_HOME`):

```
./gradlew testDebugUnitTest assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. To sign it, put a
`keystore.properties` next to `settings.gradle.kts` (it is git-ignored):

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it the release APK comes out unsigned (sign it with `apksigner`), and
`assembleDebug` uses the SDK's debug key. An APK signed with a different key
cannot update the one from `releases/`: uninstall that one first.

## License

Copyright (C) 2026 th3rumbl3m4t0r

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. It is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; see [LICENSE](LICENSE) for the full text.

The libraries bundled in the APK are under permissive licenses that are
compatible with GPLv3 (Apache-2.0 and the MIT-style Bouncy Castle Licence);
Apache-2.0 code is why this is GPL version 3 and not 2. Their notices are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
