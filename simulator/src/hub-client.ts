const FRAME_BYTES = 160; // 20 ms @ 8 kHz μ-law

export type HubClientCallbacks = {
  onLog: (message: string, level?: "info" | "ok" | "warn" | "err") => void;
  onStatus: (status: string) => void;
  onSessionReady: () => void;
  onAudioDelta: (mulaw: Uint8Array) => void;
  onBytesSent: (n: number) => void;
  onBytesReceived: (n: number) => void;
};

export class HubClient {
  private ws: WebSocket | null = null;
  private closed = false;
  private uplinkTimer: ReturnType<typeof setInterval> | null = null;
  private pendingMulaw: number[] = [];

  constructor(
    private hubUrl: string,
    private callbacks: HubClientCallbacks,
    private loopback = false,
  ) {}

  async start(): Promise<void> {
    this.closed = false;
    const base = this.hubUrl.replace(/\/$/, "");

    const tokenEndpoint =
      typeof window !== "undefined" && import.meta.env.DEV
        ? `/proxy/token?hub=${encodeURIComponent(base)}`
        : `${base}/token`;

    this.callbacks.onLog(`Fetching token from ${base}/token`);
    const tokenRes = await fetch(tokenEndpoint, { method: "POST" });
    if (!tokenRes.ok) {
      throw new Error(`token HTTP ${tokenRes.status}: ${(await tokenRes.text()).slice(0, 200)}`);
    }
    const tokenJson = (await tokenRes.json()) as { value?: string; model?: string };
    const token = tokenJson.value;
    if (!token) throw new Error("no token in response");

    this.callbacks.onLog(`Token minted (model=${tokenJson.model ?? "?"})`, "ok");

    const wsUrl =
      base.replace(/^https:/, "wss:").replace(/^http:/, "ws:") +
      (this.loopback ? "/loopback" : "");
    this.callbacks.onLog(`Opening WebSocket ${wsUrl}`);
    this.callbacks.onStatus("connecting");

    await new Promise<void>((resolve, reject) => {
      let settled = false;
      const fail = (err: Error) => {
        if (settled) return;
        settled = true;
        reject(err);
      };
      const ok = () => {
        if (settled) return;
        settled = true;
        resolve();
      };

      const ws = new WebSocket(wsUrl, []);
      this.ws = ws;

      ws.onopen = () => {
        this.callbacks.onLog("WebSocket open (hub ignores Bearer in browser)", "ok");
        this.callbacks.onStatus("connected");
      };

      ws.onmessage = (ev) => {
        const text = typeof ev.data === "string" ? ev.data : "";
        this.handleMessage(text);
        if (text.includes('"session.updated"')) {
          ok();
        }
      };

      ws.onerror = () => {
        this.callbacks.onLog("WebSocket error", "err");
        fail(new Error("WebSocket error"));
      };

      ws.onclose = (ev) => {
        this.callbacks.onLog(`WebSocket closed code=${ev.code} reason=${ev.reason || "(none)"}`, "warn");
        if (!this.closed) {
          this.callbacks.onStatus("disconnected");
        }
        fail(new Error(`WebSocket closed before session.updated (code=${ev.code})`));
      };
    });
  }

  startUplink(sendChunk: () => Uint8Array | null): void {
    if (this.uplinkTimer) return;
    this.uplinkTimer = setInterval(() => {
      const chunk = sendChunk();
      if (!chunk || chunk.length === 0 || !this.ws || this.ws.readyState !== WebSocket.OPEN) {
        return;
      }
      let b64 = "";
      for (let i = 0; i < chunk.length; i += 0x8000) {
        b64 += String.fromCharCode(...chunk.subarray(i, i + 0x8000));
      }
      b64 = btoa(b64);
      const msg = JSON.stringify({ type: "input_audio_buffer.append", audio: b64 });
      this.ws.send(msg);
      this.callbacks.onBytesSent(chunk.length);
    }, 20);
  }

  /** Queue μ-law samples; uplink timer drains 160-byte frames. */
  enqueueMulaw(data: Uint8Array): void {
    for (const b of data) this.pendingMulaw.push(b);
  }

  drainFrame(): Uint8Array | null {
    if (this.pendingMulaw.length < FRAME_BYTES) return null;
    const frame = this.pendingMulaw.splice(0, FRAME_BYTES);
    return Uint8Array.from(frame);
  }

  private handleMessage(text: string): void {
    let type = "";
    try {
      const json = JSON.parse(text) as { type?: string; delta?: string };
      type = json.type ?? "";
      if (type === "response.output_audio.delta" && json.delta) {
        const bin = atob(json.delta);
        const mulaw = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) mulaw[i] = bin.charCodeAt(i);
        this.callbacks.onBytesReceived(mulaw.length);
        this.callbacks.onAudioDelta(mulaw);
        return;
      }
      if (type === "session.updated") {
        this.callbacks.onLog(`session.updated: ${text.slice(0, 240)}`, "ok");
        this.callbacks.onSessionReady();
        this.callbacks.onStatus("session ready");
        return;
      }
      if (type === "input_audio_buffer.speech_started") {
        this.callbacks.onLog("speech_started (server VAD)", "info");
        return;
      }
      if (type === "error") {
        this.callbacks.onLog(`hub error: ${text.slice(0, 300)}`, "err");
        return;
      }
    } catch {
      // non-json
    }
    this.callbacks.onLog(`WS: ${type || text.slice(0, 120)}`);
  }

  stop(): void {
    this.closed = true;
    if (this.uplinkTimer) {
      clearInterval(this.uplinkTimer);
      this.uplinkTimer = null;
    }
    this.pendingMulaw = [];
    if (this.ws) {
      try {
        this.ws.close(1000, "call ended");
      } catch {
        /* ignore */
      }
      this.ws = null;
    }
    this.callbacks.onStatus("idle");
    this.callbacks.onLog("Call ended", "info");
  }
}
