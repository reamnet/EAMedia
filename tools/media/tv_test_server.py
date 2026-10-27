#!/usr/bin/env python3
"""Serve the original video/audio/input fixture only on localhost for native TV checks."""
import argparse
import functools
import http.server
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--port',type=int,default=8765)
args=parser.parse_args()
handler=functools.partial(http.server.SimpleHTTPRequestHandler,directory=str(Path(__file__).resolve().parent/'tv_fixture'))
http.server.ThreadingHTTPServer(('127.0.0.1',args.port),handler).serve_forever()
