#!/usr/bin/env python3
"""Loopback dashboard and SSH snapshot fetcher. Cannot mutate cloud Jeeves."""
from __future__ import annotations
import argparse, json, subprocess, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from checkpoint_backup import durable_json
from quality import read

HERE=Path(__file__).resolve().parent/'dashboard'

def fetch(config):
    while True:
        try:
            command=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=15','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2',
                     '-o','HostKeyAlias='+config['host_key_alias'],'-o','UserKnownHostsFile='+config['known_hosts'],
                     config['host'],'cat /var/lib/jeeves/campaign/dashboard.json']
            response=subprocess.run(command,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
            value=json.loads(response.stdout);value['relayed_at']=time.time()
            durable_json(Path(config['cache']),value)
        except Exception as error:print('Last snapshot retained:',type(error).__name__,flush=True)
        time.sleep(5)

def handler(config):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.headers.get('Host','').split(':')[0] not in ('localhost','127.0.0.1'):
                self.send_error(403);return
            path=urlsplit(self.path).path
            if path=='/api/status':
                value=read(config['cache'],{'at':0,'errors':['Waiting for first cloud snapshot']})
                body=json.dumps(value,ensure_ascii=False).encode();mime='application/json'
            elif path in ('/','/index.html','/app.js','/style.css'):
                name='index.html' if path=='/' else path[1:]
                body=(HERE/name).read_bytes()
                mime={'index.html':'text/html; charset=utf-8','app.js':'text/javascript; charset=utf-8','style.css':'text/css; charset=utf-8'}[name]
            elif path=='/favicon.ico':self.send_response(204);self.end_headers();return
            else:self.send_error(404);return
            self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(body)))
            self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff')
            self.send_header('Content-Security-Policy',"default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'")
            self.end_headers()
            try:self.wfile.write(body)
            except (BrokenPipeError,ConnectionResetError):pass
        def log_message(self,*args):pass
    return Handler

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True);parser.add_argument('--port',type=int,default=7930);args=parser.parse_args()
    config=read(args.config)
    threading.Thread(target=fetch,args=(config,),daemon=True).start()
    ThreadingHTTPServer(('127.0.0.1',args.port),handler(config)).serve_forever()

if __name__=='__main__':main()
