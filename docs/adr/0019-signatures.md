# 0019. Signatures

Status: accepted

## Decision

- jpm checks OpenPGP signatures (`.asc`) only when the workspace root lists `"trustedKeys"`: full key
  fingerprints, each with the packages it may sign. A signature by a key nobody chose to trust proves
  nothing, so there is no check without the list.
- With the list, every signed jar must be signed by a key trusted for its package, and the signature must
  match; unsigned jars pass, since many repositories publish no signatures. The check runs after the jars are
  fetched and before `project.lock` is written (`dev.jpm.signing.Signatures`, from `Install.run`), so a lock
  never pins a jar that failed it. A jar that passed is remembered next to its signature (`.asc.ok`, with the
  jar's SHA-256 and the key), so later installs do not verify it again.
- Keys are fetched by fingerprint from keys.openpgp.org (`JPM_KEYSERVER`), which publishes only keys their
  owners uploaded, and kept in `~/.jpm/keys`. A fetched key is used only when its fingerprint is the trusted
  one, and a subkey only with a valid binding signature from it.
- OpenPGP is implemented in jpm with the JDK's cryptography (`dev.jpm.signing.impl.OpenPgp`), as jpm has no
  dependencies: version 4 keys and signatures, RSA, EdDSA (Ed25519) and ECDSA on the NIST curves, with SHA-1
  and SHA-2 hashes. DSA and version 6 keys are not supported; a signature that cannot be checked fails like
  one that does not match. Revocations and expiry are not read: trust is the list in the manifest.
