package com.quant.finance.decision.live;

/** Published after the job's settings were saved, so the scheduler can re-plan itself. */
public record LiveConfigChanged(LiveSettings settings) {}
