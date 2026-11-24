#!/usr/bin/env python3
"""VREMOVER server.

Server kecil (Flask) yang nerima lagu dari app Android / browser, misahin vokal
dan instrumen pakai model UVR lewat paket `audio-separator`, lalu ngasih hasilnya.

Jalanin:  python server/app.py   (default port 5000)
"""
import os
import queue
import re
import shutil
import socket
import threading
import time
import traceback
import uuid

from flask import Flask, abort, jsonify, request, send_from_directory
from werkzeug.utils import secure_filename

BASE = os.path.dirname(os.path.abspath(__file__))
WORK = os.environ.get("VREMOVER_WORKDIR", os.path.join(BASE, "work"))
MODEL_DIR = os.environ.get("VREMOVER_MODELS", os.path.join(BASE, "models"))
MAX_MB = int(os.environ.get("VREMOVER_MAX_MB", "200"))
KEEP_HOURS = float(os.environ.get("VREMOVER_KEEP_HOURS", "6"))

os.makedirs(WORK, exist_ok=True)
os.makedirs(MODEL_DIR, exist_ok=True)

app = Flask(__name__, static_folder=os.path.join(BASE, "static"), static_url_path="/static")
app.config["MAX_CONTENT_LENGTH"] = MAX_MB * 1024 * 1024

# Nama model mengikuti daftar model di audio-separator (cek: audio-separator -l)
MODELS = [
    {"id": "UVR-MDX-NET-Inst_HQ_3.onnx", "name": "MDX-Net Inst HQ3 · Vokal + Instrumental (disarankan)"},
    {"id": "UVR_MDXNET_KARA_2.onnx", "name": "MDX-Net Karaoke 2 · Hapus vokal utama"},
    {"id": "Kim_Vocal_2.onnx", "name": "Kim Vocal 2 · Acapella bersih"},
    {"id": "htdemucs_ft.yaml", "name": "Demucs v4 FT · 4 stem (vokal, drum, bass, lainnya)"},
]
MODEL_IDS = {m["id"] for m in MODELS}
FORMATS = {"MP3", "WAV", "FLAC"}
AUDIO_EXT = (".mp3", ".wav", ".flac")

JOBS = {}
LOCK = threading.Lock()
QUEUE = queue.Queue()


def stem_label(filename):
    found = re.findall(r"\(([^)]+)\)", filename)
    return found[-1] if found else os.path.splitext(filename)[0]


def worker():
    # Satu job per waktu biar GPU/RAM nggak jebol.
    while True:
        jid = QUEUE.get()
        job = JOBS.get(jid)
        if not job:
            continue
        job["status"] = "running"
        job["started"] = time.time()
        try:
            from audio_separator.separator import Separator

            out = os.path.join(WORK, jid, "out")
            os.makedirs(out, exist_ok=True)
            sep = Separator(output_dir=out, model_file_dir=MODEL_DIR, output_format=job["format"])
            sep.load_model(model_filename=job["model"])
            sep.separate(job["input"])

            files = []
            for fn in sorted(os.listdir(out)):
                if fn.lower().endswith(AUDIO_EXT):
                    files.append({
                        "name": fn,
                        "label": stem_label(fn),
                        "url": f"/files/{jid}/{fn}",
                    })
            if not files:
                raise RuntimeError("Proses selesai tapi file hasil nggak ditemukan.")
            job["files"] = files
            job["status"] = "done"
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            job["status"] = "error"
            job["error"] = str(e) or e.__class__.__name__
        finally:
            job["finished"] = time.time()


def cleaner():
    while True:
        time.sleep(600)
        limit = time.time() - KEEP_HOURS * 3600
        with LOCK:
            old = [j for j, v in JOBS.items() if v.get("finished", v["created"]) < limit and v["status"] in ("done", "error")]
            for j in old:
                JOBS.pop(j, None)
                shutil.rmtree(os.path.join(WORK, j), ignore_errors=True)


threading.Thread(target=worker, daemon=True).start()
threading.Thread(target=cleaner, daemon=True).start()


@app.get("/")
def index():
    return send_from_directory(app.static_folder, "index.html")


@app.get("/api/health")
def health():
    return jsonify(ok=True, app="VREMOVER", queue=QUEUE.qsize())


@app.get("/api/models")
def models():
    return jsonify(MODELS)


@app.post("/api/separate")
def separate():
    f = request.files.get("file")
    if not f or not f.filename:
        return jsonify(error="File lagu belum dipilih."), 400
    model = request.form.get("model", MODELS[0]["id"])
    fmt = request.form.get("format", "MP3").upper()
    if model not in MODEL_IDS:
        return jsonify(error="Model nggak dikenal."), 400
    if fmt not in FORMATS:
        fmt = "MP3"

    jid = uuid.uuid4().hex[:12]
    in_dir = os.path.join(WORK, jid, "in")
    os.makedirs(in_dir, exist_ok=True)
    name = secure_filename(f.filename) or "audio"
    if "." not in name:
        name += ".mp3"
    path = os.path.join(in_dir, name)
    f.save(path)

    JOBS[jid] = {
        "status": "queued", "created": time.time(), "input": path,
        "model": model, "format": fmt, "files": [], "error": None,
    }
    QUEUE.put(jid)
    return jsonify(id=jid)


@app.get("/api/jobs/<jid>")
def job(jid):
    j = JOBS.get(jid)
    if not j:
        return jsonify(error="Job nggak ketemu (mungkin sudah dihapus)."), 404
    pos = 0
    if j["status"] == "queued":
        pos = sum(1 for v in JOBS.values() if v["status"] == "queued" and v["created"] <= j["created"])
    return jsonify(status=j["status"], files=j["files"], error=j["error"], position=pos)


@app.get("/files/<jid>/<path:name>")
def files(jid, name):
    if jid not in JOBS:
        abort(404)
    return send_from_directory(
        os.path.join(WORK, jid, "out"), name,
        as_attachment=request.args.get("dl") == "1",
    )


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5000"))
    print(f"\nVREMOVER server jalan.\n  Di PC ini  : http://127.0.0.1:{port}\n  Dari HP    : http://{local_ip()}:{port}  (satu WiFi)\n")
    app.run(host="0.0.0.0", port=port, threaded=True)
