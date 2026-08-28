package server.service;

import server.timeline.StepState;

import java.util.List;

public class CucumberRunnerResult {

    private final ResultStatus status;

    private ErrorDetail error;
    private String output;

    /**
     * The step timeline. Present while the run is going (read live from the
     * registry) and after it ends (copied onto the finished result), so the UI
     * polls one endpoint and never has to switch data sources mid-run.
     */
    private List<StepState> steps;

    /** Index into {@link #steps} of the step currently executing, or -1. */
    private Integer currentStepIndex;

    public CucumberRunnerResult(ResultStatus status) {
        this.status = status;
    }

    public ResultStatus getStatus() {
        return status;
    }

    public ErrorDetail getError() {
        return error;
    }

    public void setError(ErrorDetail error) {
        this.error = error;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getOutput() {
        return output;
    }

    public List<StepState> getSteps() {
        return steps;
    }

    public void setSteps(List<StepState> steps) {
        this.steps = steps;
    }

    public Integer getCurrentStepIndex() {
        return currentStepIndex;
    }

    public void setCurrentStepIndex(Integer currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }
}
