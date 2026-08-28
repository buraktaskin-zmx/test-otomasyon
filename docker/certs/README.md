# Corporate root CA certificates

Drop the Yapı Kredi root and intermediate CA certificates here as `*.crt`
(PEM, one certificate per file) **before building the images**.

Both Dockerfiles pick up every `*.crt` in this directory:

- **executor** — imports into the OS trust store *and* the JVM cacerts.
  Chromium reads the OS store, the JVM reads cacerts; importing only one gives
  you TLS that works in the browser but not in Java, or the reverse.
- **orchestrator** — JVM cacerts only. It never runs a browser.

If this directory holds no `.crt` files the build simply skips the import, so
the Dockerfiles work unchanged outside the corporate network.

To convert a DER/`.cer` certificate to PEM:

```
openssl x509 -inform der -in corp-root.cer -out corp-root.crt
```

Certificates are public keys, not secrets — but they are site-specific, so
`.gitignore` keeps them out of the repository. Distribute them the same way the
team distributes the VDI trust bundle.
