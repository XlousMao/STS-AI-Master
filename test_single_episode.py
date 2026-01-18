import gym
import gym_sts
import time
import numpy as np

def main():
    print("Initializing SlayTheSpireEnv...")
    env = gym.make("SlayTheSpire-v0", run_mode="headless", port=9999)
    
    print("Resetting environment...")
    try:
        obs, info = env.reset()
        print("Reset complete.")
        print("Initial Observation Keys:", obs.keys())
        
        done = False
        step = 0
        while not done and step < 100:
            step += 1
            # Random action for now, but mask aware
            mask = info["action_mask"]
            valid_actions = np.where(mask == 1)[0]
            
            if len(valid_actions) > 0:
                action = np.random.choice(valid_actions)
            else:
                print("No valid actions! Force ending turn or waiting.")
                action = 10 # End Turn default
            
            print(f"Step {step}: Taking action {action} ({env.action_map.get(action, 'Unknown')})")
            
            obs, reward, terminated, truncated, info = env.step(action)
            done = terminated or truncated
            
            print(f"Reward: {reward:.2f} | HP: {obs['player']['current_hp'][0]*100:.1f}")
            env.render()
            
            time.sleep(0.5)
            
        print("Episode finished.")
        
    except Exception as e:
        print(f"Test failed: {e}")
    finally:
        env.close()

if __name__ == "__main__":
    main()
