import gym
import numpy as np
import time
import random
from gym import spaces
from gym_sts.utils.communication import STSCommunicator
from gym_sts.protos import sts_state_pb2

class SlayTheSpireEnv(gym.Env):
    metadata = {'render_modes': ['human', 'log']}

    def __init__(self, run_mode='headless', port=9999, normalize_obs=True, seed=42):
        super(SlayTheSpireEnv, self).__init__()
        self.run_mode = run_mode
        self.port = port
        self.normalize_obs = normalize_obs
        self.seed_value = seed
        self._seed(seed)

        self.communicator = STSCommunicator(port=port)
        self.game_state = None
        self.prev_game_state = None
        
        # --- Action Space Configuration ---
        self.MAX_HAND_CARDS = 10
        self.MAX_MAP_NODES = 5
        self.MAX_SHOP_CARDS = 7
        self.MAX_SHOP_RELICS = 3
        self.MAX_SHOP_POTIONS = 3
        self.MAX_REWARD_ITEMS = 5
        self.MAX_REST_OPTIONS = 5
        
        # Offsets
        self.ACTION_OFFSETS = {
            "PLAY_CARD": 0,          # 0-9
            "END_TURN": 10,          # 10
            "CHOOSE_MAP_NODE": 11,   # 11-15
            "BUY_CARD": 16,          # 16-22
            "BUY_RELIC": 23,         # 23-25
            "BUY_POTION": 26,        # 26-28
            "PURGE_CARD": 29,        # 29-35
            "LEAVE_SHOP": 36,        # 36
            "CHOOSE_REST": 37,       # 37-41 (Rest=37, Smith=38...)
            "LEAVE_REST": 42,        # 42
            "CHOOSE_REWARD": 43,     # 43-47
            "SKIP_REWARD": 48        # 48
        }
        self.ACTION_SPACE_SIZE = 50
        self.action_space = spaces.Discrete(self.ACTION_SPACE_SIZE)
        
        # --- Observation Space Configuration ---
        # Normalization Constants
        self.MAX_HP = 100.0 # Normalized usually to max_hp, but for global norm use 100 or 300
        self.MAX_GOLD = 2000.0
        self.MAX_ENERGY = 10.0
        self.MAX_BLOCK = 100.0
        self.MAX_FLOOR = 60.0
        
        self.observation_space = spaces.Dict({
            "player": spaces.Dict({
                "current_hp": spaces.Box(low=0, high=1.0, shape=(1,), dtype=np.float32),
                "max_hp": spaces.Box(low=0, high=1.0, shape=(1,), dtype=np.float32),
                "gold": spaces.Box(low=0, high=1.0, shape=(1,), dtype=np.float32),
                "energy": spaces.Box(low=0, high=1.0, shape=(1,), dtype=np.float32),
                "block": spaces.Box(low=0, high=1.0, shape=(1,), dtype=np.float32),
                "floor": spaces.Box(low=0, high=1.0, shape=(1,), dtype=np.float32),
            }),
            "monsters": spaces.Box(low=0, high=1.0, shape=(5, 6), dtype=np.float32), # 5 monsters, 6 features
            "hand": spaces.Box(low=0, high=1.0, shape=(10, 10), dtype=np.float32), # 10 cards, 10 features
            "game_global": spaces.Dict({
                "is_combat": spaces.Discrete(2),
                "screen_type": spaces.Discrete(10), # Mapped manually
            })
        })
        
        # Screen Type Mapping
        self.SCREEN_TYPES = {
            "NONE": 0, "COMBAT": 1, "MAP": 2, "SHOP": 3, 
            "REST": 4, "REWARD": 5, "EVENT": 6, 
            "GAME_OVER": 7, "VICTORY": 8, "UNKNOWN": 9
        }
        
        # Internal state
        self.current_step = 0
        self.max_steps = 1000
        
        # Action Map for Debugging
        self.action_map = self._build_action_map()

    def _seed(self, seed=None):
        if seed is not None:
            random.seed(seed)
            np.random.seed(seed)
        return [seed]

    def _build_action_map(self):
        m = {}
        for i in range(10): m[self.ACTION_OFFSETS["PLAY_CARD"] + i] = f"PLAY_CARD_{i}"
        m[self.ACTION_OFFSETS["END_TURN"]] = "END_TURN"
        for i in range(5): m[self.ACTION_OFFSETS["CHOOSE_MAP_NODE"] + i] = f"CHOOSE_MAP_NODE_{i}"
        for i in range(7): m[self.ACTION_OFFSETS["BUY_CARD"] + i] = f"BUY_CARD_{i}"
        for i in range(3): m[self.ACTION_OFFSETS["BUY_RELIC"] + i] = f"BUY_RELIC_{i}"
        for i in range(3): m[self.ACTION_OFFSETS["BUY_POTION"] + i] = f"BUY_POTION_{i}"
        for i in range(7): m[self.ACTION_OFFSETS["PURGE_CARD"] + i] = f"PURGE_CARD_{i}"
        m[self.ACTION_OFFSETS["LEAVE_SHOP"]] = "LEAVE_SHOP"
        for i in range(5): m[self.ACTION_OFFSETS["CHOOSE_REST"] + i] = f"CHOOSE_REST_{i}"
        m[self.ACTION_OFFSETS["LEAVE_REST"]] = "LEAVE_REST"
        for i in range(5): m[self.ACTION_OFFSETS["CHOOSE_REWARD"] + i] = f"CHOOSE_REWARD_{i}"
        m[self.ACTION_OFFSETS["SKIP_REWARD"]] = "SKIP_REWARD"
        return m

    def reset(self):
        if not self.communicator.connected:
            self.communicator.connect()
        
        self.communicator.send_message("RESET")
        # In a real scenario, we might need to wait for the game to actually restart.
        # But the bridge handles RESET by queuing it.
        # We need to wait for the first valid state.
        
        # Simple wait loop
        max_retries = 20
        for _ in range(max_retries):
            state = self.communicator.receive_state()
            if state and state.player.floor <= 1: # Assuming reset goes to floor 0 or 1
                self.game_state = state
                break
            time.sleep(0.1)
            
        if not self.game_state:
             # Fallback
             self.game_state = self.communicator.receive_state()

        self.prev_game_state = self.game_state
        self.current_step = 0
        
        obs = self._get_obs(self.game_state)
        info = self._get_info()
        return obs, info

    def step(self, action_idx):
        self.current_step += 1
        
        command, p1, p2 = self._decode_action(action_idx)
        
        # Send action
        self.communicator.send_message(command, p1, p2)
        
        # Receive new state
        self.game_state = self.communicator.receive_state()
        if self.game_state is None:
            # Connection lost or error
            return self._get_obs(self.prev_game_state), 0, True, False, {"error": "Connection lost"}

        # Calculate Reward
        reward = self._calculate_reward(self.prev_game_state, self.game_state)
        
        # Check Done
        terminated = False
        if self.game_state.game_outcome.is_done:
            terminated = True
            
        truncated = False
        if self.current_step >= self.max_steps:
            truncated = True
            
        obs = self._get_obs(self.game_state)
        info = self._get_info()
        
        self.prev_game_state = self.game_state
        
        return obs, reward, terminated, truncated, info

    def _decode_action(self, action_idx):
        if 0 <= action_idx < 10:
            return "PLAY_CARD", action_idx, 0 # Target logic needs improvement (e.g. first monster)
        elif action_idx == 10:
            return "END_TURN", 0, 0
        elif 11 <= action_idx <= 15:
            return "CHOOSE_MAP_NODE", action_idx - 11, 0 # We use card_index for X, target for Y in Java bridge?
            # Wait, Java bridge expects x in card_index, y in target_index.
            # But here we only have an index 0-4.
            # We need to map 0-4 to actual X,Y of available nodes from the state!
            # Since we don't have the state here easily (or we do via self.game_state),
            # we should look up the Nth available node.
            return self._resolve_map_choice(action_idx - 11)
            
        elif 16 <= action_idx <= 22:
            return "CHOOSE_SHOP_CARD", action_idx - 16, 0
        elif 23 <= action_idx <= 25:
            return "CHOOSE_SHOP_RELIC", action_idx - 23, 0
        elif 26 <= action_idx <= 28:
            return "CHOOSE_SHOP_POTION", action_idx - 26, 0
        elif 29 <= action_idx <= 35:
            return "PURGE_CARD", action_idx - 29, 0
        elif action_idx == 36:
            return "LEAVE_SHOP", 0, 0
        elif 37 <= action_idx <= 41:
            return "CHOOSE_REST_OPTION", action_idx - 37, 0
        elif action_idx == 42:
            return "LEAVE_REST", 0, 0
        elif 43 <= action_idx <= 47:
            return "CHOOSE_REWARD", action_idx - 43, 0
        elif action_idx == 48:
            return "SKIP_REWARD", 0, 0
            
        return "WAIT", 0, 0

    def _resolve_map_choice(self, choice_idx):
        # Find the choice_idx-th available node in the map
        if not self.game_state or not self.game_state.map.nodes:
            return "CHOOSE_MAP_NODE", 0, 0
            
        # Filter nodes that are children of current node?
        # The proto sends all nodes. We need to find "available" ones.
        # The Java bridge `MapNodeState` has `is_available`.
        available_nodes = [n for n in self.game_state.map.nodes if n.is_available]
        
        # Sort by x then y to be deterministic
        # Actually usually there is only 1 row of available nodes?
        # Or we filter by y > current_y?
        # For simplicity, if we have available nodes list:
        if choice_idx < len(available_nodes):
            node = available_nodes[choice_idx]
            return "CHOOSE_MAP_NODE", node.x, node.y
        
        return "CHOOSE_MAP_NODE", 0, 0

    def _get_obs(self, state):
        # --- Player ---
        p = state.player
        player_obs = {
            "current_hp": np.array([p.hp / self.MAX_HP], dtype=np.float32),
            "max_hp": np.array([p.max_hp / self.MAX_HP], dtype=np.float32),
            "gold": np.array([p.gold / self.MAX_GOLD], dtype=np.float32),
            "energy": np.array([p.energy / self.MAX_ENERGY], dtype=np.float32),
            "block": np.array([p.block / self.MAX_BLOCK], dtype=np.float32),
            "floor": np.array([p.floor / self.MAX_FLOOR], dtype=np.float32),
        }
        
        # --- Monsters ---
        monsters_arr = np.zeros((5, 6), dtype=np.float32)
        for i, m in enumerate(state.monsters):
            if i >= 5: break
            # Norm: hp, max_hp, block, intent(enum?), damage?, is_gone
            # Intent mapping: Attack=1, Defend=2, Buff=3... simplified
            intent_val = 0.0
            if "ATTACK" in m.intent: intent_val = 0.2
            elif "DEFEND" in m.intent: intent_val = 0.4
            elif "BUFF" in m.intent: intent_val = 0.6
            elif "DEBUFF" in m.intent: intent_val = 0.8
            
            monsters_arr[i] = [
                m.hp / self.MAX_HP,
                m.max_hp / self.MAX_HP,
                m.block / self.MAX_BLOCK,
                intent_val,
                0.0, # Damage not in proto yet?
                1.0 if m.is_gone else 0.0
            ]
            
        # --- Hand ---
        hand_arr = np.zeros((10, 10), dtype=np.float32)
        for i, c in enumerate(state.hand):
            if i >= 10: break
            # Features: cost, damage, block, type(0-3), is_playable
            type_val = 0.0
            if c.type == "ATTACK": type_val = 0.2
            elif c.type == "SKILL": type_val = 0.4
            elif c.type == "POWER": type_val = 0.6
            
            hand_arr[i] = [
                c.cost / 5.0,
                c.damage / 50.0,
                c.block / 50.0,
                type_val,
                1.0 if c.is_playable else 0.0,
                1.0 if c.is_upgraded else 0.0,
                1.0 if c.exhaust else 0.0,
                0.0, 0.0, 0.0 # Padding
            ]
            
        # --- Global ---
        screen_id = self.SCREEN_TYPES.get(state.screen_type, 9)
        
        return {
            "player": player_obs,
            "monsters": monsters_arr,
            "hand": hand_arr,
            "game_global": {
                "is_combat": 1 if state.screen_type == "COMBAT" else 0,
                "screen_type": screen_id
            }
        }

    def _calculate_reward(self, prev, curr):
        if not prev or not curr: return 0.0
        reward = 0.0
        
        # 1. Immediate Rewards
        # HP
        hp_diff = curr.player.hp - prev.player.hp
        reward += hp_diff * 0.1
        
        # Monster HP (simplified)
        prev_m_hp = sum(m.hp for m in prev.monsters if not m.is_gone)
        curr_m_hp = sum(m.hp for m in curr.monsters if not m.is_gone)
        # If monster died (is_gone became true), hp becomes 0 or stays same?
        # Ideally we track ID. Simplified:
        reward += (prev_m_hp - curr_m_hp) * 0.1
        
        # Gold
        gold_diff = curr.player.gold - prev.player.gold
        reward += gold_diff * 0.01
        
        # 2. Stage Rewards
        if curr.player.floor > prev.player.floor:
            reward += 10.0
            
        # 3. Terminal Rewards
        if curr.game_outcome.is_done:
            if curr.game_outcome.victory:
                reward += 1000.0
            else:
                reward -= 500.0 # Death penalty
                
        # Clipping
        reward = max(min(reward, 10.0), -10.0) # Clip single step reward? 
        # But terminal reward is large. Let's clip only non-terminal?
        # Or just don't clip terminal.
        if abs(reward) > 50: # Likely terminal
            pass
        else:
            reward = max(min(reward, 10.0), -10.0)
            
        return reward

    def _get_info(self):
        return {
            "action_mask": self._generate_action_mask(),
            "original_state": self.game_state # Debug
        }

    def _generate_action_mask(self):
        mask = np.zeros(self.ACTION_SPACE_SIZE, dtype=np.int8)
        if not self.game_state: return mask
        
        screen = self.game_state.screen_type
        
        if screen == "COMBAT":
            mask[self.ACTION_OFFSETS["END_TURN"]] = 1
            for i, c in enumerate(self.game_state.hand):
                if i < 10 and c.is_playable:
                    mask[self.ACTION_OFFSETS["PLAY_CARD"] + i] = 1
        
        elif screen == "MAP":
            # Enable available nodes
            # Assuming we can resolve up to 5 choices
            # We need to count how many available nodes we have
            count = sum(1 for n in self.game_state.map.nodes if n.is_available)
            for i in range(min(count, 5)):
                mask[self.ACTION_OFFSETS["CHOOSE_MAP_NODE"] + i] = 1
                
        elif screen == "SHOP":
            mask[self.ACTION_OFFSETS["LEAVE_SHOP"]] = 1
            # Check gold for buy
            gold = self.game_state.player.gold
            for i, c in enumerate(self.game_state.shop.cards):
                if i < 7 and gold >= c.price:
                    mask[self.ACTION_OFFSETS["BUY_CARD"] + i] = 1
            # ... similarly for relics/potions/purge
            
        elif screen == "REST":
            # Check available rest options
            if self.game_state.rest_site.has_rest:
                mask[self.ACTION_OFFSETS["CHOOSE_REST"] + 0] = 1 # Rest
            if self.game_state.rest_site.has_smith:
                mask[self.ACTION_OFFSETS["CHOOSE_REST"] + 1] = 1 # Smith
            # ...
            
        elif screen == "REWARD":
            mask[self.ACTION_OFFSETS["SKIP_REWARD"]] = 1
            # Count rewards
            count = len(self.game_state.reward.items)
            for i in range(min(count, 5)):
                 mask[self.ACTION_OFFSETS["CHOOSE_REWARD"] + i] = 1
                 
        return mask

    def render(self, mode='human'):
        if mode == 'human':
            if self.game_state:
                print(f"Step: {self.current_step} | Floor: {self.game_state.player.floor} | HP: {self.game_state.player.hp} | Screen: {self.game_state.screen_type}")
        
    def close(self):
        self.communicator.close()
