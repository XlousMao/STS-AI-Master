import socket
import struct
import time
from gym_sts.protos import sts_state_pb2

class STSCommunicator:
    def __init__(self, port=9999):
        self.host = 'localhost'
        self.port = port
        self.socket = None
        self.connected = False

    def connect(self):
        """Establish connection to the Java bridge."""
        if self.connected:
            return

        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            # Disable Nagle's algorithm for lower latency
            self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.socket.connect((self.host, self.port))
            self.connected = True
            print(f"Connected to STS Bridge on port {self.port}")
        except ConnectionRefusedError:
            print(f"Connection refused on port {self.port}. Is the game running?")
            raise
        except Exception as e:
            print(f"Failed to connect: {e}")
            raise

    def send_message(self, command_type, card_index=0, target_index=0):
        """Send a GameAction to the bridge."""
        if not self.connected:
            raise RuntimeError("Not connected to STS Bridge")

        action = sts_state_pb2.GameAction()
        action.action_type = command_type
        action.card_index = card_index
        action.target_index = target_index

        payload = action.SerializeToString()
        try:
            # Send length (4 bytes, big endian)
            self.socket.sendall(struct.pack('>I', len(payload)))
            # Send payload
            self.socket.sendall(payload)
        except Exception as e:
            print(f"Error sending message: {e}")
            self.close()
            raise

    def receive_state(self):
        """Receive and parse a GameState from the bridge."""
        if not self.connected:
            raise RuntimeError("Not connected to STS Bridge")

        try:
            # Read length (4 bytes)
            length_bytes = self._recv_all(4)
            if not length_bytes:
                return None
            
            length = struct.unpack('>I', length_bytes)[0]
            
            # Read payload
            payload = self._recv_all(length)
            if not payload:
                return None

            # Parse Protobuf
            game_state = sts_state_pb2.GameState()
            game_state.ParseFromString(payload)
            return game_state

        except Exception as e:
            print(f"Error receiving state: {e}")
            self.close()
            raise

    def _recv_all(self, n):
        """Helper to receive exactly n bytes."""
        data = bytearray()
        while len(data) < n:
            packet = self.socket.recv(n - len(data))
            if not packet:
                return None
            data.extend(packet)
        return data

    def close(self):
        """Close the connection."""
        if self.socket:
            try:
                self.socket.close()
            except:
                pass
        self.socket = None
        self.connected = False
