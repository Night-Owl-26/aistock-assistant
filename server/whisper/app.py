"""Tiny HTTP service: POST /transcribe {audio: base64 16 kHz mono 16-bit PCM, language: "ta-IN"|"en-IN", prompt} -> {text}.
GET /health -> {ok, model}. Only reachable inside the Docker network (the Follower API calls it)."""
import base64, json, os, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

import numpy as np
from faster_whisper import WhisperModel

CPUS = os.cpu_count() or 1
NAME = os.environ.get("WHISPER_MODEL", "auto").strip() or "auto"
if NAME == "auto":
    # medium hears Tamil much better than small, but needs about 4 cores to answer in a few seconds.
    NAME = "medium" if CPUS >= 4 else "small"
MAX_SECONDS = 60

print(f"loading Whisper '{NAME}' on {CPUS} CPU cores (first start downloads the model)...", flush=True)
t0 = time.time()
MODEL = WhisperModel(NAME, device="cpu", compute_type="int8", cpu_threads=CPUS, download_root="/models")
print(f"ready in {time.time() - t0:.0f}s", flush=True)
LOCK = threading.Lock()  # one transcription at a time: they would only slow each other down


def transcribe(pcm_b64: str, language: str, prompt: str, vad: bool = True) -> dict:
    pcm = np.frombuffer(base64.b64decode(pcm_b64), dtype=np.int16)
    if pcm.size == 0:
        return {"text": "", "seconds": 0}
    if pcm.size > 16000 * MAX_SECONDS:
        raise ValueError("audio too long")
    audio = pcm.astype(np.float32) / 32768.0
    lang = {"ta-IN": "ta", "en-IN": "en"}.get(language, None)
    t = time.time()
    with LOCK:
        segments, info = MODEL.transcribe(
            audio, language=lang, beam_size=1, vad_filter=vad, condition_on_previous_text=False,
            initial_prompt=(prompt or None), without_timestamps=True,
            # A shop command is one or two sentences: stop runaway repetition, which Whisper does on unclear audio.
            max_new_tokens=120, no_repeat_ngram_size=4, repetition_penalty=1.1,
        )
        text = " ".join(s.text.strip() for s in segments).strip()
    return {"text": text, "language": info.language, "took": round(time.time() - t, 2), "model": NAME}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("content-type", "application/json; charset=utf-8")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            return self._send(200, {"ok": True, "model": NAME, "cpus": CPUS})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/transcribe":
            return self._send(404, {"error": "not found"})
        try:
            length = int(self.headers.get("content-length", "0"))
            if length <= 0 or length > 4 * 1024 * 1024:
                return self._send(413, {"error": "bad size"})
            req = json.loads(self.rfile.read(length))
            self._send(200, transcribe(req.get("audio", ""), req.get("language", ""), str(req.get("prompt", ""))[:800], bool(req.get("vad", True))))
        except ValueError as e:
            self._send(400, {"error": str(e)})
        except Exception as e:  # never crash the service on one bad request
            self._send(500, {"error": f"{type(e).__name__}: {e}"})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
