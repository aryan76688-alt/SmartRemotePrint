# Smart Remote Print Protocol Specification

## Network & Transport
- **Network Layer**: Private WireGuard mesh VPN provided by Tailscale (`tailfc14cd.ts.net`).
- **Transport**: HTTP / HTTPS REST API over port `8766`.
- **Addressing**:
  - MagicDNS: `http://desktop-00ctdtl.tailfc14cd.ts.net:8766/api/v1`
  - Tailnet IPv4: `http://100.111.100.122:8766/api/v1`

---

## Authentication Handshake
```
Android Client (Lenovo Tab P12)          Windows Agent (Port 8766)
       |                                          |
       |  POST /api/v1/pair                       |
       |  { deviceId, deviceName, pairingPin }    |
       |----------------------------------------->|
       |                                          | Verifies 6-digit PIN
       |  200 OK                                  | Generates 256-bit token
       |  { success: true, authToken: "srp_..." } | Saves in auth store
       |<-----------------------------------------|
       |                                          |
       | Encrypts token via AndroidKeyStore       |
```

---

## Print Job Submission Workflow
```
Android Client                            Windows Agent
       |                                          |
       |  POST /api/v1/print                      |
       |  Authorization: Bearer srp_...           |
       |  Content-Type: multipart/form-data       |
       |  Part 1: metadata (JSON)                 |
       |  Part 2: file (PDF bytes)                |
       |----------------------------------------->|
       |                                          | 1. Validates Bearer token
       |                                          | 2. Checks rate limit
       |                                          | 3. Validates %PDF- header
       |                                          | 4. Checks SHA-256 deduplication
       |                                          | 5. Enqueues to winspool.drv
       |  200 OK                                  |
       |  { jobId: "...", state: "Spooling" }     |
       |<-----------------------------------------|
```
