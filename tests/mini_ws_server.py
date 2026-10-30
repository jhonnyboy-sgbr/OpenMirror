"""Servidor WebSocket mínimo (RFC 6455, só stdlib), escrito de forma independente do cliente Java.
Serve para testar o WsClient do Android contra OUTRA implementação do protocolo."""
import asyncio
import base64
import hashlib
import struct

GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'


class MiniWsServer:
    def __init__(self, token='tok'):
        self.token = token
        self.errors = []
        self.pongs = 0
        self.server = None
        self.port = None

    async def start(self):
        self.server = await asyncio.start_server(self.handle, '127.0.0.1', 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def stop(self):
        self.server.close()
        await self.server.wait_closed()

    @staticmethod
    def frame(op, payload=b'', fin=True):
        head = bytes([(0x80 if fin else 0) | op])
        n = len(payload)
        if n < 126:
            head += bytes([n])
        elif n <= 0xFFFF:
            head += bytes([126]) + struct.pack('!H', n)
        else:
            head += bytes([127]) + struct.pack('!Q', n)
        return head + payload

    async def handle(self, reader, writer):
        try:
            raw = await reader.readuntil(b'\r\n\r\n')
            lines = raw.decode('latin1').split('\r\n')
            headers = {}
            for line in lines[1:]:
                if ':' in line:
                    k, v = line.split(':', 1)
                    headers[k.strip().lower()] = v.strip()
            if headers.get('x-openmirror-session') != self.token:
                writer.write(b'HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n')
                await writer.drain()
                return
            accept = base64.b64encode(hashlib.sha1((headers['sec-websocket-key'] + GUID).encode()).digest()).decode()
            writer.write(('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                          f'Sec-WebSocket-Accept: {accept}\r\n\r\n').encode())
            writer.write(self.frame(0x9, b'hb'))              # ping do servidor (como o heartbeat do aiohttp)
            await writer.drain()
            while True:
                b0, b1 = await reader.readexactly(2)
                op, masked, n = b0 & 0x0F, bool(b1 & 0x80), b1 & 0x7F
                if n == 126:
                    n = struct.unpack('!H', await reader.readexactly(2))[0]
                elif n == 127:
                    n = struct.unpack('!Q', await reader.readexactly(8))[0]
                if not masked:
                    self.errors.append('cliente mandou quadro SEM máscara')
                mask = await reader.readexactly(4) if masked else b'\0\0\0\0'
                data = bytearray(await reader.readexactly(n))
                for i in range(n):
                    data[i] ^= mask[i & 3]
                if op == 0x8:
                    writer.write(self.frame(0x8, bytes(data[:2])))
                    await writer.drain()
                    return
                if op == 0xA:
                    self.pongs += 1
                    if bytes(data) != b'hb':
                        self.errors.append('pong com conteúdo diferente do ping')
                    continue
                if op != 0x1:
                    continue
                text = data.decode('utf-8')
                if text == 'big':
                    writer.write(self.frame(0x1, ('y' * 70000).encode()))
                elif text == 'frag':
                    writer.write(self.frame(0x1, b'ab', fin=False) + self.frame(0x0, b'cd', fin=True))
                elif text == 'unicode':
                    writer.write(self.frame(0x1, 'olá 😀'.encode()))
                elif text == 'pongs':
                    writer.write(self.frame(0x1, f'pongs:{self.pongs}'.encode()))
                elif text == 'close':
                    writer.write(self.frame(0x8, struct.pack('!H', 1000)))
                else:
                    writer.write(self.frame(0x1, f'echo:{len(text)}:{text[:20]}'.encode()))
                await writer.drain()
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            writer.close()
