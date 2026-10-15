#!/usr/bin/env python3
"""Does the SQL Server at <host> <port> refuse a client that will not do TLS?

smoke.sh's TLS check (infra/lib.sh, check_tls_enforced) pipes this to the
instance, where python3 is part of the OS, and runs it against RDS. It speaks
TDS (MS-TDS) itself, because every SQL Server client the instance has will
encrypt when the server asks it to:

  1. PRELOGIN, its ENCRYPTION option ENCRYPT_NOT_SUP: this client cannot do TLS;
  2. LOGIN7, in plaintext, for a login that does not exist, with a random
     password, so no credential ever travels unencrypted.

A server that forces encryption (rds.force_ssl = 1) answers the PRELOGIN with
ENCRYPT_REQ and closes the connection on the plaintext LOGIN7, unread. One that
does not force it reads the LOGIN7 and answers it in plaintext, here with a
failed-login error, since the login does not exist: any answer to the LOGIN7
means the server accepted a session without TLS.

Exit status: 0 refused, 1 accepted in plaintext, 2 the probe could not tell.
Standard library only; no argument is a secret.
"""
import secrets
import socket
import struct
import sys

PRELOGIN, LOGIN7 = 0x12, 0x10
ENCRYPT_NOT_SUP = 0x02
ENCRYPTION_NAMES = {0x00: "ENCRYPT_OFF", 0x01: "ENCRYPT_ON", 0x02: "ENCRYPT_NOT_SUP", 0x03: "ENCRYPT_REQ"}
PROBE_LOGIN = "tls_probe_no_such_login"


def packet(kind, payload):
    """One TDS packet, end of message: header (type, status, length, spid, id, window)."""
    return struct.pack(">BBHHBB", kind, 0x01, 8 + len(payload), 0, 1, 0) + payload


def receive(sock, count):
    """count bytes, or None when the server closes or resets the connection first."""
    data = b""
    while len(data) < count:
        try:
            chunk = sock.recv(count - len(data))
        except (ConnectionResetError, socket.timeout):
            return None
        if not chunk:
            return None
        data += chunk
    return data


def receive_message(sock):
    """One TDS message's payload, or None when the server closes the connection."""
    payload = b""
    while True:
        header = receive(sock, 8)
        if header is None:
            return None
        status, length = header[1], struct.unpack(">H", header[2:4])[0]
        body = receive(sock, length - 8)
        if body is None:
            return None
        payload += body
        if status & 0x01:
            return payload


def prelogin():
    """VERSION and ENCRYPTION (ENCRYPT_NOT_SUP), then the terminator."""
    options = [(0x00, b"\x10\x00\x00\x00\x00\x00"), (0x01, bytes([ENCRYPT_NOT_SUP]))]
    offset = 5 * len(options) + 1
    table, data = b"", b""
    for token, value in options:
        table += struct.pack(">BHH", token, offset + len(data), len(value))
        data += value
    return table + b"\xff" + data


def encryption_of(payload):
    """The ENCRYPTION option of a PRELOGIN answer."""
    index = 0
    while index + 5 <= len(payload) and payload[index] != 0xFF:
        token, offset, _ = struct.unpack(">BHH", payload[index:index + 5])
        if token == 0x01 and offset < len(payload):
            return payload[offset]
        index += 5
    return None


def login7(login, password):
    """A TDS 7.4 LOGIN7 for SQL authentication; strings UTF-16LE, the password scrambled as TDS requires."""
    def scrambled(text):
        return bytes((((b << 4) & 0xF0) | (b >> 4)) ^ 0xA5 for b in text.encode("utf-16-le"))

    fixed = 94
    fields = [  # HostName, UserName, Password, AppName, ServerName, Extension, CltIntName, Language, Database
        "tls-probe", login, None, "tls-probe", "", "", "tls-probe", "", "master",
    ]
    pairs, data = b"", b""
    for field in fields:
        raw = scrambled(password) if field is None else field.encode("utf-16-le")
        chars = len(password) if field is None else len(field)
        pairs += struct.pack("<HH", fixed + len(data), chars)
        data += raw
    end = struct.pack("<HH", fixed + len(data), 0)
    tail = b"\x00" * 6 + end + end + end + struct.pack("<I", 0)  # ClientID, SSPI, AtchDBFile, ChangePassword, cbSSPILong
    head = struct.pack("<IIIIII", 0, 0x74000004, 4096, 0, 0, 0) + bytes([0xE0, 0x03, 0x00, 0x00]) \
        + struct.pack("<iI", 0, 0x0409)
    body = head + pairs + tail
    assert len(body) == fixed
    message = body + data
    return struct.pack("<I", len(message)) + message[4:]


def main():
    if len(sys.argv) != 3:
        print("usage: tds-plaintext-probe.py <host> <port>", file=sys.stderr)
        return 2
    host, port = sys.argv[1], int(sys.argv[2])
    try:
        sock = socket.create_connection((host, port), timeout=15)
    except OSError as error:
        print(f"cannot reach {host}:{port}: {error}")
        return 2
    with sock:
        sock.sendall(packet(PRELOGIN, prelogin()))
        answer = receive_message(sock)
        if answer is None:
            print("a client that cannot do TLS is refused: the server closed the connection on its PRELOGIN")
            return 0
        encryption = encryption_of(answer)
        name = ENCRYPTION_NAMES.get(encryption, f"unknown ({encryption})")
        try:
            sock.sendall(packet(LOGIN7, login7(PROBE_LOGIN, secrets.token_urlsafe(24))))
        except (BrokenPipeError, ConnectionResetError):
            print(f"a client that cannot do TLS is refused: the server answered {name} and closed the connection")
            return 0
        reply = receive_message(sock)
        if reply is None:
            print(f"a client that cannot do TLS is refused: the server answered {name} and closed the connection"
                  " on its plaintext login, unread")
            return 0
        print(f"a client that cannot do TLS was ACCEPTED: the server answered {name}, then answered its"
              f" plaintext login (token 0x{reply[0]:02x})")
        return 1


if __name__ == "__main__":
    sys.exit(main())
