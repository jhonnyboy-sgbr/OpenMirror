"""Painel Windows para iniciar/parar o servidor OpenMirror."""
import os
import json
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
SETTINGS_FILE = BASE / 'openmirror_settings.json'
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
        self.active_settings = tk.StringVar()
        self.load_settings()
        self.build()
        self.update_active_settings()
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        self.root.after(800, self.check)
        threading.Thread(target=self.probe, daemon=True).start()

    def label(self, text: str, size=11, bold=False, color=TEXT, parent=None):
        owner = parent or self.root
        return tk.Label(owner, text=text, bg=owner.cget('bg'),
                        fg=color, font=('Segoe UI', size, 'bold' if bold else 'normal'))

    def load_settings(self):
        if not SETTINGS_FILE.exists():
            return
        try:
            values = json.loads(SETTINGS_FILE.read_text(encoding='utf-8'))
            if not isinstance(values, dict):
                raise ValueError('formato inválido')
            self.fps.set(values['fps'])
            self.width.set(values['width'])
            self.bitrate.set(values['bitrate'])
            self.encoder.set(values['encoder'])
            self.capture.set(values['capture'])
            self.eager.set(values['eager'])
            if self.encoder.get() not in ('software', 'amd'):
                raise ValueError('encoder inválido')
            if self.capture.get() not in ('gdigrab', 'ddagrab', 'auto'):
                raise ValueError('captura inválida')
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            messagebox.showwarning(
                'Configurações',
                f'Não foi possível carregar as configurações salvas. '
                f'Os valores padrão serão usados.\n\n{exc}'
            )
            self.fps.set(30)
            self.width.set(640)
            self.bitrate.set(3000)
            self.encoder.set('software')
            self.capture.set('auto')
            self.eager.set(False)

    def save_settings(self):
        values = {
            'fps': self.fps.get(),
            'width': self.width.get(),
            'bitrate': self.bitrate.get(),
            'encoder': self.encoder.get(),
            'capture': self.capture.get(),
            'eager': self.eager.get(),
        }
        temporary = SETTINGS_FILE.with_suffix('.tmp')
        temporary.write_text(json.dumps(values, indent=2), encoding='utf-8')
        temporary.replace(SETTINGS_FILE)

    def build(self):
        self.main_frame = tk.Frame(self.root, bg=BG)
        self.main_frame.pack(fill='both', expand=True)
        header = tk.Frame(self.main_frame, bg=BG)
        header.pack(fill='x', padx=25, pady=(20, 2))
        self.label('◉  OpenMirror', 25, True, parent=header).pack(side='left', padx=(48, 0))
        self.label('Seu computador, na palma da mão.', 11, color=MUTED,
                   parent=self.main_frame).pack(anchor='w', padx=31)
        box = tk.Frame(self.main_frame, bg=CARD, padx=23, pady=19)
        box.pack(fill='x', padx=25, pady=(21, 10))
        self.label('ENDEREÇO DO COMPUTADOR', 9, True, MUTED, box).pack(anchor='w')
        self.label(self.address.get(), 17, True, TEXT, box).pack(anchor='w', pady=(5, 13))
        self.label('CÓDIGO DE PAREAMENTO', 9, True, MUTED, box).pack(anchor='w')
        tk.Label(box, textvariable=self.pin, font=('Consolas', 29, 'bold'),
                 fg='#82adff', bg=CARD).pack(anchor='w', pady=(2, 0))
        self.label('Digite este código no celular para autorizar a conexão.', 9,
                   color=MUTED, parent=box).pack(anchor='w')
        self.button = tk.Button(self.main_frame, text='▶  INICIAR TRANSMISSÃO',
                                command=self.toggle, bg=ACCENT, fg='white',
                                activebackground='#2766cc', activeforeground='white',
                                borderwidth=0, font=('Segoe UI', 12, 'bold'), pady=13,
                                cursor='hand2')
        self.button.pack(fill='x', padx=25, pady=(15, 9))
        tk.Label(self.main_frame, textvariable=self.active_settings, fg=MUTED, bg=BG,
                 font=('Segoe UI', 9)).pack(pady=(0, 7))
        status = tk.Frame(self.main_frame, bg=BG)
        status.pack()
        self.status_light = tk.Canvas(status, width=14, height=14, bg=BG,
                                      highlightthickness=0)
        self.status_light.pack(side='left', padx=(0, 7))
        self.status_light.create_oval(2, 2, 12, 12, fill='#e05252', outline='')
        tk.Label(status, textvariable=self.state, fg=MUTED, bg=BG,
                 font=('Segoe UI', 10)).pack(side='left')
        tk.Button(self.main_frame, text='Abrir arquivo de log', command=self.open_log, bg=CARD, fg=TEXT,
                  borderwidth=0, font=('Segoe UI', 9), cursor='hand2').pack(pady=(10, 0))
        self.label('Uso recomendado em uma rede local privada.', 9,
                   color=MUTED, parent=self.main_frame).pack(pady=12)

        self.sidebar = tk.Frame(self.root, bg=CARD, width=260)
        self.sidebar_x = -260
        self.sidebar_open = False
        self.sidebar.place(x=self.sidebar_x, y=0, relheight=1)
        self.sidebar.pack_propagate(False)
        self.build_sidebar()
        self.menu_button = tk.Button(
            self.root, text='☰', command=self.toggle_sidebar, bg=BG, fg=TEXT,
            activebackground=CARD, activeforeground=TEXT, borderwidth=0,
            font=('Segoe UI', 16), cursor='hand2', padx=10, pady=4
        )
        self.menu_button.place(x=8, y=18)
        self.active_page = self.main_frame

    def build_sidebar(self):
        tk.Label(self.sidebar, text='OpenMirror', bg=CARD, fg=TEXT,
                 font=('Segoe UI', 18, 'bold')).pack(anchor='w', padx=22, pady=(28, 4))
        tk.Label(self.sidebar, text='MENU', bg=CARD, fg=MUTED,
                 font=('Segoe UI', 9, 'bold')).pack(anchor='w', padx=22, pady=(18, 8))
        self.sidebar_button('⌂  Painel', self.show_dashboard)
        self.sidebar_button('⚙  Configurações', self.show_settings)
        self.sidebar_button('ⓘ  Sobre o OpenMirror', self.show_about)
        tk.Frame(self.sidebar, bg='#263247', height=1).pack(fill='x', padx=22, pady=18)
        tk.Button(self.sidebar, text='Fechar menu', command=self.toggle_sidebar,
                  bg=CARD, fg=MUTED, activebackground=CARD, activeforeground=TEXT,
                  borderwidth=0, font=('Segoe UI', 10), cursor='hand2').pack(anchor='w', padx=18)

    def sidebar_button(self, text, command):
        tk.Button(self.sidebar, text=text, command=command, anchor='w',
                  bg=CARD, fg=TEXT, activebackground=ACCENT, activeforeground='white',
                  borderwidth=0, font=('Segoe UI', 11), cursor='hand2',
                  padx=22, pady=11).pack(fill='x')

    def toggle_sidebar(self):
        target = 0 if not self.sidebar_open else -self.sidebar.winfo_width()
        self.sidebar_open = not self.sidebar_open
        self.animate_sidebar(target)

    def animate_sidebar(self, target):
        current = self.sidebar.winfo_x()
        if current == target:
            return
        step = 32 if target > current else -32
        next_x = current + step
        if (step > 0 and next_x > target) or (step < 0 and next_x < target):
            next_x = target
        self.sidebar.place_configure(x=next_x)
        self.menu_button.place_configure(x=max(8, next_x + 220))
        self.root.after(12, lambda: self.animate_sidebar(target))

    def show_dashboard(self):
        self.show_page(self.main_frame)
        self.close_sidebar()

    def close_sidebar(self):
        if self.sidebar_open:
            self.toggle_sidebar()

    def show_page(self, page):
        if getattr(self, 'active_page', None) is page:
            return
        if getattr(self, 'active_page', None) is not None:
            self.active_page.pack_forget()
        page.pack(fill='both', expand=True)
        self.active_page = page

    def show_settings(self):
        if not hasattr(self, 'settings_page'):
            self.settings_fps = tk.IntVar(value=self.fps.get())
            self.settings_width = tk.IntVar(value=self.width.get())
            self.settings_bitrate = tk.IntVar(value=self.bitrate.get())
            self.settings_encoder = tk.StringVar(value=self.encoder.get())
            self.settings_capture = tk.StringVar(value=self.capture.get())
            self.settings_eager = tk.BooleanVar(value=self.eager.get())
            self.settings_preset = tk.StringVar(value='Personalizado')
            window = tk.Frame(self.root, bg=BG)
            self.page_header(window, 'Configurações')
            tk.Label(window, text='Configurações', bg=BG, fg=TEXT,
                 font=('Segoe UI', 18, 'bold')).pack(anchor='w', padx=25, pady=(8, 4))
            tk.Label(window, text='Ajuste os parâmetros usados na próxima transmissão.',
                 bg=BG, fg=MUTED, font=('Segoe UI', 10)).pack(anchor='w', padx=25, pady=(0, 18))

            form = tk.Frame(window, bg=BG)
            form.pack(fill='x', padx=25)

            def option(row, label, variable, values):
                tk.Label(form, text=label, bg=BG, fg=MUTED,
                     font=('Segoe UI', 10)).grid(row=row, column=0, sticky='w', pady=6)
                tk.OptionMenu(form, variable, *values).grid(row=row, column=1, sticky='e', padx=(20, 0))

            option(0, 'FPS', self.settings_fps, (12, 20, 24, 30, 45, 60))
            option(1, 'Largura do vídeo', self.settings_width, (640, 854, 960, 1280, 1600, 1920))
            option(2, 'Encoder H.264', self.settings_encoder, ('software', 'amd'))
            option(3, 'Bitrate (kbps)', self.settings_bitrate, (1500, 3000, 5000, 6000, 8000, 12000, 16000))
            option(4, 'Captura', self.settings_capture, ('gdigrab', 'ddagrab', 'auto'))
            tk.Checkbutton(form, text='Menos atraso (experimental)', variable=self.settings_eager,
                       bg=BG, fg=MUTED, selectcolor=CARD, activebackground=BG,
                       activeforeground=TEXT).grid(row=5, column=0, columnspan=2, sticky='w', pady=10)

            preset_row = tk.Frame(window, bg=BG)
            preset_row.pack(fill='x', padx=25, pady=(8, 4))
            tk.Label(preset_row, text='Pré-perfil', bg=BG, fg=MUTED,
                     font=('Segoe UI', 10)).pack(side='left')
            tk.OptionMenu(preset_row, self.settings_preset, 'Personalizado', *PRESETS,
                          command=self.select_settings_preset).pack(side='left', padx=(20, 0))
            tk.Button(window, text='Aplicar configurações', command=self.apply_settings,
                  bg=ACCENT, fg='white', activebackground='#2766cc', activeforeground='white',
                  borderwidth=0, font=('Segoe UI', 10, 'bold'), cursor='hand2',
                  padx=16, pady=9).pack(anchor='w', padx=25, pady=(14, 4))
            self.settings_status = tk.Label(window, text='Alterações pendentes não foram aplicadas.',
                 bg=BG, fg=MUTED, font=('Segoe UI', 9))
            self.settings_status.pack(anchor='w', padx=25, pady=(4, 0))
            tk.Label(window, text='As alterações só entram em vigor depois de clicar em Aplicar configurações.',
                 bg=BG, fg=MUTED, font=('Segoe UI', 9)).pack(anchor='w', padx=25, pady=(4, 0))
            tk.Label(window, text='Captura: auto recomenda DXGI quando disponível e usa GDI como fallback.\n'
                     'H.264 exige FFmpeg. AMD AMF e a captura DXGI são opções experimentais.',
                     bg=BG, fg=MUTED, justify='left', font=('Segoe UI', 9)).pack(anchor='w', padx=25, pady=(18, 0))
            tk.Label(window, textvariable=self.ffmpeg_info, bg=BG, fg=MUTED,
                     justify='left', wraplength=540, font=('Segoe UI', 9)).pack(anchor='w', padx=25, pady=(8, 0))
            self.settings_page = window
        else:
            self.sync_settings_from_applied()
        self.show_page(self.settings_page)
        self.close_sidebar()

    def sync_settings_from_applied(self):
        self.settings_fps.set(self.fps.get())
        self.settings_width.set(self.width.get())
        self.settings_bitrate.set(self.bitrate.get())
        self.settings_encoder.set(self.encoder.get())
        self.settings_capture.set(self.capture.get())
        self.settings_eager.set(self.eager.get())
        self.settings_preset.set(self.match_preset())

    def match_preset(self):
        values = (self.fps.get(), self.width.get(), self.bitrate.get())
        for name, preset_values in PRESETS.items():
            if preset_values == values:
                return name
        return 'Personalizado'

    def select_settings_preset(self, selection):
        if selection == 'Personalizado':
            return
        fps, width, kbps = PRESETS[selection]
        self.settings_fps.set(fps)
        self.settings_width.set(width)
        self.settings_bitrate.set(kbps)

    def apply_settings(self):
        self.fps.set(self.settings_fps.get())
        self.width.set(self.settings_width.get())
        self.bitrate.set(self.settings_bitrate.get())
        self.encoder.set(self.settings_encoder.get())
        self.capture.set(self.settings_capture.get())
        self.eager.set(self.settings_eager.get())
        try:
            self.save_settings()
        except OSError as exc:
            messagebox.showerror('Configurações', f'Não foi possível salvar as configurações.\n\n{exc}')
            self.settings_status.configure(text='Aplicadas nesta sessão, mas não foram salvas.',
                                           fg='#ff9b9b')
            return
        self.update_active_settings()
        self.settings_status.configure(text='Configurações aplicadas. Valem para a próxima transmissão.',
                                       fg='#82adff')

    def update_active_settings(self):
        preset = self.match_preset()
        preset_text = f' · Perfil: {preset}' if preset != 'Personalizado' else ''
        self.active_settings.set(
            f'Configuração atual: {self.fps.get()} FPS · {self.width.get()} px · '
            f'{self.bitrate.get()} kbps{preset_text}'
        )

    def show_about(self):
        if not hasattr(self, 'about_page'):
            window = tk.Frame(self.root, bg=BG)
            self.page_header(window, 'Sobre o OpenMirror')
            tk.Label(window, text='Sobre o OpenMirror', bg=BG, fg=TEXT,
                     font=('Segoe UI', 18, 'bold')).pack(anchor='w', padx=25, pady=(8, 8))
            tk.Label(window, text=f'OpenMirror {VERSION}', bg=BG, fg=ACCENT,
                     font=('Segoe UI', 14, 'bold')).pack(anchor='w', padx=25, pady=(8, 16))
            tk.Label(window, text='Espelhamento e controle remoto do Windows pelo Android.\n\n'
                     'Uso recomendado: rede local privada.\n\n'
                     'Servidor HTTP/WebSocket · H.264 · MJPEG',
                     bg=BG, fg=MUTED, justify='left', font=('Segoe UI', 10)).pack(anchor='w', padx=25)
            self.about_page = window
        self.show_page(self.about_page)
        self.close_sidebar()

    def page_header(self, parent, title):
        header = tk.Frame(parent, bg=BG)
        header.pack(fill='x', padx=18, pady=(18, 0))
        tk.Button(header, text='←  Voltar ao painel', command=self.show_dashboard,
                  bg=BG, fg=MUTED, activebackground=BG, activeforeground=TEXT,
                  borderwidth=0, font=('Segoe UI', 10), cursor='hand2').pack(side='left')
        tk.Label(header, text=title, bg=BG, fg=MUTED,
                 font=('Segoe UI', 10, 'bold')).pack(side='right')

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
        self.set_server_status(True, 'Transmitindo na rede local')
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
        self.set_server_status(False, 'Servidor desligado')
        self.button.configure(text='▶  INICIAR TRANSMISSÃO', bg=ACCENT)

    def set_server_status(self, online, text):
        self.state.set(text)
        self.status_light.itemconfigure(
            1, fill='#41c778' if online else '#e05252'
        )

    def check(self):
        if self.process is not None and self.process.poll() is not None:
            self.stop()
            self.set_server_status(False, 'Servidor encerrou. Consulte openmirror_server.log.')
        self.root.after(800, self.check)

    def close(self):
        self.stop()
        self.root.destroy()


if __name__ == '__main__':
    if os.name != 'nt':
        raise SystemExit('O painel Windows só roda no Windows.')
    Window().root.mainloop()
