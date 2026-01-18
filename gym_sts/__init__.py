from gym.envs.registration import register

register(
    id='SlayTheSpire-v0',
    entry_point='gym_sts.envs.slay_the_spire_env:SlayTheSpireEnv',
)
