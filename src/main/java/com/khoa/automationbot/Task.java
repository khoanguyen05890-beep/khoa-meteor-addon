package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;

public abstract class Task {
    public enum State { IDLE, RUNNING, PAUSED, DONE, ERROR }

    public State state = State.IDLE;
    public String status = "";

    public abstract String name();
    public abstract void tick(MinecraftClient mc);
    protected void onStart() {}
    protected void onStop() {}

    /** -1 nếu không xác định được tiến độ. */
    public double progress() { return -1; }

    public void start() { state = State.RUNNING; status = ""; onStart(); }

    public void pause() {
        if (state == State.RUNNING) { state = State.PAUSED; Motion.release(); }
    }

    public void resume() { if (state == State.PAUSED) state = State.RUNNING; }

    public void stop() {
        state = State.IDLE;
        status = "";
        Motion.release();
        onStop();
    }
}
