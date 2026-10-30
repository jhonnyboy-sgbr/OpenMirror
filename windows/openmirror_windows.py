"""Painel Windows para iniciar/parar o servidor OpenMirror."""
import os
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import messagebox

from om_meta import VERSION
from om_presets import PRESETS, server_args
from om_security import PIN_LENGTH, generate_pin
from server import lan_ip
from video_h264 import check_encoder, locate_ffmpeg, probe_ffmpeg

BASE = Path(__file__).resolve().parent
BG = '#0c1018'
CARD = '#161d2b'
TEXT = '#eef3fc'
MUTED = '#a3b0c7'
ACCENT = '#4388f5'


class Window:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title(f'OpenMirror {VERSION} · Windows')
        self.root.geometry('600x720')
        self.root.minsize(560, 680)
        self.root.configure(bg=BG)
        self.process = None
        self.log_handle = None
        self.pin = tk.StringVar(value='-' * PIN_LENGTH)
        self.address = tk.StringVar(value=f'http://{lan_ip()}:8765')
        self.fps = tk.IntVar(value=30)
        self.width = tk.IntVar(value=640)
        self.bitrate = tk.IntVar(value=3000)
        self.encoder = tk.StringVar(value='software')
        self.preset = tk.StringVar(value='Equilibrado')
        self.capture = tk.StringVar(value='auto')
        self.eager = tk.BooleanVar(value=False)
        self.ffmpeg_info = tk.StringVar(value='Verificando FFmpeg…')
        self.state = tk.StringVar(value='Servidor desligado')
        self.build()
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        self.root.after(800, self.check)
        threading.Thread(target=self.probe, daemon=True).start()

    def label(self, text: str, size=11, bold=False, color=TEXT, parent=None):
        return tk.Label(parent or self.root, text=text, bg=(CARD if parent else BG),
                        fg=color, font=('Segoe UI', size, 'bold' if bold else 'normal'))

    def build(self):
        self.label('◉  OpenMirror', 25, True).pack(anchor='w', padx=30, pady=(25, 2))
        self.label('Seu computador, na palma da mão.', 11, color=MUTED).pack(anchor='w', padx=31)
        box = tk.Frame(self.root, bg=CARD, padx=23, pady=19)
        box.pack(fill='x', padx=25, pady=(21, 10))
        self.label('ENDEREÇO DO COMPUTADOR', 9, True, MUTED, box).pack(anchor='w')
        self.label(self.address.get(), 17, True, TEXT, box).pack(anchor='w', pady=(5, 13))
        self.label('CÓDIGO DE PAREAMENTO', 9, True, MUTED, box).pack(anchor='w')
        tk.Label(box, textvariable=self.pin, font=('Consolas', 29, 'bold'),
                 fg='#82adff', bg=CARD).pack(anchor='w', pady=(2, 0))
        self.label('Digite este código no celular para autorizar a conexão.', 9,
                   color=MUTED, parent=box).pack(anchor='w')
        options = tk.Frame(self.root, bg=BG)
        options.pack(fill='x', padx=30, pady=7)
        tk.Label(options, text='FPS', bg=BG, fg=MUTED, font=('Segoe UI', 10)).grid(row=0, column=0)
        tk.OptionMenu(options, self.fps, 12, 20, 24, 30, 45, 60).grid(row=0, column=1, padx=(5, 30))
        tk.Label(options, text='Largura do vídeo', bg=BG, fg=MUTED,
                 font=('Segoe UI', 10)).grid(row=0, column=2)
        tk.OptionMenu(options, self.width, 640, 854, 960, 1280, 1600, 1920).grid(row=0, column=3, padx=5)
        presets = tk.Frame(self.root, bg=BG)
        presets.pack(fill='x', padx=30, pady=(2, 5))
        tk.Label(presets, text='Perfil', bg=BG, fg=MUTED).pack(side='left')
        menu = tk.OptionMenu(presets, self.preset, *PRESETS, command=self.change_preset)
        menu.pack(side='left', padx=12)
        extra = tk.Frame(self.root, bg=BG)
        extra.pack(fill='x', padx=30, pady=(4, 5))
        tk.Label(extra, text='H.264', bg=BG, fg=MUTED).grid(row=0, column=0)
        tk.OptionMenu(extra, self.encoder, 'software', 'amd').grid(row=0, column=1, padx=(8, 15))
        tk.Label(extra, text='Bitrate (kbps)', bg=BG, fg=MUTED).grid(row=0, column=2)
        tk.OptionMenu(extra, self.bitrate, 3000, 5000, 6000, 8000, 12000, 16000).grid(row=0, column=3)
        cap = tk.Frame(self.root, bg=BG)
        cap.pack(fill='x', padx=30, pady=(4, 2))
        tk.Label(cap, text='Captura', bg=BG, fg=MUTED).grid(row=0, column=0)
        tk.OptionMenu(cap, self.capture, 'gdigrab', 'ddagrab', 'auto').grid(row=0, column=1, padx=(8, 15))
        tk.Checkbutton(cap, text='Menos atraso (experimental)', variable=self.eager, bg=BG, fg=MUTED,
                       selectcolor=CARD, activebackground=BG, activeforeground=TEXT).grid(row=0, column=2)
        self.label('gdigrab = GDI (estável) · ddagrab = DXGI (experimental, FFmpeg 6+) · auto tenta DXGI e cai para GDI.',
                   9, color=MUTED).pack(pady=(2, 0))
        self.label('H.264 requer FFmpeg; AMD AMF é experimental. MJPEG funciona sem FFmpeg.',
                   9, color=MUTED).pack(pady=(2, 0))
        tk.Label(self.root, textvariable=self.ffmpeg_info, fg=MUTED, bg=BG, justify='left',
                 wraplength=540, font=('Segoe UI', 9)).pack(pady=(4, 0))
        self.button = tk.Button(self.root, text='▶  INICIAR TRANSMISSÃO',
                                command=self.toggle, bg=ACCENT, fg='white',
                                activebackground='#2766cc', activeforeground='white',
                                borderwidth=0, font=('Segoe UI', 12, 'bold'), pady=13,
                                cursor='hand2')
        self.button.pack(fill='x', padx=25, pady=(15, 9))
        tk.Label(self.root, textvariable=self.state, fg=MUTED, bg=BG,
                 font=('Segoe UI', 10)).pack()
        tk.Button(self.root, text='Abrir arquivo de log', command=self.open_log, bg=CARD, fg=TEXT,
                  borderwidth=0, font=('Segoe UI', 9), cursor='hand2').pack(pady=(6, 0))
        self.label('PC por Ethernet + celular por Wi-Fi funciona. Uso exclusivo na LAN privada.', 9,
                   color=MUTED).pack(pady=8)

    def probe(self):
        """Roda em segundo plano: o que este FFmpeg lista e se o AMD AMF funciona de verdade."""
        exe = locate_ffmpeg(BASE)
        if not exe:
            text = 'FFmpeg NÃO encontrado: só o modo MJPEG funciona. Coloque ffmpeg.exe em windows\\tools\\.'
        else:
            info = probe_ffmpeg(exe)
            parts = [f"FFmpeg {info['version'] or '?'}", 'x264 ' + ('✓' if info['libx264'] else '✗'),
                     'DXGI (ddagrab) ' + ('✓' if info['ddagrab'] else '✗ (precisa FFmpeg 6+)')]
            if info['h264_amf']:
                ok, message = check_encoder(exe, 'h264_amf')
                parts.append('AMD AMF ' + ('✓ funciona' if ok else f'listado, mas FALHOU: {message[-90:]}'))
            else:
                parts.append('AMD AMF ✗ (não listado neste FFmpeg)')
            text = ' · '.join(parts)
        self.root.after(0, lambda: self.ffmpeg_info.set(text))

    def open_log(self):
        path = BASE / 'openmirror_server.log'
        if path.exists():
            os.startfile(path)  # type: ignore[attr-defined]
        else:
            messagebox.showinfo('Log', 'Ainda não há log. Inicie a transmissão primeiro.')

    def change_preset(self, _selection):
        fps, width, kbps = PRESETS[self.preset.get()]
        self.fps.set(fps)
        self.width.set(width)
        self.bitrate.set(kbps)

    def toggle(self):
        if self.process is not None and self.process.poll() is None:
            self.stop()
        else:
            self.start()

    def start(self):
        pin = generate_pin()
        args = server_args(sys.executable, str(BASE / 'server.py'), fps=self.fps.get(),
                           width=self.width.get(), bitrate=self.bitrate.get(),
                           encoder=self.encoder.get(), capture=self.capture.get(),
                           eager_flush=self.eager.get())
        env = dict(os.environ, OPENMIRROR_PIN=pin)  # PIN fora da linha de comando
        creationflags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
        try:
            self.log_handle = (BASE / 'openmirror_server.log').open('a', encoding='utf-8')
            self.process = subprocess.Popen(args, cwd=BASE, creationflags=creationflags, env=env,
                                            stdout=self.log_handle, stderr=subprocess.STDOUT)
        except OSError as exc:
            if self.log_handle:
                self.log_handle.close()
                self.log_handle = None
            messagebox.showerror('Erro ao iniciar', str(exc))
            return
        self.pin.set(pin)
        self.state.set('● Transmitindo na rede local')
        self.button.configure(text='■  PARAR TRANSMISSÃO', bg='#353f52')

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.process = None
        if self.log_handle:
            self.log_handle.close()
            self.log_handle = None
        self.pin.set('-' * PIN_LENGTH)
        self.state.set('Servidor desligado')
        self.button.configure(text='▶  INICIAR TRANSMISSÃO', bg=ACCENT)

    def check(self):
        if self.process is not None and self.process.poll() is not None:
            self.stop()
            self.state.set('Servidor encerrou. Consulte openmirror_server.log.')
        self.root.after(800, self.check)

    def close(self):
        self.stop()
        self.root.destroy()


if __name__ == '__main__':
    if os.name != 'nt':
        raise SystemExit('O painel Windows só roda no Windows.')
    Window().root.mainloop()
