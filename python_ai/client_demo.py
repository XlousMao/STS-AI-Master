import os
import socket
import struct

os.environ.setdefault("PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION", "python")

from google.protobuf.json_format import MessageToJson

import sts_state_pb2


HOST = "127.0.0.1"
PORT = 9999
HEADER_SIZE = 4


def recv_exact(sock, size):
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("socket closed while receiving data")
        data += chunk
    return data


def main():
    try:
        with socket.create_connection((HOST, PORT)) as sock:
            print(f"[PY-CLIENT] Connected to {HOST}:{PORT}")
            action = sts_state_pb2.GameAction(
                action_type="END_TURN",
                card_index=0,
                target_index=0,
            )
            action_payload = action.SerializeToString()
            sock.sendall(struct.pack(">I", len(action_payload)) + action_payload)
            print("[PY-CLIENT] Sent GameAction: END_TURN")
            while True:
                header = recv_exact(sock, HEADER_SIZE)
                (length,) = struct.unpack(">I", header)
                if length <= 0:
                    continue
                body = recv_exact(sock, length)
                msg = sts_state_pb2.GameState()
                msg.ParseFromString(body)
                json_str = MessageToJson(
                    msg,
                    always_print_fields_with_no_presence=True,
                    preserving_proto_field_name=True,
                )
                print("[PY-CLIENT] Received GameState:")
                print(json_str)
    except ConnectionRefusedError as e:
        print(f"[PY-CLIENT] Failed to connect to {HOST}:{PORT}: {e}")
    except OSError as e:
        print(f"[PY-CLIENT] Socket error when connecting to {HOST}:{PORT}: {e}")


if __name__ == "__main__":
    main()
