import os
import socket
import struct
import time

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


def send_action(sock, action_type, card_index=0, target_index=0):
    action = sts_state_pb2.GameAction(
        action_type=action_type,
        card_index=card_index,
        target_index=target_index,
    )
    payload = action.SerializeToString()
    sock.sendall(struct.pack(">I", len(payload)) + payload)
    print(
        f"[PY-CLIENT] Sent GameAction: {action_type}, "
        f"card_index={card_index}, target_index={target_index}"
    )


def is_strike(card):
    if card.id == "Strike_R":
        return True
    name = card.name or ""
    if "打击" in name or "Strike" in name:
        return True
    return False


def main():
    try:
        with socket.create_connection((HOST, PORT)) as sock:
            print(f"[PY-CLIENT] Connected to {HOST}:{PORT}")
            while True:
                header = recv_exact(sock, HEADER_SIZE)
                (length,) = struct.unpack(">I", header)
                if length <= 0:
                    continue
                body = recv_exact(sock, length)
                msg = sts_state_pb2.GameState()
                msg.ParseFromString(body)
                player = msg.player
                energy = player.energy
                monsters = list(msg.monsters)
                alive_monsters = [i for i, m in enumerate(monsters) if m.hp > 0]
                if not alive_monsters:
                    continue
                if energy <= 0:
                    continue
                hand = list(msg.hand)
                strike_indices = [i for i, c in enumerate(hand) if is_strike(c) and c.cost <= energy]
                if not strike_indices:
                    send_action(sock, "END_TURN")
                    time.sleep(1.0)
                    continue
                for idx in strike_indices:
                    card = hand[idx]
                    cost = max(card.cost, 0)
                    if cost > energy:
                        continue
                    target_idx = alive_monsters[0] if len(alive_monsters) == 1 else alive_monsters[len(alive_monsters) - 1]
                    send_action(sock, "PLAY_CARD", card_index=idx, target_index=target_idx)
                    energy -= cost
                    time.sleep(0.5)
                    if energy <= 0:
                        break
                if energy <= 0:
                    send_action(sock, "END_TURN")
                    time.sleep(1.0)
    except ConnectionRefusedError as e:
        print(f"[PY-CLIENT] Failed to connect to {HOST}:{PORT}: {e}")
    except OSError as e:
        print(f"[PY-CLIENT] Socket error when connecting to {HOST}:{PORT}: {e}")


if __name__ == "__main__":
    main()
