import gym
import gym_sts
import yaml
from stable_baselines3 import PPO
from stable_baselines3.common.env_util import make_vec_env

def main():
    # Load Config
    with open("env_config.yaml", "r") as f:
        config = yaml.safe_load(f)
        
    print("Starting Training with Config:", config)
    
    # Create Environment
    # Note: For parallel training, we would need to manage ports.
    # Here we use a single environment for demonstration.
    env = gym.make("SlayTheSpire-v0", 
                   run_mode=config.get("run_mode", "headless"),
                   port=config.get("port", 9999),
                   normalize_obs=config.get("normalize_obs", True),
                   seed=config.get("seed", 42))
                   
    # Initialize PPO
    model = PPO("MultiInputPolicy", env, verbose=1, tensorboard_log="./ppo_sts_tensorboard/")
    
    # Train
    print("Training started...")
    try:
        model.learn(total_timesteps=10000)
        model.save("ppo_sts_v1")
        print("Training complete. Model saved to ppo_sts_v1.zip")
    except Exception as e:
        print(f"Training failed: {e}")
    finally:
        env.close()

if __name__ == "__main__":
    main()
