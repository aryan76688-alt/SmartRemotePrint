# Security & Privacy Architecture

## Zero-Trust Tailnet Isolation
- The print agent binds strictly within the Tailscale mesh interface.
- No public router port forwarding is ever required.
- No Tailscale Funnel is used.
- All communications are encrypted end-to-end via WireGuard (ChaCha20-Poly1305).

## Credential & Token Protection
- **Android KeyStore**: Auth tokens are encrypted with hardware-backed AES-256-GCM keys.
- **Zero Plaintext**: Passwords and tokens are never written to unencrypted storage, logs, or crash dumps.
- **No Credential Harvesting**: Marg Cloud passwords are never intercepted or relayed to the print server.

## Payload Validation
- **Magic Bytes Check**: Only authentic PDF payloads beginning with `%PDF-` are accepted by the spooler.
- **Size Bounds**: Enforces strict 50MB payload limits.
- **Duplicate Suppression**: Rejects duplicate document transmissions within a 2-minute rolling window.
- **Rate Limiting**: Throttles clients to a maximum of 30 requests per minute.
