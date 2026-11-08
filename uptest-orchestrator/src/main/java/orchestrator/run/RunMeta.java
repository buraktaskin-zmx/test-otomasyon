package orchestrator.run;

/**
 * Where a run comes from, as the UI says it — none of it changes what runs.
 *
 * @param scenarioPath the saved scenario's path ({@code <screen>/<file>}) in
 *                     the run's product, resolved to its id on the server so
 *                     the UI never has to know ids; blank for unsaved text
 * @param testUserId   the saved login's id (testdata/users.yaml)
 * @param trigger      "single" or "queue"; anything else is not recorded
 * @param batchId      one press of the queue's Çalıştır, a UUID
 */
public record RunMeta(String scenarioPath, String testUserId, String trigger, String batchId) {

    public static final RunMeta NONE = new RunMeta(null, null, null, null);
}
