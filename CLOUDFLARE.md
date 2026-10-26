# Cloudflare Tunnel Setup

This project includes a Cloudflare Tunnel configuration so you can expose the Remote Control hub (FastAPI server) to the internet on your own domain. Users can then open the domain in their phone browser, see the download page, and install the APK (phone) or desktop app (computer).

## Prerequisites

- A domain name managed by Cloudflare (or added to Cloudflare)
- A server/VPS where the hub runs (`python -m uvicorn main:app --host 0.0.0.0 --port 8000`)
- `cloudflared` installed on the server

## 1. Install cloudflared

```bash
# Ubuntu/Debian
sudo mkdir -p --mode=0755 /usr/share/keyrings
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo 'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared jammy main' | sudo tee /etc/apt/sources.list.d/cloudflared.list
sudo apt-get update && sudo apt-get install cloudflared

# Or download the binary directly:
# https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/install-and-setup/installation/
```

Verify: `cloudflared version`

## 2. Authenticate with Cloudflare

```bash
cloudflared tunnel login
```

This opens a browser window. Log in to Cloudflare and authorize the certificate.

## 3. Create a tunnel

```bash
cloudflared tunnel create remote-control
```

Note the tunnel UUID printed in the output — you'll need it in the config.

## 4. Route DNS to your domain

```bash
cloudflared tunnel route dns remote-control <your-domain>
```

Example: `cloudflared tunnel route dns remote-control rc.example.com`

## 5. Configure the tunnel

Copy the example config and fill in your tunnel UUID and domain:

```bash
cd server
cp .cloudflare/config.yml.example .cloudflare/config.yml
```

Edit `.cloudflare/config.yml`:

```yaml
tunnel: <YOUR_TUNNEL_UUID>
credentials-file: /root/.cloudflared/<YOUR_TUNNEL_UUID>.json

ingress:
  - hostname: <your-domain>
    service: http://localhost:8000
  - hostname: <your-domain>
    path: /devices
    service: http://localhost:8000
  - hostname: <your-domain>
    path: /ws
    service: http://localhost:8000
    originRequest:
      httpHostHeader: <your-domain>
  - service: http_status:404
```

⚠️ `config.yml` and `*.json` credentials are **gitignored** — never commit them.

## 6. Run the tunnel

```bash
cloudflared tunnel --config .cloudflare/config.yml run remote-control
```

Or install it as a systemd service (see `.cloudflare/cloudflared.service.example`).

## 7. Open the site

Visit `https://<your-domain>/` — you'll see the download page:

- **On a phone:** Detects Android → shows "Download APK" prominently
- **On a computer:** Detects Windows/macOS/Linux → shows the desktop app download

The APK and desktop binaries are published to [GitHub Releases](https://github.com/lovaerick93-source/test/releases/latest) by the CI/CD workflow.

## Files in this repo

- `server/.cloudflare/config.yml.example` — tunnel config template (copy to `config.yml`)
- `server/.cloudflare/cloudflared.service.example` — systemd service template
