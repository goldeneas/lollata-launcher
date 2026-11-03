#!/usr/bin/env python3
"""Web server statico per i file del modpack (solo HTTP, nessuna dipendenza).

L'HTTPS lo gestisce un reverse proxy davanti (es. Nginx Proxy Manager):
punta il proxy host a http://<ip-di-questa-macchina>:<porta>.

Uso:
    ./serve.py [--dir /srv/mcfiles] [--port 8080] [--bind 0.0.0.0]

Le stesse opzioni si possono dare con variabili d'ambiente:
MCFILES_DIR, MCFILES_PORT, MCFILES_BIND.

Struttura servita (come la genera pack-builder):
    <dir>/packs/index.json
    <dir>/packs/<id>/pack.json
    <dir>/packs/<id>/objects/ab/ab...
    <dir>/launcher/...            (opzionale, es. launcher.jar o i pacchetti)
"""
import argparse
import os
import sys
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class Handler(SimpleHTTPRequestHandler):
    server_version = "LollataFiles/1.0"
    sys_version = ""
    # keep-alive: il launcher scarica migliaia di oggetti piccoli
    protocol_version = "HTTP/1.1"

    extensions_map = {
        **SimpleHTTPRequestHandler.extensions_map,
        ".json": "application/json",
        ".jar": "application/java-archive",
        ".zip": "application/zip",
        ".gz": "application/gzip",
    }

    def list_directory(self, path):
        # niente elenco dei file: le cartelle non si possono sfogliare
        self.send_error(HTTPStatus.NOT_FOUND, "File not found")
        return None

    def end_headers(self):
        path = self.path.split("?", 1)[0]
        if path.endswith(".json"):
            # index.json e pack.json cambiano a ogni pubblicazione
            self.send_header("Cache-Control", "no-cache")
        elif "/objects/" in path:
            # gli oggetti sono immutabili: il nome è l'hash del contenuto
            self.send_header("Cache-Control", "public, max-age=31536000, immutable")
        super().end_headers()

    def log_message(self, fmt, *args):
        # dietro al proxy l'IP vero del client è in X-Forwarded-For / X-Real-IP
        headers = getattr(self, "headers", None)
        client = ((headers.get("X-Forwarded-For") or headers.get("X-Real-IP")) if headers else None) \
            or self.client_address[0]
        sys.stderr.write("%s - [%s] %s\n" % (client, self.log_date_time_string(), fmt % args))


def main():
    p = argparse.ArgumentParser(description="Serve i file del modpack su HTTP.")
    p.add_argument("--dir", default=os.environ.get("MCFILES_DIR", "/srv/mcfiles"),
                   help="cartella da servire (default: /srv/mcfiles)")
    p.add_argument("--port", type=int, default=int(os.environ.get("MCFILES_PORT", "8080")),
                   help="porta HTTP (default: 8080)")
    p.add_argument("--bind", default=os.environ.get("MCFILES_BIND", "0.0.0.0"),
                   help="indirizzo su cui ascoltare (default: 0.0.0.0)")
    a = p.parse_args()

    root = os.path.abspath(a.dir)
    if not os.path.isdir(root):
        sys.exit(f"Cartella non trovata: {root}")
    if not os.path.isfile(os.path.join(root, "packs", "index.json")):
        print(f"Attenzione: {root}/packs/index.json non esiste ancora "
              "(pubblica un pack con publish.sh).", file=sys.stderr)

    ThreadingHTTPServer.daemon_threads = True
    httpd = ThreadingHTTPServer((a.bind, a.port), partial(Handler, directory=root))
    print(f"Servo {root} su http://{a.bind}:{a.port}/  (Ctrl+C per fermare)", file=sys.stderr)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
