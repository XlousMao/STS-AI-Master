import gym
import gym_sts
import yaml
from stable_baselines3 import PPO

def main():
    # Load Config
    with open("env_config.yaml", "r") as f:
        config = yaml.safe_load(f)
    
    # Force visual mode for validation
    config["run_mode"] = "visual"
    
    print("Starting Validation with Config:", config)
    
    env = gym.make("SlayTheSpire-v0", 
                   run_mode="visual",
                   port=config.get("port", 9999),
                   normalize_obs=config.get("normalize_obs", True),
                   seed=config.get("seed", 42))
                   
    # Load Model
    try:
        model = PPO.load("ppo_sts_v1")
        print("Model loaded.")
    except:
        print("Model not found, running random policy.")
        model = None
        
    obs, info = env.reset()
    done = False
    total_reward = 0
    
    while not done:
        if model:
            action, _states = model.predict(obs, deterministic=True)
        else:
            action = env.action_space.sample()
            
        obs, reward, terminated, truncated, info = env.step(action)
        done = terminated or truncated
        total_reward += reward
        
        env.render()
        
    print(f"Validation Episode Complete. Total Reward: {total_reward}")
    env.close()

if __name__ == "__main__":
    main()
