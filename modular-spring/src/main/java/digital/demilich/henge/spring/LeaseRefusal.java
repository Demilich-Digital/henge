package digital.demilich.henge.spring;

import java.util.Map;

/**
 * Why a lease claim was refused: which lease, how much the claimant wanted, and who holds it now
 * ({@code service@version} to amount, for the holders in this process; {@code otherHolders} counts
 * the rest).
 */
record LeaseRefusal(LeaseNeed need, Map<String, Integer> heldByThisProcess, int otherHolders) {

    boolean heldOnlyByThisProcess() {
        return otherHolders == 0;
    }
}
